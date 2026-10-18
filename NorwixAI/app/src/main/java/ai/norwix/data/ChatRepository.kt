package ai.norwix.data

import ai.norwix.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ChatRepository(val vault: Vault) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow<AppData?>(null)
    val data = mutable.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = vault.read("workspace.bin")?.decodeToString()
            val parsed = saved?.let { json.decodeFromString<AppData>(it) } ?: AppData()
            require(parsed.version == 1) { "This data needs a newer version of Norwix AI." }
            val recovered = parsed.copy(chats = parsed.chats.map { chat ->
                chat.copy(messages = chat.messages.map { message ->
                    if (message.status == MessageStatus.STREAMING) message.copy(
                        status = MessageStatus.INTERRUPTED,
                        error = "The app closed before the response completed. This response is partial."
                    ) else message
                })
            })
            val ready = if (recovered.chats.isEmpty()) {
                val first = Chat()
                recovered.copy(chats = listOf(first), activeChatId = first.id)
            } else if (recovered.activeChat == null) recovered.copy(activeChatId = recovered.chats.first().id)
            else recovered
            persist(ready)
            mutable.value = ready
            garbageCollect(ready)
        }
    }

    private fun persist(next: AppData) = vault.write("workspace.bin", json.encodeToString(next).toByteArray())
    private fun references(state: AppData): Set<String> = state.chats.flatMap { chat ->
        chat.draftAttachments + chat.messages.flatMap { it.attachments }
    }.map { it.id }.toSet()

    private fun garbageCollect(state: AppData) {
        val used = references(state)
        vault.names().filter { it.startsWith("attachment-") && it.endsWith(".bin") }.forEach { name ->
            if (name.removePrefix("attachment-").removeSuffix(".bin") !in used) vault.delete(name)
        }
    }

    suspend fun update(transform: (AppData) -> AppData) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = checkNotNull(mutable.value) { "Protected storage is still loading." }
            val next = transform(current)
            persist(next) // A failed write never publishes unsaved state.
            mutable.value = next
            val removed = references(current) - references(next)
            removed.forEach { vault.delete("attachment-$it.bin") }
        }
    }

    suspend fun updateChat(id: String, change: (Chat) -> Chat) = update { state ->
        state.copy(chats = state.chats.map { if (it.id == id) change(it) else it })
    }

    suspend fun newChat(provider: Provider = Provider.CHATGPT, accountId: String? = null): String {
        val chat = Chat(provider = provider, accountId = accountId)
        update { it.copy(chats = it.chats + chat, activeChatId = chat.id) }
        return chat.id
    }

    suspend fun deleteChat(id: String) = update { state ->
        val remaining = state.chats.filterNot { it.id == id }.ifEmpty { listOf(Chat()) }
        state.copy(chats = remaining,
            activeChatId = if (state.activeChatId == id) remaining.first().id else state.activeChatId)
    }

    suspend fun saveAccount(account: Account) = update { state ->
        state.copy(accounts = state.accounts.filterNot { it.id == account.id } + account)
    }

    suspend fun erase() = withContext(Dispatchers.IO) {
        mutex.withLock {
            vault.erase()
            val chat = Chat()
            val empty = AppData(chats = listOf(chat), activeChatId = chat.id)
            persist(empty)
            mutable.value = empty
        }
    }

    /** Credentials and host ID are excluded structurally, never by fragile string redaction. */
    fun export(): String {
        val state = checkNotNull(mutable.value)
        return json.encodeToString(WorkspaceExport(
            chats = state.chats,
            accounts = state.accounts.map { PublicAccount(it.id, it.provider, it.label) },
            preferences = state.preferences
        ))
    }
}

@kotlinx.serialization.Serializable
private data class PublicAccount(val id: String, val provider: Provider, val label: String)
@kotlinx.serialization.Serializable
private data class WorkspaceExport(
    val format: String = "norwix-local-export-v1",
    val note: String = "Plaintext. Contains local chat history and memory. Attachment bytes and sign-in tokens are excluded.",
    val chats: List<Chat>,
    val accounts: List<PublicAccount>,
    val preferences: Preferences
)
