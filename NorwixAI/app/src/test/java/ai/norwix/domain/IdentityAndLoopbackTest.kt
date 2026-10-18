package ai.norwix.domain

import ai.norwix.network.*
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.Date

class IdentityAndLoopbackTest {
    private val signingKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val discovery = Discovery("https://auth.openai.com", "https://auth.openai.com/api/accounts/authorize",
        "https://auth.openai.com/api/accounts/oauth/token", "https://auth.openai.com/.well-known/jwks.json", null, setOf("RS256"))
    private fun token(issuer: String = discovery.issuer, audience: String = "test-client", nonce: String = "test-nonce",
        expiry: Long = System.currentTimeMillis() + 120_000): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("test-subject")
            .issueTime(Date()).expirationTime(Date(expiry)).claim("nonce", nonce).claim("email", "test@example.invalid").build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims)
            .apply { sign(RSASSASigner(signingKey)) }.serialize()
    }

    @Test fun signedIdentityIsValidatedBeforeUse() {
        val identity = IdentityVerifier(Http()).verifyWithKeys(token(), "test-client", "test-nonce", discovery, JWKSet(signingKey.toPublicJWK()))
        assertEquals("test-subject", identity.subject)
    }

    @Test fun wrongIssuerAudienceNonceExpiryAndSignatureAreRejected() {
        val verifier = IdentityVerifier(Http())
        val keys = JWKSet(signingKey.toPublicJWK())
        listOf(token(issuer = "https://attacker.invalid"), token(audience = "another-client"),
            token(nonce = "wrong-nonce"), token(expiry = System.currentTimeMillis() - 60_000)).forEach { jwt ->
            assertThrows(UserFacingException::class.java) { verifier.verifyWithKeys(jwt, "test-client", "test-nonce", discovery, keys) }
        }
        val wrongKey = RSAKeyGenerator(2048).keyID("test-key").generate()
        assertThrows(UserFacingException::class.java) { verifier.verifyWithKeys(token(), "test-client", "test-nonce", discovery, JWKSet(wrongKey.toPublicJWK())) }
    }

    @Test fun loopbackRejectsWrongStateBeforeAcceptingTheRealCallback() = runBlocking {
        withTimeout(10_000) {
            LoopbackCallback().use { receiver ->
                val awaiting = async(Dispatchers.IO) { receiver.await("correct") }
                suspend fun request(state: String): String = withContext(Dispatchers.IO) {
                    Socket("127.0.0.1", URI(receiver.redirectUri).port).use { socket ->
                        socket.soTimeout = 3000
                        socket.getOutputStream().write("GET /auth/callback?state=$state&code=test-code&client_id=test-client HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                        socket.getInputStream().bufferedReader().readLine()
                    }
                }
                assertTrue(request("wrong").contains("400"))
                assertFalse(awaiting.isCompleted)
                assertTrue(request("correct").contains("200"))
                assertEquals("test-code", awaiting.await()["code"])
            }
        }
    }
}
