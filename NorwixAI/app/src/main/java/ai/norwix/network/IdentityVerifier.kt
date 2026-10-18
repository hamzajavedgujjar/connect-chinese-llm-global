package ai.norwix.network

import ai.norwix.domain.OAuthPrimitives
import ai.norwix.domain.UrlPolicy
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jwt.SignedJWT
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

data class Discovery(val issuer: String, val authorize: String, val token: String,
    val jwks: String, val revoke: String?, val signingAlgorithms: Set<String>)
data class VerifiedIdentity(val subject: String, val email: String, val displayName: String)

class IdentityVerifier(private val http: Http) {
    suspend fun discovery(): Discovery {
        val data = http.objectResponse(Request.Builder()
            .url("https://auth.openai.com/.well-known/openid-configuration").build())
        val issuer = data.string("issuer")
        val authorize = data.string("authorization_endpoint")
        val token = data.string("token_endpoint")
        val jwks = data.string("jwks_uri")
        val revoke = data.string("revocation_endpoint").takeIf { it.isNotBlank() }
        if (issuer != "https://auth.openai.com" ||
            authorize != "https://auth.openai.com/api/accounts/authorize" ||
            token != "https://auth.openai.com/api/accounts/oauth/token" ||
            !UrlPolicy.trustedOpenAi(jwks) || (revoke != null && !UrlPolicy.trustedOpenAi(revoke))) {
            throw UserFacingException("The provider's security configuration changed. Sign-in was stopped safely.")
        }
        val algorithms = data["id_token_signing_alg_values_supported"]?.jsonArray
            ?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        return Discovery(issuer, authorize, token, jwks, revoke, algorithms)
    }

    suspend fun verify(raw: String, clientId: String, nonce: String?, discovery: Discovery): VerifiedIdentity {
        val keys = http.objectResponse(Request.Builder().url(discovery.jwks).build())
        return verifyWithKeys(raw, clientId, nonce, discovery, JWKSet.parse(keys.toString()))
    }

    internal fun verifyWithKeys(raw: String, clientId: String, nonce: String?, discovery: Discovery, keys: JWKSet): VerifiedIdentity {
        if (raw.length > 64_000) throw UserFacingException("The sign-in token is too large.")
        val jwt = SignedJWT.parse(raw)
        val algorithm = jwt.header.algorithm
        // Never follow token-supplied jku/x5u URLs. Keys come only from trusted discovery.
        if (algorithm !in setOf(JWSAlgorithm.RS256, JWSAlgorithm.ES256) ||
            algorithm.name !in discovery.signingAlgorithms || jwt.header.keyID.isNullOrBlank() ||
            !jwt.header.criticalParams.isNullOrEmpty()) {
            throw UserFacingException("The sign-in token uses an unsupported signature. No account was changed.")
        }
        val key = keys.getKeyByKeyId(jwt.header.keyID)
            ?: throw UserFacingException("The provider's signing key could not be verified. Try signing in again.")
        if (key.algorithm != null && key.algorithm != algorithm) throw UserFacingException("The sign-in signature did not match.")
        if ((key.keyUse != null && key.keyUse != KeyUse.SIGNATURE) ||
            (!key.keyOperations.isNullOrEmpty() && KeyOperation.VERIFY !in key.keyOperations)) {
            throw UserFacingException("The sign-in key is not authorized for signature verification.")
        }
        val valid = when {
            algorithm == JWSAlgorithm.RS256 && key is RSAKey && key.toRSAPublicKey().modulus.bitLength() >= 2048 -> jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))
            algorithm == JWSAlgorithm.ES256 && key is ECKey -> jwt.verify(ECDSAVerifier(key.toECPublicKey()))
            else -> false
        }
        if (!valid) throw UserFacingException("The sign-in signature could not be verified. No account was changed.")
        val claims = jwt.jwtClaimsSet
        val now = System.currentTimeMillis()
        val audience = claims.audience.orEmpty()
        if (claims.issuer != discovery.issuer || clientId !in audience ||
            claims.subject.isNullOrBlank() || claims.expirationTime == null ||
            claims.expirationTime.time <= now - 5000 || claims.issueTime == null ||
            claims.issueTime.time > now + 5000 || (claims.notBeforeTime?.time ?: 0) > now + 5000 ||
            (audience.size > 1 && claims.getStringClaim("azp") != clientId) ||
            (nonce != null && !OAuthPrimitives.same(claims.getStringClaim("nonce").orEmpty(), nonce))) {
            throw UserFacingException("The sign-in identity, audience, time, or nonce could not be verified.")
        }
        val email = claims.getStringClaim("email").orEmpty().take(254)
        return VerifiedIdentity(claims.subject, email, claims.getStringClaim("name").orEmpty().take(120))
    }
}
