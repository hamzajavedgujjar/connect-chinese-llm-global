package ai.norwix.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.norwix.NorwixApplication
import ai.norwix.domain.*
import ai.norwix.network.UserFacingException
import ai.norwix.platform.VoiceController
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class Handoff(val provider: Provider, val text: String, val chatId: String)
data class UiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val busy: String? = null,
    val signingIn: Boolean = false,
    val modelsLoading: Boolean = false,
    val models: List<AvailableModel> = emptyList(),
    val generationChatId: String? = null,
    val handoff: Handoff? = null,
    val error: String? = null
)

sealed interface UiEvent {
    data class AuthBrowser(val url: String) : UiEvent
    data class ProviderPage(val provider: Provider, val link: String = "") : UiEvent
    data class Notice(val text: String) : UiEvent
    data object Accounts : UiEvent
}

class NorwixViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NorwixApplication
    val repository = app.repository
    val data = repository.data
    val voice = VoiceController(application)
    private val mutableUi = MutableStateFlow(UiState())
    val ui = mutableUi.asStateFlow()
    private val mutableDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts = mutableDrafts.asStateFlow()
    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var generation: Job? = null
    private var authentication: Job? = null
    private var modelLoading: Job? = null
    private val draftJobs = mutableMapOf<String, Job>()
    private var foreground = false

    init { load() }

    private fun set(change: (UiState) -> UiState) { mutableUi.update(change) }
    private fun error(error: Exception) {
        set { it.copy(error = (error as? UserFacingException)?.message ?:
            "The operation could not be completed. Check your connection, storage, and selected files, then try again.") }
    }
    fun clearError() = set { it.copy(error = null) }
    fun report(failure: Exception) = error(failure)
    fun notice(text: String) { viewModelScope.launch { eventChannel.send(UiEvent.Notice(text)) } }

    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error(failure) }
    } }

    fun load() { action {
        set { it.copy(loading = true, loadFailed = false) }
        try {
            repository.load()
            set { it.copy(loading = false) }
            refreshModels()
        } catch (failure: Exception) {
            set { it.copy(loading = false, loadFailed = true) }
        }
    } }

    fun editDraft(chatId: String, text: String) {
        mutableDrafts.update { it + (chatId to text.take(60_000)) }
        draftJobs.remove(chatId)?.cancel()
        draftJobs[chatId] = viewModelScope.launch {
            delay(300)
            try { repository.updateChat(chatId) { it.copy(draft = text.take(60_000)) } }
            catch (failure: Exception) { if (failure !is CancellationException) error(failure) }
        }
    }

    private suspend fun persistDrafts() {
        draftJobs.values.forEach { it.cancel() }; draftJobs.clear()
        val drafts = mutableDrafts.value
        if (data.value != null) repository.update { state ->
            state.copy(chats = state.chats.map { chat -> chat.copy(draft = drafts[chat.id] ?: chat.draft) })
        }
    }
    fun flushDrafts() { action { persistDrafts() } }

    fun newChat() { action {
        voice.stop()
        val current = data.value?.activeChat
        repository.newChat(current?.provider ?: Provider.CHATGPT, current?.accountId)
        refreshModels()
    } }
    fun selectChat(id: String) { action {
        voice.stop(); persistDrafts()
        repository.update { state -> state.copy(activeChatId = id.takeIf { id -> state.chats.any { it.id == id } } ?: state.activeChatId) }
        refreshModels()
    } }
    fun renameChat(id: String, title: String) { action {
        if (title.isBlank()) throw UserFacingException("A conversation needs a name.")
        repository.updateChat(id) { it.copy(title = title.trim().take(100)) }
    } }
    fun deleteChat(id: String) { action {
        if (ui.value.generationChatId == id) generation?.cancelAndJoin()
        voice.stop(); draftJobs.remove(id)?.cancel()
        mutableDrafts.update { it - id }
        repository.deleteChat(id); refreshModels()
    } }
    fun deleteMessage(chatId: String, messageId: String) { action {
        if (ui.value.generationChatId == chatId) generation?.cancelAndJoin()
        voice.stop()
        repository.updateChat(chatId) { chat -> chat.copy(messages = chat.messages.filterNot { it.id == messageId }) }
    } }

    fun chooseProvider(provider: Provider) { action {
        authentication?.cancelAndJoin()
        stopAndWait()
        val id = data.value?.activeChatId ?: return@action
        repository.updateChat(id) { it.copy(provider = provider, accountId = null, model = null, browserChatUrl = "") }
        set { it.copy(models = emptyList()) }
    } }
    fun chooseAccount(accountId: String) { action {
        authentication?.cancelAndJoin()
        stopAndWait()
        val state = data.value ?: return@action
        val account = state.accounts.find { it.id == accountId } ?: return@action
        repository.updateChat(state.activeChatId ?: return@action) {
            it.copy(provider = account.provider, accountId = account.id, model = null, browserChatUrl = "")
        }
        refreshModels()
    } }
    fun chooseModel(slug: String) { action {
        stopAndWait()
        if (slug !in ui.value.models.map { it.slug }) throw UserFacingException("Refresh the account's model list before selecting a model.")
        repository.updateChat(data.value?.activeChatId ?: return@action) { it.copy(model = slug) }
    } }

    fun signIn(existingId: String? = null) {
        if (authentication?.isActive == true) return
        authentication = viewModelScope.launch {
            set { it.copy(signingIn = true) }
            try {
                stopAndWait()
                val account = app.auth.signIn(existingId) { eventChannel.send(UiEvent.AuthBrowser(it)) }
                val chatId = data.value?.activeChatId
                if (chatId != null) repository.updateChat(chatId) {
                    it.copy(provider = Provider.CHATGPT, accountId = account.id, model = null, browserChatUrl = "")
                }
                notice(if (account.nativeChatAvailable) "Identity verified. ChatGPT plan permission was granted; loading available models."
                    else "Identity verified. ChatGPT plan usage was not granted; native chat remains unavailable.")
                refreshModels()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure) }
            finally { set { it.copy(signingIn = false) } }
        }
    }
    fun cancelSignIn() { authentication?.cancel() }

    fun signOut(accountId: String) { action {
        authentication?.cancelAndJoin()
        stopAndWait()
        modelLoading?.cancelAndJoin()
        val revoked = app.auth.signOut(accountId)
        set { it.copy(models = emptyList()) }
        notice(if (revoked) "Signed out of this account. Other accounts remain connected."
            else "Local sign-in tokens were cleared. Remote revocation could not be confirmed; disconnect Norwix AI in ChatGPT Settings → Security and login.")
    } }
    fun renameAccount(id: String, label: String) { action {
        if (label.isBlank()) throw UserFacingException("Enter a label for the account.")
        repository.update { state -> state.copy(accounts = state.accounts.map { if (it.id == id) it.copy(label = label.trim().take(100)) else it }) }
    } }
    fun forgetAccount(id: String) { action {
        authentication?.cancelAndJoin()
        stopAndWait()
        modelLoading?.cancelAndJoin()
        val revoked = app.auth.signOut(id)
        repository.update { state -> state.copy(accounts = state.accounts.filterNot { it.id == id },
            chats = state.chats.map { if (it.accountId == id) it.copy(accountId = null, model = null) else it }) }
        refreshModels()
        if (!revoked) notice("The connection was removed locally. Disconnect Norwix AI in ChatGPT Settings to confirm remote revocation.")
    } }

    fun refreshModels() {
        modelLoading?.cancel()
        val state = data.value
        val chat = state?.activeChat
        val account = state?.accounts?.find { it.id == chat?.accountId }
        set { it.copy(models = emptyList(), modelsLoading = false) }
        if (account?.nativeChatAvailable != true) return
        modelLoading = viewModelScope.launch {
            set { it.copy(modelsLoading = true) }
            try {
                val models = app.chat.models(account.id)
                if (data.value?.activeChatId == chat?.id && data.value?.activeChat?.accountId == account.id) {
                    set { it.copy(models = models) }
                    repository.updateChat(checkNotNull(chat).id) { current ->
                        current.copy(model = current.model?.takeIf { old -> models.any { it.slug == old } } ?: models.first().slug)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure) }
            finally { set { it.copy(modelsLoading = false) } }
        }
    }

    fun attach(uris: List<Uri>) { action {
        val chatId = data.value?.activeChatId ?: return@action
        if (uris.isEmpty()) return@action
        set { it.copy(busy = "Encrypting attachments…") }
        val imported = mutableListOf<Attachment>()
        try {
            val existing = data.value?.activeChat?.draftAttachments.orEmpty()
            if (existing.size + uris.size > 6) throw UserFacingException("Attach up to six files per message.")
            if (data.value!!.chats.flatMap { it.messages.flatMap { m -> m.attachments } + it.draftAttachments }
                    .distinctBy { it.id }.sumOf { it.size } > 200L * 1024 * 1024) {
                throw UserFacingException("The local attachment limit is 200 MB. Delete files from older chats first.")
            }
            for (uri in uris) imported += app.attachments.import(uri)
            val currentBytes = data.value!!.chats.flatMap { it.messages.flatMap { m -> m.attachments } + it.draftAttachments }
                .distinctBy { it.id }.sumOf { it.size }
            if (currentBytes + imported.sumOf { it.size } > 200L * 1024 * 1024) {
                throw UserFacingException("These files would exceed the 200 MB local attachment limit. Delete older attachments first.")
            }
            repository.updateChat(chatId) { it.copy(draftAttachments = it.draftAttachments + imported) }
        } catch (failure: Exception) {
            withContext(Dispatchers.IO) { imported.forEach { repository.vault.delete("attachment-${it.id}.bin") } }
            throw failure
        } finally { set { it.copy(busy = null) } }
    } }
    fun removeAttachment(chatId: String, attachmentId: String) { action {
        repository.updateChat(chatId) { it.copy(draftAttachments = it.draftAttachments.filterNot { file -> file.id == attachmentId }) }
    } }

    fun send() {
        val state = data.value ?: return
        val chat = state.activeChat ?: return
        if (generation?.isActive == true || ui.value.busy != null) return
        val text = (mutableDrafts.value[chat.id] ?: chat.draft).trim()
        if (text.isEmpty() && chat.draftAttachments.isEmpty()) return
        val account = state.accounts.find { it.id == chat.accountId }
        if (chat.provider == Provider.CHATGPT && account?.nativeChatAvailable != true) {
            action { eventChannel.send(UiEvent.Accounts) }; return
        }
        if (chat.provider == Provider.CHATGPT && (chat.model == null || ui.value.models.none { it.slug == chat.model })) {
            notice("Select an available model in the menu. Refresh the list if needed."); return
        }
        draftJobs.remove(chat.id)?.cancel()
        set { it.copy(generationChatId = chat.id) }
        generation = viewModelScope.launch {
            val native = chat.provider == Provider.CHATGPT
            val user = Message(role = Role.USER, text = text, attachments = chat.draftAttachments,
                status = if (native) MessageStatus.COMPLETE else MessageStatus.LOCAL_ONLY,
                source = if (native) "You" else "Saved locally · paste in ${chat.provider.title}")
            var assistantId: String? = null
            var partial = ""
            var userSaved = false
            try {
                voice.stop()
                repository.updateChat(chat.id) { current -> current.copy(
                    draft = "", draftAttachments = emptyList(), messages = current.messages + user,
                    title = if (current.messages.isEmpty() && current.title == "New conversation") text.take(50).ifBlank { "Attachment conversation" } else current.title,
                    updatedAt = System.currentTimeMillis()) }
                userSaved = true
                mutableDrafts.update { it + (chat.id to "") }
                val current = data.value!!.chats.first { it.id == chat.id }
                val context = ContextBuilder.build(current, data.value!!.preferences)
                if (!native) {
                    set { it.copy(handoff = Handoff(chat.provider, ContextBuilder.handoff(context), chat.id)) }
                    return@launch
                }
                val connectedAccount = checkNotNull(account)
                val reply = Message(role = Role.ASSISTANT, text = "", status = MessageStatus.STREAMING,
                    source = "ChatGPT · ${chat.model}", accountId = connectedAccount.id, model = chat.model)
                assistantId = reply.id
                repository.updateChat(chat.id) { it.copy(messages = it.messages + reply) }
                var lastSave = 0L
                val final = app.chat.stream(connectedAccount.id, chat.model!!, context) { cumulative ->
                    partial = cumulative
                    if (System.nanoTime() - lastSave > 150_000_000L) {
                        repository.updateChat(chat.id) { currentChat -> currentChat.copy(messages = currentChat.messages.map {
                            if (it.id == reply.id) it.copy(text = cumulative) else it
                        }) }
                        lastSave = System.nanoTime()
                    }
                }
                repository.updateChat(chat.id) { currentChat -> currentChat.copy(updatedAt = System.currentTimeMillis(), messages = currentChat.messages.map {
                    if (it.id == reply.id) it.copy(text = final, status = MessageStatus.COMPLETE) else it
                }) }
                if (foreground && data.value?.activeChatId == chat.id && data.value?.preferences?.autoSpeak == true) {
                    voice.speak(final, data.value!!.preferences)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { assistantId?.let { id -> markReply(chat.id, id, partial, MessageStatus.INTERRUPTED, "Stopped. This response is partial.") } }
                throw cancelled
            } catch (failure: Exception) {
                val message = (failure as? UserFacingException)?.message ?: "The request failed. Check your connection, account, and files. Any text shown is partial."
                if (assistantId != null) markReply(chat.id, assistantId, partial, MessageStatus.FAILED, message)
                else {
                    if (!userSaved) mutableDrafts.update { it + (chat.id to text) }
                    set { it.copy(error = message) }
                }
            } finally { set { it.copy(generationChatId = null) } }
        }
    }

    private suspend fun markReply(chatId: String, id: String, text: String, status: MessageStatus, message: String) {
        try { repository.updateChat(chatId) { chat -> chat.copy(messages = chat.messages.map {
            if (it.id == id) it.copy(text = text, status = status, error = message) else it
        }) } } catch (_: Exception) { set { it.copy(error = "A partial response could not be saved. Check device storage.") } }
    }
    fun stop() { generation?.cancel(); voice.stop() }
    private suspend fun stopAndWait() { generation?.cancelAndJoin(); voice.stop() }
    fun closeHandoff() = set { it.copy(handoff = null) }
    fun openProvider(provider: Provider, url: String = "") { action { eventChannel.send(UiEvent.ProviderPage(provider, url)) } }
    fun listen() {
        val state = data.value ?: return
        val chatId = state.activeChatId ?: return
        voice.listen(state.preferences) result@ { recognized ->
            val chat = data.value?.chats?.find { it.id == chatId } ?: return@result
            val current = mutableDrafts.value[chatId] ?: chat.draft
            editDraft(chatId, listOf(current.trimEnd(), recognized).filter { it.isNotBlank() }.joinToString(" "))
        }
    }

    fun pasteResponse(text: String) { action {
        val chat = data.value?.activeChat ?: return@action
        if (text.isBlank()) throw UserFacingException("Paste a response first.")
        if (text.length > 120_000) throw UserFacingException("The response is too long to import.")
        val message = Message(role = Role.ASSISTANT, text = text,
            source = "Pasted by you · ${chat.provider.title} · unverified")
        repository.updateChat(chat.id) { it.copy(messages = it.messages + message, updatedAt = System.currentTimeMillis()) }
        if (data.value?.preferences?.autoSpeak == true) voice.speak(text, data.value!!.preferences)
    } }
    fun preferences(change: (Preferences) -> Preferences) { action {
        voice.stop()
        repository.update { it.copy(preferences = change(it.preferences)) }
    } }
    fun chatMemory(memory: String, use: Boolean, global: Boolean) { action {
        repository.updateChat(data.value?.activeChatId ?: return@action) {
            it.copy(memory = memory.take(12_000), useMemory = use, includeGlobalPreferences = global)
        }
    } }
    fun saveBrowserLink(link: String) { action {
        val chat = data.value?.activeChat ?: return@action
        if (link.isNotBlank() && !UrlPolicy.validConversation(chat.provider, link)) {
            throw UserFacingException("Enter a link from ${chat.provider.home} without query parameters or fragments.")
        }
        repository.updateChat(chat.id) { it.copy(browserChatUrl = link.trim()) }
    } }
    fun export(uri: Uri) { action {
        set { it.copy(busy = "Exporting local data…") }
        try {
            persistDrafts()
            val content = repository.export()
            withContext(Dispatchers.IO) {
                checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { it.write(content.toByteArray()) }
            }
            notice("Exported local chats and memory. Sign-in tokens and attachment contents were excluded.")
        } finally { set { it.copy(busy = null) } }
    } }
    fun eraseEverything() { action {
        authentication?.cancelAndJoin(); modelLoading?.cancelAndJoin(); stopAndWait()
        draftJobs.values.forEach { it.cancel() }; draftJobs.clear(); mutableDrafts.value = emptyMap()
        // Local erasure is always available, including when encrypted data cannot be opened.
        repository.erase()
        set { UiState(loading = false) }
        notice("Local data and encryption keys were erased. Provider-side data and grants must be deleted or disconnected in each provider's settings.")
    } }

    fun onForeground() { foreground = true }
    fun onBackground() { foreground = false; voice.stop(); flushDrafts() }
    override fun onCleared() { voice.close(); super.onCleared() }
}
