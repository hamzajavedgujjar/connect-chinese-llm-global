package ai.norwix.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import ai.norwix.domain.*
import ai.norwix.platform.ProviderBrowser
import ai.norwix.platform.VoiceState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

enum class Page(val title: String, val icon: ImageVector) {
    CHAT("Conversation", Icons.Rounded.ChatBubbleOutline),
    CHATS("Your conversations", Icons.Rounded.Forum),
    ACCOUNTS("Login Accounts", Icons.Rounded.AccountCircle),
    MODELS("Models & provider", Icons.Rounded.Tune),
    THEMES("Appearance", Icons.Rounded.Palette),
    VOICE("Voice", Icons.Rounded.GraphicEq),
    MEMORY("Memory", Icons.Rounded.Psychology),
    PRIVACY("Privacy & data", Icons.Rounded.Shield),
    ABOUT("About Norwix", Icons.Rounded.Info)
}

fun sensitiveCopy(context: Context, text: String) {
    val clip = ClipData.newPlainText("Norwix AI text", text)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    requireNotNull(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(clip)
}

@Composable
fun NorwixApp(vm: NorwixViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val voice by vm.voice.status.collectAsStateWithLifecycle()
    val draftOverrides by vm.drafts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycle = LocalLifecycleOwner.current
    var page by rememberSaveable { mutableStateOf(Page.CHAT) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var erase by remember { mutableStateOf(false) }
    var exportConfirm by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.attach(it) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.listen() else vm.voice.notice("Microphone permission was denied. You can keep typing or enable it in Android app settings.")
    }
    val protect = data?.preferences?.protectScreen ?: true
    SideEffect {
        if (protect) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    LaunchedEffect(vm, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                try {
                    when (event) {
                        is UiEvent.AuthBrowser -> ProviderBrowser.openAuth(activity, event.url)
                        is UiEvent.ProviderPage -> ProviderBrowser.openProvider(activity, event.provider, event.link)
                        is UiEvent.Notice -> snack.showSnackbar(event.text, duration = SnackbarDuration.Long, withDismissAction = true)
                        UiEvent.Accounts -> page = Page.ACCOUNTS
                    }
                } catch (failure: Exception) { vm.cancelSignIn(); vm.report(failure) }
            }
        }
    }
    BackHandler(drawer.isOpen || page != Page.CHAT) {
        if (drawer.isOpen) scope.launch { drawer.close() } else page = Page.CHAT
    }
    NorwixTheme(data?.preferences?.theme ?: ThemeMode.MIDNIGHT) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                AmbientBackground(Modifier.fillMaxSize())
                ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.widthIn(max = 340.dp), drawerContainerColor = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                BrandMark()
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text("NORWIX", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                                    Text("A space to think", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { vm.newChat(); page = Page.CHAT; scope.launch { drawer.close() } },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(10.dp)); Text("New conversation")
                            }
                            Spacer(Modifier.height(16.dp))
                            Page.entries.filter { it != Page.CHAT }.forEach { destination ->
                                if (destination == Page.ACCOUNTS || destination == Page.ABOUT) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                                NavigationDrawerItem(label = { Text(destination.title) }, selected = page == destination,
                                    icon = { Icon(destination.icon, null) },
                                    onClick = { page = destination; scope.launch { drawer.close() } })
                            }
                            Spacer(Modifier.height(22.dp))
                            Text("OPEN-SOURCE PREVIEW  /  0.1", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                        }
                    }
                }) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        modifier = Modifier.safeDrawingPadding(),
                        snackbarHost = { SnackbarHost(snack) },
                        topBar = {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, "Open navigation menu") }
                                if (page != Page.CHAT) IconButton(onClick = { page = Page.CHAT }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to conversation") }
                                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                    Text(if (page == Page.CHAT) "Norwix AI" else page.title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                                    if (page == Page.CHAT) Text(data?.activeChat?.title ?: "Your personal workspace",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (page == Page.CHAT) IconButton(onClick = vm::newChat, enabled = data != null) {
                                    Icon(Icons.Rounded.Add, "New conversation")
                                }
                            }
                        }
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                            val state = data
                            when {
                                ui.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("Opening your protected workspace…")
                                }
                                ui.loadFailed || state == null -> GlassPanel(Modifier.padding(24.dp).widthIn(max = 560.dp)) {
                                    Text("Protected data could not be opened", style = MaterialTheme.typography.titleLarge)
                                    Text("Your existing files were kept. The device encryption key or storage may be unavailable. Retry, or erase local data to start again.")
                                    Button(onClick = vm::load) { Text("Retry") }
                                    TextButton(onClick = { erase = true }) { Text("Erase local data") }
                                }
                                else -> {
                                    when (page) {
                                        Page.CHAT -> ChatScreen(state, ui, voice, draftOverrides, vm,
                                            onAccounts = { page = Page.ACCOUNTS }, onMemory = { page = Page.MEMORY },
                                            onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
                                            onImage = { attachmentPicker.launch(arrayOf("image/*")) },
                                            onMic = { if (voice.recognizing) vm.voice.finishListening() else microphone.launch(Manifest.permission.RECORD_AUDIO) },
                                            onPaste = { paste = true })
                                        Page.CHATS -> ChatsScreen(state, vm) { page = Page.CHAT }
                                        Page.ACCOUNTS -> AccountsScreen(state, ui, vm)
                                        Page.MODELS -> ModelsScreen(state, ui, vm) { page = Page.ACCOUNTS }
                                        Page.THEMES -> AppearanceScreen(state.preferences, vm)
                                        Page.VOICE -> VoiceScreen(state.preferences, voice, vm)
                                        Page.MEMORY -> MemoryScreen(state, vm)
                                        Page.PRIVACY -> PrivacyScreen(state.preferences, vm, { exportConfirm = true }, { erase = true })
                                        Page.ABOUT -> AboutScreen()
                                    }
                                }
                            }
                        }
                    }
                }
                if (ui.busy != null) androidx.compose.ui.window.Dialog(onDismissRequest = {},
                    properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
                    GlassPanel { CircularProgressIndicator(); Text(ui.busy!!) }
                }
            }
        }
        if (ui.error != null) AlertDialog(onDismissRequest = vm::clearError,
            title = { Text("Couldn’t complete that") }, text = { Text(ui.error!!) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } })
        if (erase) ConfirmDialog("Erase all local data?",
            "This permanently removes local chats, memory, attachments, account tokens, and encryption keys. Provider-side history and permissions remain in each provider's settings.",
            "Erase local data", { erase = false; vm.eraseEverything(); page = Page.CHAT }, { erase = false })
        if (exportConfirm) ConfirmDialog("Export local data?",
            "The JSON export contains plaintext chats, memory, preferences, and attachment names. Anyone with the file can read it. Sign-in tokens and attachment contents are excluded.",
            "Choose location", { exportConfirm = false; export.launch("norwix-local-data.json") }, { exportConfirm = false })
        if (paste) EditDialog("Add an external response", "Paste text you copied from the official provider. It will be labelled as unverified.", "", true,
            onSave = { vm.pasteResponse(it); paste = false }, onDismiss = { paste = false })
        ui.handoff?.let { handoff ->
            AlertDialog(onDismissRequest = vm::closeHandoff, title = { Text("Continue in ${handoff.provider.title}") },
                text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Review this text before copying it. Paste it into the official provider's composer, attach files there, and press its Send button. Norwix cannot auto-send, read replies, or verify the website's active account.")
                    Text(handoff.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                confirmButton = { TextButton(onClick = {
                    sensitiveCopy(context, handoff.text)
                    val chat = data?.chats?.find { it.id == handoff.chatId }
                    vm.openProvider(handoff.provider, chat?.browserChatUrl.orEmpty()); vm.closeHandoff()
                }) { Text("Copy & open ${handoff.provider.title}") } },
                dismissButton = { TextButton(onClick = vm::closeHandoff) { Text("Keep locally") } })
        }
    }
}

