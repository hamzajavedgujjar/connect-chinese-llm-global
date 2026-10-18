package ai.norwix.domain

import kotlinx.serialization.json.*

class StreamFailure(val code: String) : Exception("Stream did not complete: $code")

/** A transport-independent state machine. Partial text is never a successful completion. */
class ResponseAccumulator {
    private val json = Json { ignoreUnknownKeys = true }
    private val text = StringBuilder()
    var completed = false
        private set
    val partial: String get() = text.toString()

    fun accept(data: String): String? {
        if (data == "[DONE]" || completed) return null
        val event = json.parseToJsonElement(data).jsonObject
        fun JsonObject.value(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        when (event.value("type")) {
            "response.output_text.delta", "response.refusal.delta" -> {
                text.append(event.value("delta"))
                if (text.length > 1_000_000) throw StreamFailure("local_output_limit")
                return partial
            }
            "response.completed" -> {
                if (text.isEmpty()) event["response"]?.jsonObject?.get("output")?.jsonArray.orEmpty().forEach { item ->
                    item.jsonObject["content"]?.jsonArray.orEmpty().forEach { part ->
                        val content = part.jsonObject
                        when (content.value("type")) {
                            "output_text" -> text.append(content.value("text"))
                            "refusal" -> text.append(content.value("refusal"))
                        }
                    }
                }
                if (text.length > 1_000_000) throw StreamFailure("local_output_limit")
                completed = true
                return partial
            }
            "response.failed", "error" -> {
                val error = event["response"]?.jsonObject?.get("error") as? JsonObject
                    ?: event["error"] as? JsonObject ?: event
                throw StreamFailure(error.value("code").ifBlank { "response_failed" })
            }
            "response.incomplete" -> throw StreamFailure("response_incomplete")
        }
        return null
    }

    fun finish(): String {
        if (!completed) throw StreamFailure("stream_interrupted")
        if (text.isBlank()) throw StreamFailure("empty_response")
        return partial
    }
}
