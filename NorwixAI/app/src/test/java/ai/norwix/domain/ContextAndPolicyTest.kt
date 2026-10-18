package ai.norwix.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ContextAndPolicyTest {
    @Test fun memoryAndHistoryStayInsideTheirOwnChat() {
        val first = Chat(memory = "first-only-note", messages = listOf(Message(role = Role.USER, text = "first-only-history")))
        val second = Chat(memory = "second-only-note", messages = listOf(Message(role = Role.USER, text = "second-only-history")))
        val preferences = Preferences(globalMemory = "global-private-note")
        val payload = ContextBuilder.handoff(ContextBuilder.build(first, preferences))
        assertTrue(payload.contains("first-only-note"))
        assertTrue(payload.contains("first-only-history"))
        assertFalse(payload.contains(second.memory))
        assertFalse(payload.contains("second-only-history"))
        assertFalse(payload.contains(preferences.globalMemory))
        assertTrue(ContextBuilder.build(first.copy(includeGlobalPreferences = true), preferences)
            .instructions.contains(preferences.globalMemory))
    }

    @Test fun incompleteAssistantRepliesAreNotReusedAsCompletedContext() {
        val chat = Chat(messages = listOf(
            Message(role = Role.USER, text = "question"),
            Message(role = Role.ASSISTANT, text = "partial-untrusted-answer", status = MessageStatus.INTERRUPTED)))
        assertEquals(listOf("question"), ContextBuilder.build(chat, Preferences()).turns.map { it.text })
    }

    @Test fun turningOffChatMemoryDoesNotRemoveHistory() {
        val chat = Chat(memory = "do-not-send-note", useMemory = false,
            messages = listOf(Message(role = Role.USER, text = "keep-history")))
        val payload = ContextBuilder.handoff(ContextBuilder.build(chat, Preferences()))
        assertFalse(payload.contains(chat.memory)); assertTrue(payload.contains("keep-history"))
    }

    @Test fun oversizedContextFailsInsteadOfSilentlyDroppingHistory() {
        val chat = Chat(messages = listOf(Message(role = Role.USER, text = "a".repeat(120_001))))
        assertThrows(IllegalArgumentException::class.java) { ContextBuilder.build(chat, Preferences()) }
    }

    @Test fun providerLinksRejectOriginConfusionAndCredentialParameters() {
        assertTrue(UrlPolicy.validConversation(Provider.CHATGPT, "https://chatgpt.com/c/example"))
        assertTrue(UrlPolicy.validConversation(Provider.GEMINI, "https://gemini.google.com/app/example"))
        assertTrue(UrlPolicy.validConversation(Provider.CLAUDE, "https://claude.ai/chat/example"))
        listOf("http://chatgpt.com/", "https://chatgpt.com.attacker.test/", "https://attacker.test@chatgpt.com/",
            "https://chatgpt.com:8443/", "https://chatgpt.com/?token=value", "https://chatgpt.com/#value",
            "javascript:alert(1)", "https://chatgpt.com\\@attacker.test/").forEach {
            assertFalse("Unsafe URL was accepted: $it", UrlPolicy.validConversation(Provider.CHATGPT, it))
        }
        assertFalse(UrlPolicy.validConversation(Provider.GEMINI, Provider.CLAUDE.home))
    }

    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuthPrimitives.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun duplicateEncodedCallbackKeysAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { OAuthPrimitives.query("state=a&st%61te=b") }
        assertEquals("a b", OAuthPrimitives.query("value=a+b")["value"])
        assertFalse(OAuthPrimitives.same("correct-state", "wrong-state"))
        assertTrue(OAuthPrimitives.secret(64).matches(Regex("[A-Za-z0-9_-]{86}")))
    }

    @Test fun sseHandlesCommentsCrLfAndMultilineData() = runBlocking {
        val events = mutableListOf<String>()
        val input = ": heartbeat\r\ndata: first\r\ndata: second\r\n\r\ndata: third\n\n"
        SseParser.consume(StringReader(input).buffered(), { false }) { events += it }
        assertEquals(listOf("first\nsecond", "third"), events)
    }

    @Test fun streamRequiresExplicitCompletedEvent() {
        val stream = ResponseAccumulator()
        stream.accept("""{"type":"response.output_text.delta","delta":"hello"}""")
        stream.accept("[DONE]")
        assertFalse(stream.completed)
        assertThrows(StreamFailure::class.java) { stream.finish() }
        assertEquals("hello", stream.partial)
    }

    @Test fun usageFailureAfterTextKeepsPartialAndDoesNotComplete() {
        val stream = ResponseAccumulator()
        stream.accept("""{"type":"response.output_text.delta","delta":"partial"}""")
        val failure = assertThrows(StreamFailure::class.java) {
            stream.accept("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""")
        }
        assertEquals("subscription_sharing_usage_limit_exceeded", failure.code)
        assertFalse(stream.completed); assertEquals("partial", stream.partial)
    }

    @Test fun completedRefusalCanBeDisplayedAndFinalTextIsRecovered() {
        val refusal = ResponseAccumulator()
        refusal.accept("""{"type":"response.refusal.delta","delta":"Cannot help with that."}""")
        refusal.accept("""{"type":"response.completed","response":{}}""")
        assertEquals("Cannot help with that.", refusal.finish())
        val final = ResponseAccumulator()
        final.accept("""{"type":"response.completed","response":{"output":[{"content":[{"type":"output_text","text":"Final text"}]}]}}""")
        assertEquals("Final text", final.finish())
    }
}