@Composable
fun BrandMark() {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(15.dp))
        .background(Brush.linearGradient(listOf(Color(0xFFD4C5FF), Color(0xFFA8DCD8)))), contentAlignment = Alignment.Center) {
        Text("N", color = Color(0xFF25213F), fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
    }
}

@Composable
fun SettingsBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 820.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
}

@Composable
fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun EditDialog(title: String, help: String, initial: String, multiline: Boolean = false,
    onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (help.isNotBlank()) Text(help, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, label = { Text(title) },
                singleLine = !multiline, maxLines = if (multiline) 12 else 1,
                modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp))
        }
    }, confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun ChatScreen(state: AppData, ui: UiState, voice: VoiceState, drafts: Map<String, String>, vm: NorwixViewModel,
    onAccounts: () -> Unit, onMemory: () -> Unit, onAttach: () -> Unit, onImage: () -> Unit,
    onMic: () -> Unit, onPaste: () -> Unit) {
    val chat = state.activeChat ?: return
    val account = state.accounts.find { it.id == chat.accountId }
    val native = chat.provider == Provider.CHATGPT
    val listState = rememberLazyListState()
    LaunchedEffect(chat.id, chat.messages.size) {
        if (chat.messages.isNotEmpty()) listState.scrollToItem(chat.messages.lastIndex)
    }
    Column(Modifier.fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth().imePadding()) {
        if (chat.messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("YOUR NEXT IDEA STARTS HERE", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                NorwixOrb(state.preferences.reduceMotion)
                Spacer(Modifier.height(14.dp))
                Text("What’s on your mind?", fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(if (native && account?.nativeChatAvailable == true) "${account.label}\n${ui.models.find { it.slug == chat.model }?.displayName ?: "Choose a model from the menu"}"
                    else if (native) "Connect ChatGPT to chat here.\nHistory is stored on this device."
                    else "Draft here. Continue securely in ${chat.provider.title}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium)
                if (native && account?.nativeChatAvailable != true) TextButton(onClick = onAccounts) { Text("Login Accounts") }
                Spacer(Modifier.height(18.dp))
                Suggestion("Make a plan", "Turn a goal into clear next steps", Icons.Rounded.AutoAwesome) {
                    vm.editDraft(chat.id, "Help me turn this goal into a practical plan: ")
                }
                Spacer(Modifier.height(10.dp))
                Suggestion("Think it through", "Explore a question from a fresh angle", Icons.Rounded.Lightbulb) {
                    vm.editDraft(chat.id, "Help me think through this question carefully: ")
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
                Text("${chat.provider.title} · ${if (native) chat.model ?: "Model not selected" else "Official browser"}",
                    Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onMemory) { Text("Memory", style = MaterialTheme.typography.labelSmall) }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(chat.messages, key = { it.id }) { message -> MessageBubble(message,
                    onSpeak = { vm.voice.speak(message.text, state.preferences) },
                    onDelete = { vm.deleteMessage(chat.id, message.id) }) }
            }
        }
        voice.notice?.let { message ->
            NoticeRow(message) { vm.voice.notice(null) }
        }
        if (voice.recognizing) NoticeRow(if (voice.partial.isNotBlank()) voice.partial else "Listening… Tap the microphone again to finish.") { vm.voice.stop() }
        if (ui.modelsLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp))
        if (chat.draftAttachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chat.draftAttachments.forEach { file -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name, maxLines = 1, modifier = Modifier.widthIn(max = 170.dp), overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { vm.removeAttachment(chat.id, file.id) }) { Icon(Icons.Rounded.Close, "Remove ${file.name}", Modifier.size(16.dp)) }
                }
            } }
        }
        val composerShape = RoundedCornerShape(27.dp)
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, composerShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .45f), composerShape).padding(8.dp)) {
            TextField(value = drafts[chat.id] ?: chat.draft, onValueChange = { vm.editDraft(chat.id, it) },
                placeholder = { Text(if (native) "Ask anything…" else "Draft for ${chat.provider.title}…") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp, max = 158.dp).semantics { contentDescription = "Message composer" },
                minLines = 1, maxLines = 5,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach) { Icon(Icons.Rounded.AttachFile, "Attach a file") }
                IconButton(onClick = onImage) { Icon(Icons.Rounded.Image, "Attach an image") }
                IconButton(onClick = onMic) { Icon(if (voice.recognizing) Icons.Rounded.StopCircle else Icons.Rounded.Mic, if (voice.recognizing) "Finish voice input" else "Start voice input",
                    tint = if (voice.recognizing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.weight(1f))
                if (voice.speaking) IconButton(onClick = vm.voice::stop) { Icon(Icons.Rounded.VolumeOff, "Stop speech playback") }
                if (ui.generationChatId != null) FilledIconButton(onClick = vm::stop, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Stop, "Stop generating response") }
                else FilledIconButton(onClick = vm::send,
                    enabled = (drafts[chat.id] ?: chat.draft).isNotBlank() || chat.draftAttachments.isNotEmpty(),
                    modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.Send, if (native) "Send message" else "Review provider handoff") }
            }
        }
        if (!native) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Replies stay with the provider until you paste them here.", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onPaste) { Text("Add response", style = MaterialTheme.typography.labelSmall) }
        }
        else Text("AI can make mistakes. Your local chats are stored encrypted.",
            Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 20.dp, end = 20.dp),
            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Suggestion(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.widthIn(max = 390.dp).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .8f), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .2f))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun NoticeRow(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss notice", Modifier.size(18.dp)) }
    }
}

