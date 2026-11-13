package com.raunakpatil.roniepocket

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import com.raunakpatil.roniepocket.vision.ImageImport
import java.io.File
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raunakpatil.roniepocket.ui.Chip
import com.raunakpatil.roniepocket.ui.EyebrowText
import com.raunakpatil.roniepocket.ui.Ic
import com.raunakpatil.roniepocket.ui.Mood
import com.raunakpatil.roniepocket.ui.PillButton
import com.raunakpatil.roniepocket.ui.R
import com.raunakpatil.roniepocket.ui.RoundButton
import com.raunakpatil.roniepocket.ui.StatusPill
import com.raunakpatil.roniepocket.ui.Tile
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel(), assistRequest: Int = 0, shared: SharedItem? = null, onSharedUsed: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var attachedImages by remember { mutableStateOf<List<File>>(emptyList()) }
    var fullScreenImage by remember { mutableStateOf<String?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }

    // Photo picker (up to 2 photos per message: that's how many Ronie keeps in view at once)
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PHOTOS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val files = uris.take(MAX_PHOTOS).mapNotNull { ImageImport.importFromUri(context, it) }
                attachedImages = (attachedImages + files).distinctBy { it.absolutePath }.takeLast(MAX_PHOTOS)
            }
        }
    }

    // Camera launcher & permission
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraUri != null) {
            scope.launch {
                val file = ImageImport.importFromUri(context, cameraUri!!)
                if (file != null) {
                    attachedImages = (attachedImages + file).distinctBy { it.absolutePath }.takeLast(MAX_PHOTOS)
                }
            }
        }
    }
    val cameraPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val dir = ImageImport.cameraDir(context)
            val photoFile = File(dir, "cam_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", photoFile)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, "Camera permission needed to take photos", Toast.LENGTH_SHORT).show()
        }
    }
    fun launchCamera() {
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) {
            val dir = ImageImport.cameraDir(context)
            val photoFile = File(dir, "cam_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", photoFile)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // Microphone: ask once, then start listening (speech is recognised on the phone).
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startListening() }
    fun micPermission() {
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) vm.startListening() else micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    // A tool needs a permission (calendar, notifications): ask, then the tool runs again.
    val toolPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onPermission(granted) }
    LaunchedEffect(s.permissionAsk) { s.permissionAsk?.let { toolPermLauncher.launch(it) } }

    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val isAssistant = remember(s.ready) {
        runCatching {
            context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
        }.getOrDefault(false)
    }
    // Summoned via long-press home / power: jump straight to typing.
    LaunchedEffect(assistRequest) { if (assistRequest > 0) runCatching { inputFocus.requestFocus(); keyboard?.show() } }

    @Composable
    fun FullScreen(content: @Composable () -> Unit) =
        Box(Modifier.fillMaxSize().background(R.bg).windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
    // First launch: ask the user's name before anything else (once).
    if (s.askName) { FullScreen { NameScreen(vm::setName) }; return }
    when (val p = s.phase) {
        is Phase.Onboarding -> { FullScreen { OnboardingScreen(p.choice) { tier, wifiOnly, downloadDrawing, imageTier -> vm.startDownload(tier, wifiOnly, downloadDrawing, imageTier) } }; return }
        is Phase.Downloading -> { FullScreen { DownloadScreen(p.tier, p.progress) { vm.retryDownload(p.tier) } }; return }
        is Phase.Error -> { FullScreen {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.Top) {
                GlowDot(R.red); Spacer(Modifier.width(10.dp)); Text(p.message, color = R.text, fontSize = 16.sp)
            }
        }; return }
        else -> Unit
    }

    LaunchedEffect(s.messages.size, s.messages.lastOrNull()?.text?.length, s.messages.lastOrNull()?.tools?.size) {
        if (s.messages.isNotEmpty()) listState.animateScrollToItem(s.messages.lastIndex)
    }

    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    var settingsOpen by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen && !settingsOpen) { scope.launch { drawer.close() } }   // Back closes the drawer first
    androidx.activity.compose.BackHandler(enabled = settingsOpen) { settingsOpen = false }

    val window = (context as? android.app.Activity)?.window
    androidx.compose.runtime.DisposableEffect(s.generating) {
        if (s.generating) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    Box(Modifier.fillMaxSize()) {
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            Sidebar(
                s,
                onNewChat = { vm.newChat(); scope.launch { drawer.close() } },
                onOpen = { id -> vm.openChat(id); scope.launch { drawer.close() } },
                onDelete = vm::deleteChat,
                onSettings = { settingsOpen = true },
            )
        },
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(Modifier.fillMaxSize().background(R.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            // What Ronie is doing replaces his name at the top as a glowing pill ("Thinking…", "Checking your calendar…").
            val pill: Pair<String, Mood>? = when {
                s.doing != null -> s.doing!! to when {
                    s.doing == "Thinking…" -> Mood.THINKING
                    s.doing!!.startsWith("Asking") || s.doing!!.startsWith("Searching") -> Mood.SEARCHING
                    else -> Mood.WORKING
                }
                s.speaking -> "Speaking…" to Mood.SPEAKING
                else -> null
            }
            // The title builds itself on its dot matrix as the wake-up stages finish.
            val build = if (s.phase == Phase.Chat) 1f
                else if (s.ready || s.boot.isEmpty()) (if (s.ready) 1f else 0.05f)
                else ((s.boot.count { it.done } + 0.5f * s.boot.count { it.active }) / s.boot.size).coerceIn(0.05f, 1f)
            TopBar(s, pill, build, onMenu = { scope.launch { drawer.open() } }, onNewChat = vm::newChat, onStopSpeaking = vm::stopSpeaking)
            if (s.xray == true) Text(
                "X-ray (Labs): green = matches what you gave Ronie · yellow = unsure · red = looks made up",
                style = MaterialTheme.typography.labelSmall, color = R.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Waking up: the stage cards, in the chat area itself.
                    if (s.phase == Phase.Loading && s.boot.isNotEmpty()) item(key = "boot") {
                        com.raunakpatil.roniepocket.ui.BootCards(s.boot, s.userName)
                    }
                    if (s.messages.isEmpty() && (s.ready || s.phase == Phase.Chat)) item {
                        EmptyState(s.userName, isAssistant, remember(s.chatId, s.toolsOn, s.drawingInstalled, s.offline) {
                            Suggestions.pick(s.chatId, s.toolsOn, s.drawingInstalled, !s.offline) }, onSuggestion = { vm.send(it) }, onMakeAssistant = {
                            // ROLE_ASSISTANT can't be requested with a dialog; send the user to the right settings page.
                            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                        })
                    }
                    items(s.messages, key = { it.id }) { m ->
                        Message(
                            m,
                            vm,
                            onOpenImage = { fullScreenImage = it },
                            onEditPrompt = { input = it },
                            onRerun = { vm.redraw(it) }
                        )
                    }
                }
            }

            s.stepDownOffer?.let { lighter ->
                Tile(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("I'm a bit slow on this phone. Want my ${lighter.label} brain (${lighter.model.sizeLabel})? Replies will be quicker.",
                        style = MaterialTheme.typography.bodyMedium, color = R.text)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Switch", vm::acceptStepDown)
                        PillButton("Keep this one", vm::dismissStepDown, primary = false)
                    }
                }
            }
            shared?.let { item ->
                SharedCard(item, onPick = { prompt, imgs -> vm.send(prompt, imgs); onSharedUsed() }, onDismiss = onSharedUsed)
            }
            // A voice problem ("Didn't catch that.") shows briefly above the text box.
            if (!s.listening && s.heard.isNotEmpty()) Text(s.heard, color = R.muted, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 24.dp))

            // While listening: what's been heard so far, just above the wave.
            AnimatedVisibility(s.listening && s.heard.isNotEmpty(), enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(s.heard, color = R.text, fontSize = 18.sp, fontWeight = FontWeight.Light,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
            }
            // Voice mode: ✕ in the middle, right above the wave, easy to reach with a thumb.
            AnimatedVisibility(s.listening, enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut() + scaleOut(targetScale = 0.6f),
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                RoundButton(Ic.Close, "Cancel", vm::cancelListening, size = 48.dp, background = R.surface2,
                    modifier = Modifier.padding(top = 6.dp))
            }

            // Selected images thumbnail preview above input bar
            AnimatedVisibility(attachedImages.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachedImages.forEach { file ->
                        Box(Modifier.size(60.dp)) {
                            val bm = com.raunakpatil.roniepocket.ui.rememberThumb(file.absolutePath, 200)
                            if (bm != null) {
                                Image(
                                    bitmap = bm,
                                    contentDescription = "Attached photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).border(1.dp, R.edge, RoundedCornerShape(12.dp))
                                )
                            }
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape).background(R.bg.copy(alpha = 0.85f))
                                    .clickable { attachedImages = attachedImages.filter { it != file } },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Ic.Close, "Remove", tint = R.text, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }

            InputBar(
                value = input, onValue = { input = it }, s = s, level = vm.micLevel,
                attachedImages = attachedImages,
                modifier = Modifier.focusRequester(inputFocus),
                onSend = {
                    val imgs = attachedImages.map { it.absolutePath }
                    vm.send(input, imgs)
                    input = ""
                    attachedImages = emptyList()
                    keyboard?.hide()
                },
                onStop = vm::stop,
                onMic = { keyboard?.hide(); micPermission() },
                onVoiceDone = vm::stopListening,
                onVoiceCancel = vm::cancelListening,
                onAttach = { showAttachMenu = true }
            )
        }
    }

    if (showAttachMenu) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAttachMenu = false },
            title = { Text("Attach Photo", color = R.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Choose from Photos", {
                        showAttachMenu = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, Modifier.fillMaxWidth())
                    PillButton("Take a Photo", {
                        showAttachMenu = false
                        launchCamera()
                    }, Modifier.fillMaxWidth(), primary = false)
                }
            },
            confirmButton = {},
            dismissButton = {
                PillButton("Cancel", { showAttachMenu = false }, primary = false)
            },
            containerColor = R.surface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (fullScreenImage != null) {
        FullScreenImageViewer(fullScreenImage!!, onDismiss = { fullScreenImage = null })
    }

    // Settings: its own page, sliding in over everything.
    androidx.compose.animation.AnimatedVisibility(
        settingsOpen,
        enter = androidx.compose.animation.slideInHorizontally { it } + fadeIn(),
        exit = androidx.compose.animation.slideOutHorizontally { it } + fadeOut(),
    ) { SettingsPage(s, vm, onBack = { settingsOpen = false }) }
    }
}

