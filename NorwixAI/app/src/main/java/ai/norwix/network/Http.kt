package ai.norwix.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class UserFacingException(message: String) : Exception(message)

/** No HTTP logging, cookie jar, redirects, retry interceptor, or API-key fallback. */
class Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(
                    UserFacingException("The secure connection failed. Check your connection and try again.")))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    suspend fun objectResponse(request: Request): JsonObject = withContext(Dispatchers.IO) {
        execute(request).use { response -> coroutineScope {
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { response.close() }
            }
            try {
                if (!response.isSuccessful) throw failure(response)
                val body = response.body ?: throw UserFacingException("The provider returned an empty response.")
                if (body.contentLength() > 2_000_000) throw UserFacingException("The provider response is too large.")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                body.byteStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 2_000_000) throw UserFacingException("The provider response is too large.")
                        output.write(buffer, 0, count)
                    }
                }
                json.parseToJsonElement(output.toString("UTF-8")).jsonObject
            } finally { closer.cancel() }
        } }
    }

    fun failure(response: Response): UserFacingException {
        val code = runCatching {
            val body = response.peekBody(32_768).string()
            val root = json.parseToJsonElement(body).jsonObject
            val error = root["error"]
            if (error is JsonObject) error["code"]?.jsonPrimitive?.contentOrNull
            else error?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return UserFacingException(when (code) {
            "invalid_grant", "invalid_token" -> "This sign-in has expired or was revoked. Reconnect this account in Login Accounts."
            "subscription_sharing_usage_limit_exceeded" -> "Your shared ChatGPT plan usage limit was reached. Review your limits in ChatGPT Settings → Usage. No other billing method will be used."
            "subscription_sharing_usage_unavailable" -> "ChatGPT plan usage is temporarily unavailable. Try again later."
            "subscription_sharing_unsupported_capability" -> "This model or input is unavailable through ChatGPT plan usage. Choose another account-supported model or remove the attachment."
            "insufficient_scope", "subscription_sharing_not_enabled" -> "ChatGPT plan permission is missing. Reconnect the account and review the optional plan-usage consent."
            else -> when (response.code) {
                401 -> "This account needs a fresh sign-in. Reconnect it in Login Accounts."
                403 -> "The provider did not allow this request. Check account eligibility, workspace policy, and plan-usage consent."
                429 -> "The provider's usage limit was reached. Wait before trying again."
                in 500..599 -> "The provider is temporarily unavailable. Try again later."
                else -> "The provider rejected the request (HTTP ${response.code}). Review the model, attachments, and connection."
            }
        })
    }
}

fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
