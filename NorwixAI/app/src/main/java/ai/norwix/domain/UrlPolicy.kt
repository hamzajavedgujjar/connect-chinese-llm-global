package ai.norwix.domain

import java.net.URI

object UrlPolicy {
    /** Only explicit official provider origins, no credentials or token-bearing query. */
    fun validConversation(provider: Provider, raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val expected = URI(provider.home).host
        uri.scheme == "https" && uri.host == expected && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.rawFragment == null && uri.rawQuery == null
    }.getOrDefault(false)

    fun trustedOpenAi(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.host == "auth.openai.com" &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)
}
