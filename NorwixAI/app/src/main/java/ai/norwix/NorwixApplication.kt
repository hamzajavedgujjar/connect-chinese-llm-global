package ai.norwix

import android.app.Application
import ai.norwix.data.*
import ai.norwix.network.*

class NorwixApplication : Application() {
    val repository by lazy { ChatRepository(EncryptedVault(this)) }
    val attachments by lazy { Attachments(contentResolver, repository.vault) }
    private val http by lazy { Http() }
    val auth by lazy { ChatGptAuth(repository, http) }
    val chat by lazy { OpenAiChat(http, auth, attachments) }
}