/**
 * Top bar. Left of "Ronie" sits a softly glowing status dot: green = ready to talk, yellow = warming up,
 * red = not working. When Ronie starts working, the dot swells into the status pill ("Thinking…",
 * "Checking your calendar…") and swallows his name; when he's done it shrinks back into the dot.
 */
@Composable
private fun TopBar(s: ChatState, pill: Pair<String, Mood>?, build: Float, onMenu: () -> Unit, onNewChat: () -> Unit, onStopSpeaking: () -> Unit) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Ic.Menu, "Settings", onMenu)
        com.raunakpatil.roniepocket.ui.TitleMorph(
            pill = pill,
            dotColor = when {
                s.phase is Phase.Error -> R.red
                s.ready -> R.green
                else -> Color(0xFFFFD24A)
            },
            tagline = "Private · Uncensored",
            onPillClick = if (pill?.second == Mood.SPEAKING) onStopSpeaking else null,
            modifier = Modifier.weight(1f),
            build = build,
        )
        RoundButton(Ic.Plus, "New chat", onNewChat, enabled = s.ready && !s.generating)
    }
}

/** A small status light with a soft, slowly breathing glow. */
@Composable
private fun GlowDot(color: Color) {
    val still = com.raunakpatil.roniepocket.ui.reducedMotion()
    val pulse = if (still) 0.7f else androidx.compose.animation.core.rememberInfiniteTransition(label = "dot").animateFloat(
        0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse").value
    val c by androidx.compose.animation.animateColorAsState(color, androidx.compose.animation.core.tween(600), label = "dotColor")
    androidx.compose.foundation.Canvas(Modifier.size(24.dp).semantics {
        contentDescription = when (color) { R.green -> "Ready"; R.red -> "Not working"; else -> "Warming up" }
    }) {
        val center = this.center
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(c.copy(alpha = 0.55f * pulse), Color.Transparent), center,
            size.minDimension / 2), size.minDimension / 2, center)
        drawCircle(c, 4.5.dp.toPx(), center)
        drawCircle(Color.White.copy(alpha = 0.55f), 1.4.dp.toPx(), center.copy(x = center.x - 1.3.dp.toPx(), y = center.y - 1.3.dp.toPx()))
    }
}

