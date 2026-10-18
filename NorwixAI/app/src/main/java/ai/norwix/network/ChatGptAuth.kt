package ai.norwix.network

import ai.norwix.data.ChatRepository
import ai.norwix.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Official OSS dynamic-agent flow documented at developers.openai.com/siwc. */
class ChatGptAuth(private val repository: ChatRepository, private val http: Http) {
    private val verifier = IdentityVerifier(http)
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit): Account {
        val workspace = checkNotNull(repository.data.value)
        val existing = workspace.accounts.find { it.id == existingId }
        val metadata = verifier.discovery()
        val state = OAuthPrimitives.secret()
        val nonce = OAuthPrimitives.secret()
        val pkce = OAuthPrimitives.secret(64)
        return withContext(Dispatchers.IO) {
            LoopbackCallback().use { receiver ->
                val url = metadata.authorize.toHttpUrl().newBuilder()
                    .addQueryParameter("client_id", existing?.clientId ?: "dynamic_agent_client")
                    .addQueryParameter("ext_agent_host_id", workspace.hostId)
                    .addQueryParameter("response_type", "code")
                    .addQueryParameter("redirect_uri", receiver.redirectUri)
                    .addQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                    .addQueryParameter("resource", "https://api.openai.com/v1")
                    .addQueryParameter("state", state)
                    .addQueryParameter("nonce", nonce)
                    .addQueryParameter("code_challenge_method", "S256")
                    .addQueryParameter("code_challenge", OAuthPrimitives.challenge(pkce))
                if (existing == null) url.addQueryParameter("agent_name_hint", "Norwix AI")
                else {
                    existing.credentials?.idToken?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("id_token_hint", it) }
                    if (existing.email.isNotBlank()) url.addQueryParameter("login_hint", existing.email)
                    if (!existing.nativeChatAvailable) url.addQueryParameter("prompt", "consent")
                }
                withContext(Dispatchers.Main) { openBrowser(url.build().toString()) }
                val result = receiver.await(state)
                if (result["error"] != null) throw UserFacingException("Sign-in or plan-usage consent was declined. No account was changed.")
                val issued = result["client_id"] ?: existing?.clientId
                if (issued.isNullOrBlank() || issued == "dynamic_agent_client" ||
                    (existing != null && existing.clientId != issued)) {
                    throw UserFacingException("The account registration could not be verified. Start sign-in again.")
                }
                val code = result["code"].orEmpty()
                if (code.isBlank()) throw UserFacingException("Sign-in did not return an authorization code.")
                val tokens = http.objectResponse(Request.Builder().url(metadata.token).post(FormBody.Builder()
                    .add("grant_type", "authorization_code").add("client_id", issued).add("code", code)
                    .add("code_verifier", pkce).add("redirect_uri", receiver.redirectUri)
                    .add("resource", "https://api.openai.com/v1").build()).build())
                val identity = verifier.verify(tokens.string("id_token"), issued, nonce, metadata)
                if (existing != null && existing.subject != identity.subject) {
                    throw UserFacingException("A different account signed in. Use Add account to keep accounts separate.")
                }
                val credential = credential(tokens, null)
                val account = Account(
                    id = existing?.id ?: newId(), provider = Provider.CHATGPT,
                    label = existing?.label ?: "${identity.email.ifBlank { identity.displayName.ifBlank { "ChatGPT account" } }} · ${issued.takeLast(6)}",
                    subject = identity.subject, clientId = issued, email = identity.email,
                    credentials = credential
                )
                // De-duplicate only an identical client+subject; never merge accounts by email.
                val identical = repository.data.value?.accounts?.find {
                    it.provider == Provider.CHATGPT && it.clientId == issued && it.subject == identity.subject
                }
                val saved = account.copy(id = existing?.id ?: identical?.id ?: account.id)
                repository.saveAccount(saved)
                saved
            }
        }
    }

    private fun credential(data: kotlinx.serialization.json.JsonObject, previous: Credentials?): Credentials {
        val access = data.string("access_token")
        if (access.isNotEmpty() && !data.string("token_type").equals("Bearer", ignoreCase = true)) {
            throw UserFacingException("The provider returned an unsupported token type.")
        }
        val seconds = data.long("expires_in") ?: 0
        if (access.isNotEmpty() && (seconds <= 0 || seconds > 604800)) {
            throw UserFacingException("The provider returned an invalid session lifetime.")
        }
        val granted = data.string("scope").takeIf { it.isNotBlank() }?.split(' ')
            ?.filter { it.isNotBlank() }?.toSet() ?: previous?.scopes.orEmpty()
        return Credentials(accessToken = access,
            refreshToken = data.string("refresh_token").ifBlank { previous?.refreshToken.orEmpty() },
            idToken = data.string("id_token").ifBlank { previous?.idToken.orEmpty() },
            expiresAt = System.currentTimeMillis() + seconds * 1000,
            scopes = granted)
    }

    suspend fun ready(accountId: String): Account = locks.getOrPut(accountId) { Mutex() }.withLock {
        val account = repository.data.value?.accounts?.find { it.id == accountId }
            ?: throw UserFacingException("Choose a connected ChatGPT account in Login Accounts.")
        if (!account.nativeChatAvailable) throw UserFacingException("This account has not granted ChatGPT plan usage. Reconnect it and review consent.")
        val old = checkNotNull(account.credentials)
        if (old.expiresAt > System.currentTimeMillis() + 60_000) return@withLock account
        if (old.refreshToken.isEmpty()) throw UserFacingException("This account needs a fresh sign-in. Reconnect it in Login Accounts.")
        val metadata = verifier.discovery()
        val token = http.objectResponse(Request.Builder().url(metadata.token).post(FormBody.Builder()
            .add("grant_type", "refresh_token").add("client_id", account.clientId)
            .add("refresh_token", old.refreshToken).add("resource", "https://api.openai.com/v1").build()).build())
        val nextCredential = credential(token, old)
        if (token.string("id_token").isNotBlank()) {
            val identity = verifier.verify(token.string("id_token"), account.clientId, null, metadata)
            if (identity.subject != account.subject) throw UserFacingException("Session renewal returned a different identity. Reconnect this account.")
        }
        val updated = account.copy(credentials = nextCredential)
        repository.update { current ->
            val latest = current.accounts.find { it.id == accountId }
            if (latest?.credentials?.refreshToken != old.refreshToken) {
                throw UserFacingException("The account session changed during renewal. Select the account again.")
            }
            current.copy(accounts = current.accounts.map { if (it.id == accountId) updated else it })
        }
        if (!updated.nativeChatAvailable) throw UserFacingException("ChatGPT plan permission is no longer enabled. Reconnect this account.")
        updated
    }

    /** Clears local tokens regardless of remote revocation availability. Returns remote status. */
    suspend fun signOut(accountId: String): Boolean = locks.getOrPut(accountId) { Mutex() }.withLock {
        val account = repository.data.value?.accounts?.find { it.id == accountId } ?: return@withLock true
        val refresh = account.credentials?.refreshToken.orEmpty()
        var revoked = refresh.isBlank()
        try {
            if (refresh.isNotBlank()) {
                val endpoint = verifier.discovery().revoke
                if (endpoint != null) repeat(2) { attempt ->
                    if (!revoked) {
                        http.execute(Request.Builder().url(endpoint).post(FormBody.Builder()
                            .add("token", refresh).add("token_type_hint", "refresh_token")
                            .add("client_id", account.clientId).build()).build()).use { revoked = it.code == 200 }
                        if (!revoked && attempt == 0) delay(500)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { revoked = false }
        finally {
            withContext(NonCancellable) {
                repository.update { state -> state.copy(accounts = state.accounts.map {
                    if (it.id == accountId) it.copy(credentials = null) else it
                }) }
            }
        }
        revoked
    }
}