@Composable
fun MessageBubble(message: Message, onSpeak: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var delete by remember { mutableStateOf(false) }
    val isUser = message.role == Role.USER
    Column(Modifier.fillMaxWidth().padding(start = if (isUser) 28.dp else 0.dp, end = if (isUser) 0.dp else 10.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Text(if (isUser) "You" else message.source, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
        Surface(color = if (isUser) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .8f) else MaterialTheme.colorScheme.surface.copy(alpha = .7f),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (isUser) 22.dp else 5.dp, bottomEnd = if (isUser) 5.dp else 22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message.text.isNotBlank()) MarkdownText(message.text)
                message.attachments.forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, null, Modifier.size(18.dp))
                        Text("${file.name} · ${file.size / 1024} KB", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (message.status == MessageStatus.STREAMING) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Receiving…", Modifier.padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelSmall)
                }
                if (message.status == MessageStatus.LOCAL_ONLY) Text(message.source, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                message.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row {
            if (message.text.isNotEmpty()) IconButton(onClick = { sensitiveCopy(context, message.text) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.ContentCopy, "Copy message", Modifier.size(17.dp)) }
            if (!isUser && message.text.isNotEmpty()) IconButton(onClick = onSpeak, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.VolumeUp, "Read message aloud", Modifier.size(19.dp)) }
            IconButton(onClick = { delete = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.DeleteOutline, "Delete message", Modifier.size(18.dp)) }
        }
    }
    if (delete) ConfirmDialog("Delete this message?", "Its text and any local attachments will be removed from this conversation.", "Delete",
        { delete = false; onDelete() }, { delete = false })
}

