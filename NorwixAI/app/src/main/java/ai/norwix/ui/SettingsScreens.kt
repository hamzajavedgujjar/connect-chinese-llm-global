package ai.norwix.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.norwix.domain.*
import ai.norwix.platform.VoiceState

@Composable
fun AccountsScreen(state: AppData, ui: UiState, vm: NorwixViewModel) {
    var rename by remember { mutableStateOf<Account?>(null) }
    var forget by remember { mutableStateOf<Account?>(null) }
    SettingsBody {
        Text("One place for your AI accounts.", style = MaterialTheme.typography.headlineSmall)
        Text("Only official sign-in pages handle your password. Native ChatGPT connections are kept separately; browser sessions remain under the provider and browser's control.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassPanel(Modifier.fillMaxWidth()) {
            Text("ChatGPT", style = MaterialTheme.typography.titleLarge)
            Text("Open-source plan-usage preview", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
            Text("Continue with ChatGPT opens OpenAI's official OAuth page. Eligible users can optionally grant access to included plan usage. Your existing ChatGPT chats and memories are not imported. No API key or API billing setup is used.", style = MaterialTheme.typography.bodyMedium)
            Text("This implementation has not been verified with a live account on Android. Availability depends on OpenAI eligibility and its preview restrictions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.signIn() }, enabled = !ui.signingIn,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Continue with ChatGPT")
            }
            if (ui.signingIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Complete sign-in in the secure browser, then close the tab.", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = vm::cancelSignIn) { Text("Cancel sign-in") }
            }
            TextButton(onClick = { vm.openProvider(Provider.CHATGPT) }) { Text("Open official ChatGPT website") }
        }
        state.accounts.filter { it.provider == Provider.CHATGPT }.forEach { account ->
            GlassPanel(Modifier.fillMaxWidth()) {
                Text(account.label, style = MaterialTheme.typography.titleMedium)
                Text(when {
                    account.credentials == null -> "Signed out · registration retained"
                    account.nativeChatAvailable -> "Identity verified · plan usage granted"
                    else -> "Identity verified · plan usage disabled"
                }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                if (state.activeChat?.accountId == account.id) Text("Selected for this conversation", style = MaterialTheme.typography.labelSmall)
                Text("Selecting an account applies to this conversation. Its local history is included when you next send a message.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { vm.chooseAccount(account.id) }, enabled = account.credentials != null) { Text("Use for this conversation") }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.signIn(account.id) }, enabled = !ui.signingIn) { Text("Reconnect") }
                    TextButton(onClick = { rename = account }) { Text("Rename") }
                    if (account.credentials != null) TextButton(onClick = { vm.signOut(account.id) }) { Text("Sign out") }
                    TextButton(onClick = { forget = account }) { Text("Forget") }
                }
            }
        }
        Provider.entries.filter { it != Provider.CHATGPT }.forEach { provider ->
            GlassPanel(Modifier.fillMaxWidth()) {
                Text(if (provider == Provider.GEMINI) "Google Gemini / Google AI Pro" else "Claude / Claude Pro",
                    style = MaterialTheme.typography.titleMedium)
                Text("Official browser access", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                Text(if (provider == Provider.GEMINI)
                    "A Google AI Pro subscription is used in Gemini Apps. Google's documented developer OAuth route requires a Cloud project and is not subscription access. Norwix opens Gemini's own interface instead."
                    else "Anthropic does not permit third-party applications to offer Claude.ai login or route inference through Pro subscription credentials. Norwix opens Claude's own interface instead.")
                Text("Switch accounts and models on the official page. Norwix cannot verify the active browser account or create isolated sessions for each account. Responses, attachments, and provider history are handled on that page.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { vm.openProvider(provider) }) { Text("Open ${provider.title}") }
                TextButton(onClick = { vm.chooseProvider(provider); vm.notice("${provider.title} browser mode selected for this conversation.") }) { Text("Use browser mode in this conversation") }
            }
        }
    }
    rename?.let { account -> EditDialog("Account label", "This changes only its local display label.", account.label,
        onSave = { vm.renameAccount(account.id, it); rename = null }, onDismiss = { rename = null }) }
    forget?.let { account -> ConfirmDialog("Forget this connection?", "Norwix will attempt to revoke its renewable session, then remove this local registration. Existing local chats remain.", "Forget",
        { vm.forgetAccount(account.id); forget = null }, { forget = null }) }
}

@Composable
fun ModelsScreen(state: AppData, ui: UiState, vm: NorwixViewModel, onAccounts: () -> Unit) {
    val chat = state.activeChat ?: return
    val account = state.accounts.find { it.id == chat.accountId }
    SettingsBody {
        Text("Choose where you think.", style = MaterialTheme.typography.headlineSmall)
        Text("Changes apply to this conversation and stop any response currently being generated.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Provider.entries.forEach { provider -> FilterChip(selected = chat.provider == provider,
                onClick = { vm.chooseProvider(provider) }, label = { Text(provider.title) }) }
        }
        if (chat.provider == Provider.CHATGPT) {
            GlassPanel(Modifier.fillMaxWidth()) {
                Text("${account?.label ?: "No account selected"}", style = MaterialTheme.typography.titleMedium)
                Text("The model list comes from the selected account's official model catalog. No model names or access entitlements are assumed.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onAccounts) { Text("Choose or connect an account") }
                OutlinedButton(onClick = vm::refreshModels, enabled = account?.nativeChatAvailable == true && !ui.modelsLoading) { Text("Refresh available models") }
                if (ui.modelsLoading) CircularProgressIndicator(Modifier.size(24.dp))
                ui.models.forEach { model ->
                    Row(Modifier.fillMaxWidth().selectable(selected = chat.model == model.slug,
                        role = SemanticsRole.RadioButton, onClick = { vm.chooseModel(model.slug) }).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chat.model == model.slug, onClick = null)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(model.slug, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (ui.models.isEmpty() && !ui.modelsLoading) Text("No available model catalog has been loaded.", style = MaterialTheme.typography.bodySmall)
            }
        } else GlassPanel(Modifier.fillMaxWidth()) {
            Text("${chat.provider.title} controls its model picker", style = MaterialTheme.typography.titleMedium)
            Text("Open its official page and select an available model there. Website responses are not streamed into Norwix.")
            Button(onClick = { vm.openProvider(chat.provider, chat.browserChatUrl) }) { Text("Open ${chat.provider.title}") }
        }
    }
}

@Composable
fun AppearanceScreen(preferences: Preferences, vm: NorwixViewModel) {
    SettingsBody {
        Text("Quiet, by design.", style = MaterialTheme.typography.headlineSmall)
        GlassPanel(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().selectable(preferences.theme == mode,
                    role = SemanticsRole.RadioButton, onClick = { vm.preferences { it.copy(theme = mode) } }).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(preferences.theme == mode, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(when (mode) { ThemeMode.MIDNIGHT -> "Midnight"; ThemeMode.PEARL -> "Pearl"; ThemeMode.SYSTEM -> "Follow Android" }, fontWeight = FontWeight.Medium)
                        Text(when (mode) { ThemeMode.MIDNIGHT -> "Deep ink, lilac, and soft mint"; ThemeMode.PEARL -> "Soft porcelain and sculpted surfaces"; ThemeMode.SYSTEM -> "Match your system's light or dark mode" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SettingToggle("Reduce motion", "Stops the ambient orb animation.", preferences.reduceMotion) { enabled -> vm.preferences { it.copy(reduceMotion = enabled) } }
        }
        NorwixOrb(preferences.reduceMotion, Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun VoiceScreen(preferences: Preferences, state: VoiceState, vm: NorwixViewModel) {
    val context = LocalContext.current
    var language by rememberSaveable { mutableStateOf(preferences.speechLocale) }
    var voicesOpen by remember { mutableStateOf(false) }
    var rate by rememberSaveable(preferences.speechRate) { mutableFloatStateOf(preferences.speechRate) }
    val voices = state.voices.filter { !preferences.offlineVoicesOnly || !it.needsNetwork }
    SettingsBody {
        Text("Give your ideas a voice.", style = MaterialTheme.typography.headlineSmall)
        GlassPanel(Modifier.fillMaxWidth()) {
            SettingToggle("On-device recognition only", "Requires Android 12+ and a supported installed recognizer. No automatic cloud fallback.", preferences.onDeviceRecognitionOnly) {
                enabled -> vm.preferences { it.copy(onDeviceRecognitionOnly = enabled) }
            }
            if (!preferences.onDeviceRecognitionOnly) Text("The Android speech service may send microphone audio to its own servers. Its provider and privacy policy apply.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(language, { language = it.take(30) }, label = { Text("Recognition / voice language tag") },
                supportingText = { Text("For example en-US or ur-PK. Availability depends on installed language packs.") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = {
                if (language.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*"))) vm.preferences { it.copy(speechLocale = language) }
                else vm.notice("Enter a language tag such as en-US or ur-PK.")
            }) { Text("Save language") }
        }
        GlassPanel(Modifier.fillMaxWidth()) {
            Text("Playback", style = MaterialTheme.typography.titleMedium)
            SettingToggle("Offline voices only", "Hides voices that require a network connection.", preferences.offlineVoicesOnly) {
                enabled -> vm.preferences { it.copy(offlineVoicesOnly = enabled, voiceName = "") }
            }
            Box {
                OutlinedButton(onClick = { voicesOpen = true }, enabled = voices.isNotEmpty()) {
                    Text(voices.find { it.name == preferences.voiceName }?.label ?: if (voices.isEmpty()) "No supported voice installed" else "Choose an installed voice", maxLines = 3)
                }
                DropdownMenu(expanded = voicesOpen, onDismissRequest = { voicesOpen = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    voices.forEach { voice -> DropdownMenuItem(text = { Text("${voice.label}${if (voice.needsNetwork) " · network" else " · offline"}") },
                        onClick = { voicesOpen = false; vm.preferences { it.copy(voiceName = voice.name) } }) }
                }
            }
            Text("Speed · ${"%.2f".format(rate)}×", style = MaterialTheme.typography.labelMedium)
            Slider(value = rate, onValueChange = { rate = it }, valueRange = .5f..1.5f,
                onValueChangeFinished = { vm.preferences { it.copy(speechRate = rate) } })
            SettingToggle("Speak completed replies", "Only the currently open conversation is spoken. Partial replies require an explicit playback tap.", preferences.autoSpeak) {
                enabled -> vm.preferences { it.copy(autoSpeak = enabled) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.voice.speak("Welcome to Norwix AI. This is a preview of your selected voice.", preferences) }, enabled = state.ready) { Text("Preview voice") }
                OutlinedButton(onClick = vm.voice::stop) { Text("Stop") }
            }
            TextButton(onClick = {
                try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: Exception) { vm.notice("Open Android Settings and search for Text-to-speech output.") }
            }) { Text("Android speech settings") }
        }
        state.notice?.let { NoticeRow(it) { vm.voice.notice(null) } }
        Text("Voice input writes a draft; it never sends automatically. Playback stops when you leave the app, start dictation, or lose audio focus.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MemoryScreen(state: AppData, vm: NorwixViewModel) {
    val chat = state.activeChat ?: return
    var memory by rememberSaveable(chat.id) { mutableStateOf(chat.memory) }
    var useMemory by rememberSaveable(chat.id) { mutableStateOf(chat.useMemory) }
    var useGlobal by rememberSaveable(chat.id) { mutableStateOf(chat.includeGlobalPreferences) }
    var global by rememberSaveable { mutableStateOf(state.preferences.globalMemory) }
    var link by rememberSaveable(chat.id) { mutableStateOf(chat.browserChatUrl) }
    var context by remember { mutableStateOf<String?>(null) }
    SettingsBody {
        Text("Memory you can see.", style = MaterialTheme.typography.headlineSmall)
        GlassPanel(Modifier.fillMaxWidth()) {
            Text(chat.title, style = MaterialTheme.typography.titleMedium)
            Text("History and memory are scoped to this conversation. These notes are explicit; Norwix does not silently extract a profile from your chats.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(memory, { memory = it.take(12_000) }, label = { Text("This conversation's memory") },
                modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10)
            SettingToggle("Use chat memory", "Include these notes in future requests for this conversation.", useMemory) { useMemory = it }
            SettingToggle("Include global preferences", "Opt in for this conversation only.", useGlobal) { useGlobal = it }
            Button(onClick = { vm.chatMemory(memory, useMemory, useGlobal); vm.notice("Conversation memory saved.") }) { Text("Save chat memory") }
            TextButton(onClick = { memory = ""; vm.chatMemory("", useMemory, useGlobal) }) { Text("Delete chat memory") }
            TextButton(onClick = {
                try { context = ContextBuilder.handoff(ContextBuilder.build(chat, state.preferences)) }
                catch (_: Exception) { vm.notice("This chat exceeds the context limit. Start a new chat with a summary.") }
            }) { Text("View stored chat context") }
        }
        GlassPanel(Modifier.fillMaxWidth()) {
            Text("Optional global preferences", style = MaterialTheme.typography.titleMedium)
            Text("Only conversations with the opt-in enabled include these notes. They are separate from ChatGPT, Gemini, and Claude's own memory features.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(global, { global = it.take(12_000) }, label = { Text("Preferences shared across opted-in chats") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
            Button(onClick = { vm.preferences { it.copy(globalMemory = global) }; vm.notice("Global preferences saved.") }) { Text("Save global preferences") }
            TextButton(onClick = { global = ""; vm.preferences { it.copy(globalMemory = "") } }) { Text("Delete global preferences") }
        }
        if (chat.provider != Provider.CHATGPT) GlassPanel(Modifier.fillMaxWidth()) {
            Text("Resume an official conversation", style = MaterialTheme.typography.titleMedium)
            Text("Optionally save a conversation link from ${chat.provider.home}. Norwix stores the link only; it cannot read or sync its contents.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(link, { link = it }, label = { Text("Official conversation URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { vm.saveBrowserLink(link) }) { Text("Save link") }
            TextButton(onClick = { vm.openProvider(chat.provider, chat.browserChatUrl) }) { Text("Open saved conversation") }
        }
    }
    context?.let { text -> AlertDialog(onDismissRequest = { context = null }, title = { Text("Stored chat context") },
        text = { androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("Unsaved memory edits and composer drafts are excluded from this view. Saved memory and history below are used on the next request.", style = MaterialTheme.typography.labelMedium)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        } }, confirmButton = { TextButton(onClick = { context = null }) { Text("Close") } }) }
}

@Composable
fun PrivacyScreen(preferences: Preferences, vm: NorwixViewModel, onExport: () -> Unit, onErase: () -> Unit) {
    SettingsBody {
        Text("Your workspace. Your control.", style = MaterialTheme.typography.headlineSmall)
        GlassPanel(Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.secondary)
            Text("Encrypted on this device", style = MaterialTheme.typography.titleMedium)
            Text("Histories, drafts, memory, account tokens, and copied attachments use AES-256-GCM with an Android Keystore key. Android backup and device transfer are excluded. There is no analytics SDK or Norwix server.")
            Text("Native requests go directly to OpenAI over HTTPS with the selected OAuth session. Each includes this conversation's history and enabled memory. Attachments are sent only after you tap Send.", style = MaterialTheme.typography.bodySmall)
            SettingToggle("Protect screen contents", "Blocks screenshots and screen recording on supported Android devices.", preferences.protectScreen) {
                enabled -> vm.preferences { it.copy(protectScreen = enabled) }
            }
        }
        GlassPanel(Modifier.fillMaxWidth()) {
            Text("Provider controls", style = MaterialTheme.typography.titleMedium)
            Text("ChatGPT plan usage is shared with other connected apps and remains subject to account limits. Norwix never switches to API billing. Review plan usage, credit permissions, and connections inside ChatGPT Settings.")
            TextButton(onClick = { vm.openProvider(Provider.CHATGPT) }) { Text("Open ChatGPT settings on its website") }
            Text("Browser cookies, provider histories, model access, and website permissions belong to the browser and provider. Removing local data does not sign out of their websites or delete provider-side data.", style = MaterialTheme.typography.bodySmall)
        }
        GlassPanel(Modifier.fillMaxWidth()) {
            Text("Manage your local data", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onExport) { Text("Export chats & memory") }
            OutlinedButton(onClick = onErase, border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
                Text("Erase all local data", color = MaterialTheme.colorScheme.error)
            }
            Text("Exports and text deliberately copied to the clipboard are plaintext. The original file selected for an attachment remains in its original location when its Norwix copy is deleted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AboutScreen() {
    SettingsBody {
        BrandMark()
        Text("Norwix AI", style = MaterialTheme.typography.headlineLarge)
        Text("0.1.0 · MIT-licensed source preview", color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassPanel(Modifier.fillMaxWidth()) {
            Text("Verification status", style = MaterialTheme.typography.titleMedium)
            Text("The source implements official ChatGPT OSS OAuth and Responses streaming. Compilation, Android installation, live OAuth, model access, microphone, and speaker tests have not been completed in the authoring environment.")
            Text("Gemini and Claude open their official websites using Android Custom Tabs or the installed browser. Their subscription accounts do not provide a verified native chat integration here.")
            Text("This app is independent and is not endorsed by OpenAI, Google, or Anthropic. All provider features, eligibility, and policies can change.", style = MaterialTheme.typography.bodySmall)
        }
        GlassPanel(Modifier.fillMaxWidth()) {
            Text("Source & review", style = MaterialTheme.typography.titleMedium)
            Text("The source package includes setup instructions, architecture, provider documentation links, unit and device test code, CI configuration, and an explicit verification matrix. Read them before distributing a build.")
        }
    }
}