/** First launch: what should Ronie call you? (Shown once; Skip is fine.) */
@Composable
private fun NameScreen(onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Hey, I'm", fontSize = 40.sp, fontWeight = FontWeight.Light, color = R.muted, lineHeight = 44.sp)
        Text("Ronie.", fontSize = 40.sp, fontWeight = FontWeight.Medium, color = R.text, lineHeight = 44.sp)
        Spacer(Modifier.height(14.dp))
        Text("What should I call you?", fontSize = 20.sp, color = R.text)
        Spacer(Modifier.height(20.dp))
        TextField(
            value = name, onValueChange = { name = it.take(40) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Your name", color = R.muted) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (name.isNotBlank()) onDone(name) }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = R.surface2, unfocusedContainerColor = R.surface,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = R.orange, focusedTextColor = R.text, unfocusedTextColor = R.text,
            ),
        )
        Spacer(Modifier.height(16.dp))
        PillButton("Continue", { onDone(name) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
        Spacer(Modifier.height(8.dp))
        PillButton("Skip", { onDone("") }, Modifier.fillMaxWidth(), primary = false)
        Spacer(Modifier.height(16.dp))
        Text("Stays on this phone. You can tell Ronie a different name any time.", color = R.muted,
            style = MaterialTheme.typography.bodySmall)
    }
}

/** First screen of a chat: a big greeting (reference board style) and a few things to try. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(name: String?, isAssistant: Boolean, suggestions: List<String>, onSuggestion: (String) -> Unit, onMakeAssistant: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 6.dp, end = 6.dp)) {
        // A silver shine sweeps across the greeting now and then.
        com.raunakpatil.roniepocket.ui.ShineText(name?.takeIf { it.isNotBlank() }?.let { "Hey $it," } ?: "Hey,",
            androidx.compose.ui.text.TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, lineHeight = 44.sp), color = R.muted)
        Text("what can I do?", fontSize = 40.sp, fontWeight = FontWeight.Medium, color = R.text, lineHeight = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text("I live on your phone, so your data is yours only.", color = R.muted, fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { Chip(it, { onSuggestion(it) }) }
            if (!isAssistant) Chip("Make me your phone's assistant →", onMakeAssistant)
        }
    }
}

@Composable
private fun Message(
    m: UiMessage,
    vm: ChatViewModel,
    onOpenImage: (String) -> Unit,
    onEditPrompt: (String) -> Unit = {},
    onRerun: (String) -> Unit = {}
) {
    if (m.fromUser) {
        // Your messages: photos and/or white pills on the right.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (m.images.isNotEmpty()) {
                Row(
                    Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    m.images.forEach { path ->
                        val bm = com.raunakpatil.roniepocket.ui.rememberThumb(path, 720)
                        if (bm != null) {
                            Image(
                                bitmap = bm,
                                contentDescription = "Attached photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .heightIn(max = 220.dp)
                                    .widthIn(max = 220.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, R.edge, RoundedCornerShape(16.dp))
                                    .clickable { onOpenImage(path) }
                            )
                        }
                    }
                }
            }
            if (m.text.isNotBlank()) {
                Text(
                    m.text,
                    modifier = Modifier.widthIn(max = 300.dp)
                        .background(R.text, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    color = R.bg, fontSize = 16.sp, lineHeight = 22.sp,
                )
            }
        }
    } else {
        // Ronie: text straight on the canvas, tools as tiles above it.
        Column(Modifier.fillMaxWidth().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            m.tools.forEach { c ->
                com.raunakpatil.roniepocket.tools.ToolCardView(
                    c,
                    onConfirm = { yes -> vm.confirm(c.id, yes) },
                    onUndo = { vm.undo(c.id) },
                    onImageClick = onOpenImage,
                    onEditPrompt = onEditPrompt,
                    onRerun = onRerun
                )
            }
            when {
                m.xray != null -> XrayText(m.xray)
                m.text.isNotEmpty() -> com.raunakpatil.roniepocket.cards.RonieReply(m.text, checklistMode = m.checklist, seed = m.id)
            }
        }
    }
}

/**
 * The bottom row. Tapping the mic morphs it into voice mode on the same line: the text box glides off to the
 * left, the mic drifts to the middle and opens into a glowing waveform that reacts to your voice (soft
 * springs, so it feels organic). Tap the wave to send what you said, ✕ to cancel.
 */
@Composable
private fun InputBar(value: String, onValue: (String) -> Unit, s: ChatState, level: kotlinx.coroutines.flow.StateFlow<Float>,
                     attachedImages: List<File>, modifier: Modifier, onSend: () -> Unit, onStop: () -> Unit, onMic: () -> Unit,
                     onVoiceDone: () -> Unit, onVoiceCancel: () -> Unit, onAttach: () -> Unit) {
    val lvl by level.collectAsStateWithLifecycle()
    // 0 = typing, 1 = voice. A slow, slightly soft spring for the whole morph.
    val p by androidx.compose.animation.core.animateFloatAsState(
        if (s.listening) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 90f), label = "voice")
    val waveTap = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth().height(76.dp).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
    ) {
        val full = maxWidth
        val btn = 52.dp
        val attachBtn = 48.dp
        val fullPx = with(androidx.compose.ui.platform.LocalDensity.current) { full.toPx() }

        // The text box and attach button slide out to the left and fade.
        if (p < 0.999f) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart)
                    .graphicsLayer { translationX = -p * fullPx; alpha = (1f - p * 1.4f).coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundButton(Ic.Plus, "Attach photo", onAttach, size = attachBtn, background = R.surface2)
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = value, onValueChange = onValue,
                    modifier = modifier.width(full - btn - attachBtn - 16.dp),
                    placeholder = { Text(if (s.ready || s.phase == Phase.Chat) "Ask anything" else "Ask anything (waking up…)", color = R.muted, maxLines = 1) },
                    maxLines = 5,
                    enabled = !s.listening,
                    shape = RoundedCornerShape(28.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = R.surface2, unfocusedContainerColor = R.surface, disabledContainerColor = R.surface,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                        cursorColor = R.orange, focusedTextColor = R.text, unfocusedTextColor = R.text, disabledTextColor = R.muted,
                    ),
                )
            }
        }

        // The mic travels from the right edge to the middle while it widens into the wave.
        val waveWidth = btn + (full * 0.78f - btn) * p
        val centerX = (full - btn / 2) + ((full / 2) - (full - btn / 2)) * p
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = centerX - waveWidth / 2).width(waveWidth).height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (p > 0.02f) com.raunakpatil.roniepocket.ui.VoiceWave(
                lvl,
                Modifier.fillMaxSize().alpha(p.coerceIn(0f, 1f))
                    .clickable(interactionSource = waveTap, indication = null, enabled = s.listening, onClick = onVoiceDone),
                grow = p)
            if (p < 0.98f) Box(Modifier.alpha((1f - p).coerceIn(0f, 1f)).scale(1f - 0.4f * p)) {
                when {
                    s.generating -> RoundButton(Ic.Stop, "Stop", onStop, size = btn, brush = R.diagonal(R.ember), enabled = s.ready)
                    value.isBlank() && attachedImages.isEmpty() && s.micAvailable -> RoundButton(Ic.Mic, "Speak to Ronie", onMic, size = btn)
                    else -> RoundButton(Ic.ArrowUp, "Send", onSend, size = btn, background = R.text, tint = R.bg, enabled = value.isNotBlank() || attachedImages.isNotEmpty())
                }
            }
        }
    }
}