@Composable
fun ChatsScreen(state: AppData, vm: NorwixViewModel, onOpen: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var rename by remember { mutableStateOf<Chat?>(null) }
    var delete by remember { mutableStateOf<Chat?>(null) }
    val results = state.chats.filter { chat ->
        chat.title.contains(query, true) || chat.messages.any { it.text.contains(query, true) }
    }.sortedByDescending { it.updatedAt }
    Column(Modifier.widthIn(max = 820.dp).fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("Search titles and messages") }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        if (results.isEmpty()) Text("No matching conversations.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(results, key = { it.id }) { chat ->
                Surface(onClick = { vm.selectChat(chat.id); onOpen() }, shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .2f))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(chat.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${chat.provider.title} · ${chat.messages.size} messages · ${DateFormat.getDateInstance().format(Date(chat.updatedAt))}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(chat.messages.lastOrNull()?.text.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        Row {
                            TextButton(onClick = { rename = chat }) { Text("Rename") }
                            TextButton(onClick = { delete = chat }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    rename?.let { chat -> EditDialog("Rename conversation", "", chat.title, onSave = { vm.renameChat(chat.id, it); rename = null }, onDismiss = { rename = null }) }
    delete?.let { chat -> ConfirmDialog("Delete conversation?", "${chat.title} and its local memory and attachments will be removed. Provider-side conversations are managed on the provider's website.", "Delete",
        { vm.deleteChat(chat.id); delete = null }, { delete = null }) }
}
