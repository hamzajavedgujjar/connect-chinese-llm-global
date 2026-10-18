package ai.norwix.domain

import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object OAuthPrimitives {
    private val random = SecureRandom()
    fun secret(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(bytes).also(random::nextBytes))
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    fun query(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val pairs = raw.split('&').map { pair ->
            val pieces = pair.split('=', limit = 2)
            URLDecoder.decode(pieces[0], "UTF-8") to
                URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "Duplicate callback parameter." }
        return pairs.toMap()
    }
}