/** X-ray v0: each sentence highlighted by the probe's grounded-vs-invented score. */
@Composable
private fun XrayText(segments: List<Xray.Segment>) {
    val text = androidx.compose.ui.text.buildAnnotatedString {
        for (seg in segments) {
            val p = seg.score?.let { Xray.probability(it) }
            val bg = when {
                p == null -> Color.Transparent
                p >= 0.7f -> Color(0x553ED47F)
                p >= 0.4f -> Color(0x66FFB13B)
                else -> Color(0x66FF3B5C)
            }
            withStyle(androidx.compose.ui.text.SpanStyle(background = bg)) { append(seg.text) }
        }
    }
    Text(text, color = R.text, style = MaterialTheme.typography.bodyLarge)
}

/** Something shared into Ronie from another app (share sheet). */
/** Photos per message. Ronie keeps the last two photos of a chat in view (see pruneOldImagesInHistory). */
const val MAX_PHOTOS = 2

data class SharedItem(val text: String = "", val subject: String? = null, val images: List<String> = emptyList()) {
    val isImage: Boolean get() = images.isNotEmpty()
    val isLink: Boolean get() = text.isNotBlank() && Regex("""^https?://\S+$""").matches(text.trim())
}

/** "Ask Ronie" quick actions for shared text, links, or images. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedCard(item: SharedItem, onPick: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    Tile(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (item.isImage) {
            EyebrowText("Shared image" + if (item.images.size > 1) " · ${item.images.size}" else "", color = R.orange)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.images.forEach { path ->
                    val bm = com.raunakpatil.roniepocket.ui.rememberThumb(path, 200)
                    if (bm != null) {
                        Image(
                            bitmap = bm,
                            contentDescription = "Shared photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, R.edge, RoundedCornerShape(12.dp))
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val actions = listOf(
                "What's this?" to "What's this?",
                "Read text" to "Read all text visible in this image.",
                "Summarise" to "Summarise this image.",
                "Receipt → expense" to "Extract receipt details from this image into an expense card.",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { (label, prompt) -> Chip(label, { onPick(prompt, item.images) }) }
                Chip("Dismiss", onDismiss)
            }
        } else {
            EyebrowText(if (item.isLink) "Shared link" else "Shared with Ronie", color = R.orange)
            Spacer(Modifier.height(6.dp))
            Text(item.text.take(240) + if (item.text.length > 240) "…" else "", style = MaterialTheme.typography.bodyMedium,
                color = R.text, maxLines = 4)
            Spacer(Modifier.height(12.dp))
            val body = (item.subject?.let { "$it\n\n" } ?: "") + item.text.take(6000)
            val actions = if (item.isLink) listOf(
                "Add task" to "Add a task to read this later: $body",
                "Remember this" to "Remember this link: $body",
            ) else listOf(
                "Summarise" to "Summarise this:\n\n$body",
                "Explain" to "Explain this simply:\n\n$body",
                "Add task" to "Add a task for this: $body",
                "Remember this" to "Remember this about me: $body",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { (label, prompt) -> Chip(label, { onPick(prompt, emptyList()) }) }
                Chip("Dismiss", onDismiss)
            }
            if (item.isLink) Text("Ronie doesn't open links yet, so nothing is sent anywhere.",
                style = MaterialTheme.typography.labelSmall, color = R.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun FullScreenImageViewer(path: String, onDismiss: () -> Unit) {
    val bm = remember(path) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() } ?: return
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bm.asImageBitmap(),
            contentDescription = "Full screen photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
        )
        RoundButton(
            Ic.Close, "Close", onDismiss, size = 44.dp, background = R.surface2,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).windowInsetsPadding(WindowInsets.safeDrawing)
        )
    }
}
