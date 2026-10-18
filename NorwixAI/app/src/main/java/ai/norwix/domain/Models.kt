package ai.norwix.domain

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
enum class Provider(val title: String, val home: String) {
    CHATGPT("ChatGPT", "https://chatgpt.com/"),
    GEMINI("Gemini", "https://gemini.google.com/app"),
    CLAUDE("Claude", "https://claude.ai/")
}

@Serializable enum class ThemeMode { MIDNIGHT, PEARL, SYSTEM }
@Serializable enum class Role { USER, ASSISTANT }
@Serializable enum class MessageStatus { COMPLETE, STREAMING, INTERRUPTED, FAILED, LOCAL_ONLY }

@Serializable
data class Attachment(
    val id: String = newId(),
    val name: String,
    val mime: String,
    val size: Long
)

@Serializable
data class Message(
    val id: String = newId(),
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.COMPLETE,
    val source: String = "",
    val error: String? = null,
    val accountId: String? = null,
    val model: String? = null
)

@Serializable
data class Chat(
    val id: String = newId(),
    val title: String = "New conversation",
    val provider: Provider = Provider.CHATGPT,
    val accountId: String? = null,
    val model: String? = null,
    val memory: String = "",
    val useMemory: Boolean = true,
    val includeGlobalPreferences: Boolean = false,
    val draft: String = "",
    val draftAttachments: List<Attachment> = emptyList(),
    val messages: List<Message> = emptyList(),
    val browserChatUrl: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** Never expose or serialize this object into UI diagnostics or user exports. */
@Serializable
data class Credentials(
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAt: Long = 0,
    val scopes: Set<String> = emptySet()
) {
    override fun toString() = "Credentials([REDACTED])"
}

@Serializable
data class Account(
    val id: String = newId(),
    val provider: Provider,
    val label: String,
    val subject: String = "",
    val clientId: String = "",
    val email: String = "",
    val credentials: Credentials? = null
) {
    val nativeChatAvailable: Boolean get() = provider == Provider.CHATGPT &&
        credentials?.accessToken?.isNotEmpty() == true &&
        credentials.scopes.containsAll(setOf("resource.invoke", "chatgpt.tokens.use.direct"))
    override fun toString() = "Account(id=$id, provider=$provider, credentials=[REDACTED])"
}

@Serializable
data class Preferences(
    val theme: ThemeMode = ThemeMode.MIDNIGHT,
    val globalMemory: String = "",
    val voiceName: String = "",
    val speechLocale: String = "en-US",
    val speechRate: Float = 1f,
    val autoSpeak: Boolean = false,
    val onDeviceRecognitionOnly: Boolean = true,
    val offlineVoicesOnly: Boolean = true,
    val reduceMotion: Boolean = false,
    val protectScreen: Boolean = true
)

@Serializable
data class AppData(
    val version: Int = 1,
    val hostId: String = "urn:uuid:${newId()}",
    val accounts: List<Account> = emptyList(),
    val chats: List<Chat> = emptyList(),
    val activeChatId: String? = null,
    val preferences: Preferences = Preferences()
) {
    val activeChat: Chat? get() = chats.find { it.id == activeChatId }
}

data class AvailableModel(val slug: String, val displayName: String)

data class ContextTurn(val role: Role, val text: String, val attachments: List<Attachment>)
data class ConversationContext(val instructions: String, val turns: List<ContextTurn>)

/** Only this conversation participates. Never accept AppData or another chat as context. */
object ContextBuilder {
    const val MAX_TEXT_CHARS = 120_000
    fun build(chat: Chat, preferences: Preferences): ConversationContext {
        val instructions = buildString {
            append("You are Norwix AI, a helpful assistant. Follow the user's language and instructions.")
            if (chat.includeGlobalPreferences && preferences.globalMemory.isNotBlank()) {
                append("\n\nUser's optional global preferences:\n${preferences.globalMemory}")
            }
            if (chat.useMemory && chat.memory.isNotBlank()) {
                append("\n\nMemory for this conversation only:\n${chat.memory}")
            }
        }
        val turns = chat.messages.filter {
            it.role == Role.USER || it.status == MessageStatus.COMPLETE
        }.map { ContextTurn(it.role, it.text, it.attachments) }
        require(instructions.length + turns.sumOf { it.text.length } <= MAX_TEXT_CHARS) {
            "This chat exceeds the local context limit. Start a new chat and copy a summary into its memory."
        }
        return ConversationContext(instructions, turns)
    }

    fun handoff(context: ConversationContext): String = buildString {
        append(context.instructions)
        for (turn in context.turns) {
            append("\n\n${if (turn.role == Role.USER) "User" else "Assistant"}:\n${turn.text}")
            if (turn.attachments.isNotEmpty()) {
                append("\n[Local attachments: ${turn.attachments.joinToString { it.name }}. Attach these separately in the provider.]")
            }
        }
    }
}
