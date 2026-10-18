package ai.norwix.domain

import ai.norwix.data.ChatRepository
import ai.norwix.data.Vault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private class MemoryVault : Vault {
    val values = mutableMapOf<String, ByteArray>()
    var failWrites = false
    override fun read(name: String) = values[name]
    override fun write(name: String, bytes: ByteArray) {
        if (failWrites) error("Simulated full disk")
        values[name] = bytes.copyOf()
    }
    override fun delete(name: String) { values.remove(name) }
    override fun names() = values.keys.toList()
    override fun erase() { values.clear() }
}

class RepositoryTest {
    @Test fun restartRestoresHistoryMemoryDraftAndSelection() = runBlocking {
        val vault = MemoryVault()
        val first = ChatRepository(vault)
        first.load()
        val id = first.data.value!!.activeChatId!!
        first.updateChat(id) { it.copy(memory = "remember this", draft = "unsent draft",
            messages = listOf(Message(role = Role.USER, text = "hello"))) }
        val restarted = ChatRepository(vault)
        restarted.load()
        assertEquals(id, restarted.data.value!!.activeChatId)
        val chat = restarted.data.value!!.activeChat!!
        assertEquals("remember this", chat.memory)
        assertEquals("unsent draft", chat.draft)
        assertEquals("hello", chat.messages.single().text)
    }

    @Test fun interruptedProcessCannotRestoreStreamingAsSuccessful() = runBlocking {
        val vault = MemoryVault(); val first = ChatRepository(vault); first.load()
        first.updateChat(first.data.value!!.activeChatId!!) { it.copy(messages = listOf(
            Message(role = Role.ASSISTANT, text = "unfinished", status = MessageStatus.STREAMING))) }
        val restarted = ChatRepository(vault); restarted.load()
        val restored = restarted.data.value!!.activeChat!!.messages.single()
        assertEquals(MessageStatus.INTERRUPTED, restored.status)
        assertEquals("unfinished", restored.text)
        assertNotNull(restored.error)
    }

    @Test fun failedAtomicWriteDoesNotPublishUnsavedChanges() = runBlocking {
        val vault = MemoryVault(); val repository = ChatRepository(vault); repository.load()
        val before = repository.data.value!!
        vault.failWrites = true
        try { repository.updateChat(before.activeChatId!!) { it.copy(title = "must-not-appear") }; fail("Expected a storage error") }
        catch (_: IllegalStateException) { }
        assertEquals(before, repository.data.value)
    }

    @Test fun exportCannotContainAnySessionCredentialOrHostIdentifier() = runBlocking {
        val repository = ChatRepository(MemoryVault()); repository.load()
        repository.saveAccount(Account(provider = Provider.CHATGPT, label = "Test account",
            subject = "fixture-subject", clientId = "fixture-client-id", email = "fixture@example.invalid",
            credentials = Credentials(accessToken = "fixture-access-not-real", refreshToken = "fixture-refresh-not-real", idToken = "fixture-id-not-real")))
        val exported = repository.export()
        listOf("fixture-access-not-real", "fixture-refresh-not-real", "fixture-id-not-real", "fixture-subject",
            "fixture-client-id", repository.data.value!!.hostId).forEach { assertFalse(exported.contains(it)) }
        assertTrue(exported.contains("Test account"))
    }

    @Test fun deletingChatDeletesItsAttachmentButKeepsOtherChatFiles() = runBlocking {
        val vault = MemoryVault(); val repository = ChatRepository(vault); repository.load()
        val firstId = repository.data.value!!.activeChatId!!
        val attachment = Attachment(name = "private.pdf", mime = "application/pdf", size = 5)
        vault.write("attachment-${attachment.id}.bin", byteArrayOf(1, 2, 3))
        repository.updateChat(firstId) { it.copy(draftAttachments = listOf(attachment)) }
        val secondId = repository.newChat()
        repository.updateChat(secondId) { it.copy(memory = "keep this memory") }
        repository.deleteChat(firstId)
        assertNull(vault.read("attachment-${attachment.id}.bin"))
        assertEquals("keep this memory", repository.data.value!!.activeChat!!.memory)
    }
}
