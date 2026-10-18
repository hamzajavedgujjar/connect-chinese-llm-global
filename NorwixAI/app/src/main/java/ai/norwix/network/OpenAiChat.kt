package ai.norwix.network

import ai.norwix.data.Attachments
import ai.norwix.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

class OpenAiChat(private val http: Http, private val auth: ChatGptAuth, private val attachments: Attachments) {
    suspend fun models(accountId: String): List<AvailableModel> {
        val account = auth.ready(accountId)
        val root = http.objectResponse(Request.Builder().url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer ${account.credentials!!.accessToken}").build())
        return root["models"]?.jsonArray.orEmpty().mapNotNull { value ->
            val model = value.jsonObject
            if (model.string("visibility") != "list" || model.string("slug").isBlank()) null
            else AvailableModel(model.string("slug"), model.string("display_name").ifBlank { model.string("slug") })
        }.ifEmpty { throw UserFacingException("No models are available for this account. Check plan access or reconnect.") }
    }

    suspend fun stream(accountId: String, model: String, context: ConversationContext,
        onText: suspend (String) -> Unit): String = withContext(Dispatchers.IO) {
        val account = auth.ready(accountId)
        val allAttachments = context.turns.flatMap { it.attachments }.distinctBy { it.id }
        if (allAttachments.sumOf { it.size } > Attachments.MAX_REQUEST_BYTES) {
            throw UserFacingException("This conversation's attachments exceed 20 MB. Start a new chat with fewer files.")
        }
        val body = buildJsonObject {
            put("model", model)
            put("instructions", context.instructions)
            put("store", false)
            put("stream", true)
            putJsonArray("input") {
                for (turn in context.turns) add(buildJsonObject {
                    put("role", if (turn.role == Role.USER) "user" else "assistant")
                    if (turn.role == Role.ASSISTANT) put("content", turn.text)
                    else putJsonArray("content") {
                        if (turn.text.isNotEmpty()) add(buildJsonObject { put("type", "input_text"); put("text", turn.text) })
                        for (file in turn.attachments) {
                            val bytes = attachments.bytes(file)
                            when {
                                file.mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif") -> add(buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", "data:${file.mime};base64,${Base64.getEncoder().encodeToString(bytes)}")
                                })
                                file.mime == "application/pdf" -> add(buildJsonObject {
                                    put("type", "input_file"); put("filename", file.name)
                                    put("file_data", "data:application/pdf;base64,${Base64.getEncoder().encodeToString(bytes)}")
                                })
                                file.mime.startsWith("text/") || file.mime in setOf("application/json", "application/xml") -> {
                                    val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }
                                        .getOrElse { throw UserFacingException("${file.name} is not UTF-8 text. Use a PDF instead.") }
                                    if (text.length > 120_000) throw UserFacingException("A text attachment is too long. Choose a smaller file.")
                                    add(buildJsonObject { put("type", "input_text"); put("text", "Attachment: ${file.name}\n$text") })
                                }
                                else -> throw UserFacingException("This file format is not supported in native chat. Use an image, PDF, or UTF-8 text file, or attach it on the official provider page.")
                            }
                        }
                    }
                })
            }
        }
        val request = Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${account.credentials!!.accessToken}")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.execute(request).use { response ->
            coroutineScope {
                // Close a blocked socket immediately when Stop is pressed.
                val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { response.close() }
                }
                val accumulator = ResponseAccumulator()
                try {
                    if (!response.isSuccessful) throw http.failure(response)
                    if (response.body?.contentType()?.toString()?.startsWith("text/event-stream") != true) {
                        throw UserFacingException("The provider returned an unexpected response format.")
                    }
                    SseParser.consume(checkNotNull(response.body).charStream().buffered(),
                        cancelled = { !isActive || accumulator.completed }) { data ->
                        accumulator.accept(data)?.let { onText(it) }
                    }
                    currentCoroutineContext().ensureActive()
                    accumulator.finish()
                } catch (failure: StreamFailure) {
                    throw UserFacingException(when (failure.code) {
                        "subscription_sharing_usage_limit_exceeded" -> "ChatGPT plan usage was exhausted during the response. This reply is partial. Review ChatGPT Settings → Usage."
                        "subscription_sharing_usage_unavailable" -> "ChatGPT plan usage became unavailable. This reply is partial. Try again later."
                        "response_incomplete", "stream_interrupted" -> "The response ended before completion. Any text shown is partial."
                        "empty_response" -> "The provider completed the request without a text reply."
                        "local_output_limit" -> "The response exceeded the local display limit. Any text shown is partial."
                        else -> "The provider could not complete the response. Any text shown is partial."
                    })
                } finally { closer.cancel() }
            }
        }
    }
}
