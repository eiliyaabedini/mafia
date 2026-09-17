package ir.iact.mafiagame

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import ir.iact.mafiagame.ai.*
import ir.iact.mafiagame.domain.*
import ir.iact.mafiagame.domain.Role
import ir.iact.mafiagame.ui.*
import mafiagame.shared.generated.resources.*
import org.jetbrains.compose.resources.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private val Ink = Color(0xFF10100F)
private val Panel = Color(0xFF1B1B19)
private val Line = Color(0xFF34332E)
private val Gold = Color(0xFFE0B77A)
private val Muted = Color(0xFFA6A298)
private val Paper = Color(0xFFF1EBDF)
private val Crimson = Color(0xFFCB817C)

private enum class Modal { RULES, LEAVE, NOTES, AUDIO, WELCOME, NAME, SAVES }

private data class DeathAnnouncement(
    val gameId: String,
    val eventIndex: Int,
    val playerId: String,
    val kind: EventKind,
) {
    val key get() = "$gameId:$eventIndex"
}

@Composable
fun App(gateway: AiGateway = UnavailableGateway, showWelcomeOnLaunch: Boolean = true, onWelcomeDismissed: () -> Unit = {},
    initialPlayerName: String = "", onPlayerNameSaved: (String) -> Unit = {}, initialSave: SavedApp? = null,
    resumeSavedGameOnLaunch: Boolean = false) {
    val scope = rememberCoroutineScope()
    // OAuth callbacks restore synchronously so the lobby never flashes in
    // place of the table while a post-composition effect is still pending.
    val controller = remember(gateway) {
        GameController(gateway, scope, initialPlayerName, onPlayerNameSaved, initialSave, resumeSavedGameOnLaunch)
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    val font = FontFamily(Font(Res.font.vazirmatn))
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = font), displayMedium = base.displayMedium.copy(fontFamily = font), displaySmall = base.displaySmall.copy(fontFamily = font),
        headlineLarge = base.headlineLarge.copy(fontFamily = font), headlineMedium = base.headlineMedium.copy(fontFamily = font), headlineSmall = base.headlineSmall.copy(fontFamily = font),
        titleLarge = base.titleLarge.copy(fontFamily = font), titleMedium = base.titleMedium.copy(fontFamily = font), titleSmall = base.titleSmall.copy(fontFamily = font),
        bodyLarge = base.bodyLarge.copy(fontFamily = font), bodyMedium = base.bodyMedium.copy(fontFamily = font), bodySmall = base.bodySmall.copy(fontFamily = font),
        labelLarge = base.labelLarge.copy(fontFamily = font), labelMedium = base.labelMedium.copy(fontFamily = font), labelSmall = base.labelSmall.copy(fontFamily = font),
    )
    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel, onSurface = Paper, onBackground = Paper, outline = Line, secondary = Gold), typography = typography) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = Ink) {
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val compact = maxWidth < 820.dp
                    val game = controller.game
                    val presentedGame = controller.presentedGame
                    var observedGameId by remember { mutableStateOf(game?.id) }
                    var observedMessageCount by remember { mutableIntStateOf(game?.conversation?.size ?: 0) }
                    var deathAnnouncement by remember { mutableStateOf<DeathAnnouncement?>(null) }
                    var modal by remember { mutableStateOf<Modal?>(if (showWelcomeOnLaunch) Modal.WELCOME else null) }
                    var startAfterName by remember { mutableStateOf(false) }
                    val startGame: () -> Unit = {
                        if (PlayerNames.valid(controller.playerName)) controller.newGame()
                        else { startAfterName = true; modal = Modal.NAME }
                    }
                    var tutorialTrack by remember { mutableStateOf<TutorialTrack?>(null) }
                    var walletChecked by remember { mutableStateOf(false) }
                    val appFocus = remember { FocusRequester() }
                    val closeModal: () -> Unit = {
                        if (modal == Modal.WELCOME) onWelcomeDismissed()
                        modal = null
                        if (tutorialTrack == null) scope.launch { withFrameNanos { }; appFocus.requestFocus() }
                    }
                    val openTutorial: () -> Unit = {
                        if (modal == Modal.WELCOME) onWelcomeDismissed()
                        modal = null
                        tutorialTrack = TutorialTrack.BASICS
                    }
                    // New players can finish the entire tutorial without loading AI Pass.
                    val lobbyReady = tutorialTrack == null && modal != Modal.WELCOME
                    LaunchedEffect(controller, modal == Modal.WELCOME, game?.id) { controller.setMusicSessionActive(modal != Modal.WELCOME) }
                    LaunchedEffect(lobbyReady) {
                        if (lobbyReady && !walletChecked) {
                            walletChecked = true
                            controller.refreshWallet()
                        }
                    }
                    LaunchedEffect(game?.id, game?.conversation?.size) {
                        val current = game
                        when {
                            current == null -> {
                                observedGameId = null
                                observedMessageCount = 0
                                deathAnnouncement = null
                            }
                            observedGameId != current.id -> {
                                // Restoring a saved game must not replay an old death announcement.
                                observedGameId = current.id
                                observedMessageCount = current.conversation.size
                                deathAnnouncement = null
                            }
                            else -> {
                                val start = observedMessageCount.coerceIn(0, current.conversation.size)
                                val freshDeath = current.conversation.withIndex().drop(start).lastOrNull {
                                    it.value.kind == EventKind.ELIMINATED || it.value.kind == EventKind.NIGHT_KILLED
                                }
                                observedMessageCount = current.conversation.size
                                val targetId = freshDeath?.value?.targetId
                                if (freshDeath != null && targetId != null && current.players.any { it.id == targetId }) {
                                    // Final ballot and elimination can be committed atomically by the engine.
                                    // Let the table read that ballot before covering it with the death scene.
                                    while (controller.ballotHold?.id == current.id) delay(50)
                                    if (controller.game?.id == current.id) {
                                        deathAnnouncement = DeathAnnouncement(current.id, freshDeath.index, targetId, freshDeath.value.kind)
                                    }
                                }
                            }
                        }
                    }
                    if (tutorialTrack != null) {
                        val tutorialPlaying = controller.tutorialLoading && controller.mediaStatus.clipId == controller.tutorialClipId && controller.mediaStatus.playing
                        Box(Modifier.fillMaxSize().focusProperties { onEnter = { if (modal != null) cancelFocusChange() } }.focusGroup()
                            .then(if (modal != null) Modifier.clearAndSetSemantics { } else Modifier)) {
                        TutorialScreen(
                            initialTrack = tutorialTrack!!,
                            onClose = { tutorialTrack = null },
                            onPlay = { tutorialTrack = null; startGame() },
                            audio = TutorialAudioState(
                                supported = controller.audioSupported && controller.mediaStatus.tutorialAvailable,
                                ready = controller.audioPreferenceReady,
                                enabled = controller.audioEnabled,
                                volumeMuted = controller.audioVolume <= 0f,
                                clipId = controller.tutorialClipId,
                                playing = tutorialPlaying,
                                pending = controller.tutorialLoading && !tutorialPlaying,
                                error = controller.tutorialError ?: controller.mediaStatus.error?.takeIf {
                                    controller.tutorialClipId != null && controller.mediaStatus.clipId == controller.tutorialClipId
                                },
                            ),
                            onNarrate = controller::narrateTutorial,
                            onStopNarration = controller::stopTutorialNarration,
                            onOpenAudio = { modal = Modal.AUDIO },
                        )
                        }
                    } else {
                    Column(Modifier.widthIn(max = 1320.dp).fillMaxSize().align(Alignment.TopCenter).padding(horizontal = if (compact) 16.dp else 40.dp)
                        .focusRequester(appFocus).focusRestorer().focusProperties { onEnter = { if (modal != null) cancelFocusChange() } }.focusGroup()
                        .then(if (modal != null) Modifier.clearAndSetSemantics { } else Modifier)) {
                        Header(controller, compact,
                            { if (game == null) openTutorial() else modal = Modal.RULES },
                            { modal = Modal.LEAVE }, { modal = Modal.AUDIO })
                        when {
                            presentedGame == null -> Lobby(controller, compact, startGame, { startAfterName = false; modal = Modal.NAME }, { modal = Modal.SAVES })
                            presentedGame.phase == Phase.REVEAL -> Reveal(presentedGame, controller::beginDay)
                            presentedGame.phase == Phase.FINISHED -> Results(presentedGame, controller::leave)
                            else -> GameScreen(presentedGame, controller, compact, { modal = Modal.NOTES }, { modal = Modal.AUDIO })
                        }
                    }
                    }
                    when (modal) {
                        Modal.WELCOME -> InCanvasModal(tr(Res.string.welcome_title), closeModal) {
                            Image(painterResource(Res.drawable.tutorial_table), null, Modifier.fillMaxWidth().aspectRatio(1.7f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                            FText(tr(Res.string.welcome_body), size = 17)
                            GoldButton(tr(Res.string.welcome_learn), openTutorial, Modifier.fillMaxWidth())
                            TextButton(closeModal, Modifier.fillMaxWidth()) { FText(tr(Res.string.welcome_skip), size = 15, color = Muted) }
                        }
                        Modal.SAVES -> InCanvasModal(tr(Res.string.storage_title), closeModal) { StorageControls(controller) }
                        Modal.NAME -> InCanvasModal(tr(Res.string.player_name_title), closeModal) {
                            PlayerNameForm(controller.playerName) { name ->
                                controller.updatePlayerName(name)
                                closeModal()
                                if (startAfterName) controller.newGame()
                            }
                        }
                        Modal.RULES -> InCanvasModal(tr(Res.string.how_to_play), closeModal) {
                            FText(tr(Res.string.rules_body), size = 14)
                        }
                        Modal.LEAVE -> InCanvasModal(tr(Res.string.leave_title), closeModal) {
                            FText(tr(Res.string.leave_body), size = 14)
                            GoldButton(tr(Res.string.stay), closeModal, Modifier.fillMaxWidth())
                            TextButton(onClick = { closeModal(); controller.leave() }, modifier = Modifier.fillMaxWidth()) { FText(tr(Res.string.confirm_leave), color = Crimson) }
                        }
                        Modal.NOTES -> if (game != null) InCanvasModal(tr(Res.string.private_info), closeModal) {
                            PrivateNotes(game, controller.notes, controller::updateNotes)
                            TextButton({ modal = Modal.SAVES }) { FText(tr(Res.string.storage_title), color = Gold) }
                        }
                        Modal.AUDIO -> InCanvasModal(tr(Res.string.music_audio_settings), closeModal) { AudioControls(controller) }
                        null -> Unit
                    }
                    val announcement = deathAnnouncement
                    if (announcement != null && game?.id == announcement.gameId && controller.ballotHold == null) {
                        DeathAnnouncementOverlay(game, announcement, compact) { finishedKey ->
                            if (deathAnnouncement?.key == finishedKey) deathAnnouncement = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeathAnnouncementOverlay(
    game: Game,
    announcement: DeathAnnouncement,
    compact: Boolean,
    onFinished: (String) -> Unit,
) {
    val player = game.player(announcement.playerId)
    val isNight = announcement.kind == EventKind.NIGHT_KILLED
    val appear = remember(announcement.key) { Animatable(0f) }
    val mark = remember(announcement.key) { Animatable(0f) }

    LaunchedEffect(announcement.key) {
        appear.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        mark.animateTo(1f, tween(if (isNight) 380 else 650, easing = FastOutSlowInEasing))
        delay(2_100)
        appear.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
        onFinished(announcement.key)
    }

    val title = tr(if (isNight) Res.string.night_death_title else Res.string.day_death_title)
    val badge = tr(if (isNight) Res.string.night_death_badge else Res.string.day_death_badge)
    val body = tr(if (isNight) Res.string.night_death_body else Res.string.day_death_body, player.character.name)
    val portraitSize = if (compact) 176.dp else 248.dp
    val shake = if (isNight) sin(mark.value * PI * 8).toFloat() * (1f - mark.value) * 12f else 0f

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = .88f * appear.value))
            .pointerInput(announcement.key) { detectTapGestures { } }
            .clearAndSetSemantics { dialog(); paneTitle = title },
        contentAlignment = Alignment.Center,
    ) {
        val veryShort = maxHeight < 520.dp
        Column(
            Modifier.padding(20.dp)
                .widthIn(max = 560.dp)
                .graphicsLayer {
                    alpha = appear.value
                    scaleX = .84f + (.16f * appear.value)
                    scaleY = .84f + (.16f * appear.value)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (veryShort) 8.dp else 16.dp),
        ) {
            FText(
                badge,
                Modifier.clip(RoundedCornerShape(50)).background(Crimson.copy(alpha = .16f))
                    .border(1.dp, Crimson.copy(alpha = .55f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                size = 13,
                color = Crimson,
                weight = FontWeight.Bold,
            )
            Box(
                Modifier.size(if (veryShort) 138.dp else portraitSize)
                    .graphicsLayer { translationX = shake },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(portrait(player.character.id)),
                    player.character.name,
                    Modifier.matchParentSize().clip(CircleShape)
                        .border(if (isNight) 4.dp else 3.dp, if (isNight) Crimson else Gold, CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Canvas(Modifier.matchParentSize()) {
                    if (isNight) {
                        val flash = ((.48f - mark.value) / .48f).coerceIn(0f, 1f)
                        if (flash > 0f) {
                            drawCircle(Color.White.copy(alpha = flash * .78f), radius = size.minDimension * (.12f + flash * .34f))
                            drawCircle(Gold.copy(alpha = flash * .9f), radius = size.minDimension * (.07f + flash * .18f))
                        }
                        if (mark.value > .18f) {
                            val impact = ((mark.value - .18f) / .82f).coerceIn(0f, 1f)
                            val center = androidx.compose.ui.geometry.Offset(size.width * .57f, size.height * .43f)
                            drawCircle(Crimson.copy(alpha = .62f), radius = size.minDimension * .072f * impact, center = center)
                            drawCircle(Color(0xFF120707), radius = size.minDimension * .038f * impact, center = center)
                            repeat(6) { ray ->
                                val angle = ray * PI.toFloat() / 3f
                                val inner = size.minDimension * .05f
                                val outer = size.minDimension * (.06f + .065f * impact)
                                drawLine(
                                    Crimson.copy(alpha = .72f * impact),
                                    androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle) * inner, center.y + sin(angle) * inner),
                                    androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle) * outer, center.y + sin(angle) * outer),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    } else {
                        val first = (mark.value * 1.85f).coerceIn(0f, 1f)
                        val second = ((mark.value - .46f) * 1.85f).coerceIn(0f, 1f)
                        val inset = size.minDimension * .17f
                        drawLine(
                            Crimson,
                            androidx.compose.ui.geometry.Offset(inset, inset),
                            androidx.compose.ui.geometry.Offset(inset + (size.width - inset * 2f) * first, inset + (size.height - inset * 2f) * first),
                            strokeWidth = 13.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        if (second > 0f) drawLine(
                            Crimson,
                            androidx.compose.ui.geometry.Offset(size.width - inset, inset),
                            androidx.compose.ui.geometry.Offset(size.width - inset - (size.width - inset * 2f) * second, inset + (size.height - inset * 2f) * second),
                            strokeWidth = 13.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            FText(title, size = if (compact) 28 else 36, weight = FontWeight.ExtraBold, color = if (isNight) Crimson else Paper, align = TextAlign.Center)
            FText(body, Modifier.fillMaxWidth(), size = if (compact) 17 else 20, weight = FontWeight.Bold, align = TextAlign.Center)
            FText(tr(Res.string.death_role_hidden), size = 13, color = Muted, align = TextAlign.Center)
        }
    }
}

/** A modal drawn in the existing canvas, with one scroll region and no platform window. */
@Composable private fun InCanvasModal(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val closeFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val dismissLabel = tr(Res.string.close)
    LaunchedEffect(Unit) { closeFocus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false
        else when (event.key) {
            Key.Escape -> { close(); true }
            Key.Tab -> { focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true }
            else -> false
        }
    }) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .74f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = dismissLabel, onClick = close)
            .clearAndSetSemantics { })
        Surface(Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = 540.dp).fillMaxWidth()
            .heightIn(max = (maxHeight - 32.dp).coerceAtLeast(120.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
            .semantics { dialog(); paneTitle = title; isTraversalGroup = true },
            color = Panel, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Line), shadowElevation = 18.dp) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FText(title, Modifier.weight(1f), size = 21, weight = FontWeight.Bold)
                    TextButton(close, Modifier.focusRequester(closeFocus)) { FText(dismissLabel, size = 13, color = Gold) }
                }
                HorizontalDivider(color = Line)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
    }
}

@Composable private fun FText(text: String, modifier: Modifier = Modifier, size: Int = 15, color: Color = Paper, weight: FontWeight = FontWeight.Normal, maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Start) {
    Text(rtl(text), modifier, fontSize = size.sp, lineHeight = (size * 1.65).sp, color = color, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl))
}
@Composable private fun GoldButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = 50.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 26.dp, vertical = 10.dp)) { FText(label, color = if (enabled) Ink else Muted, weight = FontWeight.Bold) }
}
@Composable private fun Chip(text: String, color: Color = Gold) {
    FText(text, Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .09f)).border(1.dp, color.copy(alpha = .18f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 5.dp), size = 12, color = color)
}
@Composable private fun Header(controller: GameController, compact: Boolean, rules: () -> Unit, leave: () -> Unit, openAudio: () -> Unit) {
    val inGame = controller.game != null
    val audioEnabled = controller.audioEnabled || controller.musicEnabled || controller.effectsEnabled
    Row(Modifier.fillMaxWidth().height(if (compact) 68.dp else 86.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compact) Image(painterResource(Res.drawable.icon), null, Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
            Column {
                FText(tr(Res.string.brand_short), size = if (compact) 22 else 25, weight = FontWeight.ExtraBold)
                if (!compact) FText(tr(Res.string.brand_tag), size = 11, color = Muted)
            }
        }
        WalletChip(controller, compact)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = rules, modifier = Modifier.widthIn(min = 44.dp), contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 8.dp)) { FText(tr(if (!inGame) Res.string.learn_game else if (compact) Res.string.rules_short else Res.string.how_to_play), size = 13, color = if (inGame) Muted else Gold) }
            if (inGame) TextButton(onClick = leave, modifier = Modifier.widthIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { FText(tr(Res.string.leave), size = 13, color = Crimson) }
            else {
                val audioLabel = tr(Res.string.music_audio_settings)
                val audioState = tr(if (audioEnabled) Res.string.audio_on else Res.string.audio_off)
                TextButton(openAudio, contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 10.dp), modifier = Modifier.widthIn(min = 44.dp).semantics { contentDescription = audioLabel; stateDescription = audioState }) {
                    VoiceMark(audioEnabled, small = true)
                    if (!compact) {
                        Spacer(Modifier.width(8.dp))
                        FText(audioState, size = 12, color = if (audioEnabled) Gold else Muted)
                    }
                }
            }
        }
    }
}

@Composable private fun WalletChip(controller: GameController, compact: Boolean) {
    val wallet = controller.walletStatus
    val title = tr(Res.string.wallet_title)
    val connected = wallet.connection == WalletConnection.CONNECTED
    val amount = wallet.balance?.let(::walletBadgeAmount)
    val working = controller.walletOpening || controller.walletRefreshing
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.height(44.dp).widthIn(max = if (compact) 116.dp else 138.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = controller.walletSupported && !controller.walletOpening,
                onClickLabel = title, onClick = controller::openWallet)
            .semantics {
                role = androidx.compose.ui.semantics.Role.Button
                contentDescription = title
                stateDescription = if (connected) amount ?: "AI Pass connected" else "Connect AI Pass"
            }
            .background(Color(0xFF191817))
            .border(1.dp, Paper.copy(alpha = .14f), RoundedCornerShape(12.dp))
            .padding(start = 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFF6366F1))))) {
                Canvas(Modifier.matchParentSize()) {
                    val shine = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width * .42f, 0f)
                        lineTo(size.width * .72f, size.height * .3f)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(shine, Color.White.copy(alpha = .14f))
                }
                Text("AI", Modifier.align(Alignment.Center), color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Black, maxLines = 1,
                    style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
            }
            if (connected) {
                Text("PASS", color = Paper, fontSize = if (compact) 9.sp else 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = .5.sp, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                if (amount != null) {
                    Text(amount, Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF7C3AED).copy(alpha = .18f))
                        .border(1.dp, Color(0xFFA78BFA).copy(alpha = .2f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                        color = if (wallet.stale) Muted else Color(0xFFC4B5FD),
                        fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                } else if (working) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = Color(0xFFC4B5FD), strokeWidth = 1.5.dp)
                }
            } else {
                Text("CONNECT", color = Paper, fontSize = if (compact) 9.sp else 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = .6.sp, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                if (working) CircularProgressIndicator(Modifier.size(10.dp), color = Color(0xFFC4B5FD), strokeWidth = 1.5.dp)
            }
        }
    }
}

@Composable private fun PlayerNameForm(initial: String, save: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = PlayerNames.valid(name)
    FText(tr(Res.string.player_name_body), size = 16)
    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(),
        label = { FText(tr(Res.string.player_name_label), size = 14) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrRtl),
        isError = name.isNotBlank() && !valid,
        supportingText = { FText(tr(Res.string.player_name_hint), size = 11, color = Muted) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (valid) save(name) }))
    FText(tr(Res.string.player_name_saved_note), size = 12, color = Muted)
    GoldButton(tr(Res.string.player_name_save), { save(name) }, Modifier.fillMaxWidth(), enabled = valid)
}

@Composable private fun StorageControls(controller: GameController) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FText(tr(if (controller.localSaveFailed) Res.string.storage_local_failed else Res.string.storage_local_note), size = 14,
            color = if (controller.localSaveFailed) Crimson else Paper)
        FText(tr(Res.string.storage_backup_note), size = 12, color = Muted)
        GoldButton(tr(if (controller.storageBusy) Res.string.storage_working else Res.string.storage_backup), controller::backup,
            Modifier.fillMaxWidth(), enabled = !controller.storageBusy)
        if (controller.game == null) {
            TextButton(controller::restoreBackup, enabled = !controller.storageBusy && !controller.busy) { FText(tr(Res.string.storage_restore), color = Gold) }
            FText(tr(Res.string.storage_restore_note), size = 11, color = Muted)
        }
        controller.storageMessage?.let { message ->
            FText(tr(when (message) {
                "BACKUP_SAVED" -> Res.string.storage_saved
                "BACKUP_RESTORED" -> Res.string.storage_restored
                "BACKUP_EMPTY" -> Res.string.storage_empty
                "STORAGE_CONFLICT" -> Res.string.storage_conflict
                "STORAGE_INVALID" -> Res.string.storage_invalid
                else -> Res.string.storage_failed
            }), size = 13, color = Gold)
        }
    }
}

@Composable private fun Lobby(controller: GameController, compact: Boolean, start: () -> Unit, editName: () -> Unit, openSaves: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 24.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        controller.error?.let { code -> item { ErrorPanel(code, controller::retryLobbyOperation) } }
        item {
            Box(Modifier.fillMaxWidth().heightIn(min = if (compact) 260.dp else 340.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, Line, RoundedCornerShape(24.dp))) {
                Image(painterResource(Res.drawable.room), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Ink.copy(alpha = .18f), Ink.copy(alpha = .88f)))))
                Column(Modifier.align(Alignment.CenterStart).widthIn(max = 550.dp).padding(if (compact) 18.dp else 30.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
                    if (!compact) FText(tr(Res.string.hero_kicker), size = 12, color = Gold)
                    FText(tr(Res.string.hero_title), size = if (compact) 30 else 43, weight = FontWeight.ExtraBold)
                    FText(tr(if (compact) Res.string.hero_body_short else Res.string.hero_body), size = if (compact) 13 else 15, color = Paper.copy(alpha = .86f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        GoldButton(if (controller.busy) tr(Res.string.prepare) else tr(if (controller.savedGame != null) Res.string.storage_new_game else Res.string.start), start, enabled = !controller.busy && !controller.storageBusy && controller.supported)
                        if (controller.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Gold, strokeWidth = 2.dp)
                        else FText(tr(Res.string.seven_players), Modifier.weight(1f), size = 12, color = Paper.copy(alpha = .75f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (controller.playerName.isNotBlank()) TextButton(editName, enabled = !controller.busy, contentPadding = PaddingValues(0.dp)) {
                            FText(tr(Res.string.player_name_current, controller.playerName), size = 12, color = Gold)
                        }
                        TextButton(openSaves, enabled = !controller.storageBusy, contentPadding = PaddingValues(0.dp)) {
                            FText(tr(Res.string.storage_title), size = 12, color = Muted)
                        }
                    }
                    controller.savedGame?.let { saved ->
                        TextButton(controller::resumeSavedGame, enabled = !controller.busy && !controller.storageBusy, contentPadding = PaddingValues(0.dp)) {
                            FText(tr(if (saved.phase == Phase.FINISHED) Res.string.storage_last_result else Res.string.storage_resume,
                                faNumber(saved.day)), color = Gold, size = 15, weight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FText(tr(Res.string.meet_table), size = 23, weight = FontWeight.Bold)
                FText(tr(Res.string.characters_intro), color = Muted, size = 13)
            }
        }
        item {
            BoxWithConstraints {
                val columns = if (maxWidth >= 950.dp) 6 else if (maxWidth >= 550.dp) 3 else 2
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Characters.all.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { profile -> CharacterCard(profile, controller, Modifier.weight(1f)) } }
                    }
                }
            }
        }
        if (!controller.supported) item { FText(tr(Res.string.web_only), color = Crimson) }
    }
}

@Composable private fun VoiceMark(active: Boolean, small: Boolean = false) {
    Row(Modifier.size(if (small) 24.dp else 40.dp).clip(RoundedCornerShape(if (small) 7.dp else 12.dp)).background(Gold.copy(alpha = if (active) .12f else .05f)), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        listOf(8, 16, 22, 13, 7).forEach { height ->
            Box(Modifier.width(if (small) 2.dp else 3.dp).height((if (small) height * .65f else height.toFloat()).dp).clip(RoundedCornerShape(2.dp)).background(if (active) Gold else Muted.copy(alpha = .6f)))
        }
    }
}

@Composable private fun AudioControls(controller: GameController) {
    val enableLabel = tr(Res.string.audio_enable)
    val volumeLabel = tr(Res.string.audio_volume)
    val percent = (controller.audioVolume.coerceIn(0f, 1f) * 100).toInt()
    val volumeText = tr(Res.string.audio_percent, faNumber(percent))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MusicControls(controller)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VoiceMark(controller.audioEnabled)
            Column(Modifier.weight(1f)) {
                FText(tr(if (controller.mediaStatus.tutorialAvailable) Res.string.narration_title else Res.string.audio_title), size = 17, weight = FontWeight.Bold)
                FText(tr(when {
                    !controller.audioEnabled -> Res.string.narration_muted
                    controller.audioPausedAfterError -> Res.string.narration_game_paused
                    else -> Res.string.narration_ready
                }), size = 11, color = Muted)
            }
            Switch(checked = controller.audioEnabled, onCheckedChange = controller::updateAudioEnabled, enabled = controller.audioSupported, modifier = Modifier.semantics { contentDescription = enableLabel })
        }
        if (!controller.audioSupported) FText(tr(Res.string.audio_unavailable), size = 12, color = Crimson)
        if (controller.audioEnabled) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FText(volumeLabel, Modifier.weight(1f), size = 12, color = Paper)
                FText(volumeText, size = 12, color = Gold)
            }
            Slider(value = controller.audioVolume.coerceIn(0f, 1f), onValueChange = controller::updateAudioVolume, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().semantics { contentDescription = volumeLabel; stateDescription = volumeText })
        }
        if (controller.audioError != null) AudioErrorNotice(controller)
        else if (controller.audioPausedAfterError) TextButton({ controller.updateAudioEnabled(true) }, enabled = controller.audioSupported) {
            FText(tr(Res.string.audio_reenable), size = 12, color = Gold)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Line)
        EffectsControls(controller)
    }
}

@Composable private fun EffectsControls(controller: GameController) {
    val enableLabel = tr(Res.string.effects_enable)
    val volumeLabel = tr(Res.string.effects_volume)
    val volumeText = tr(Res.string.audio_percent, faNumber((controller.effectsVolume.coerceIn(0f, 1f) * 100).toInt()))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FText(tr(Res.string.effects_title), size = 17, weight = FontWeight.Bold)
                FText(tr(if (controller.effectsSupported) Res.string.effects_description else Res.string.effects_unavailable), size = 11, color = Muted)
            }
            Switch(checked = controller.effectsEnabled, onCheckedChange = controller::updateEffectsEnabled,
                enabled = controller.effectsSupported, modifier = Modifier.semantics { contentDescription = enableLabel })
        }
        if (controller.effectsEnabled) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FText(volumeLabel, Modifier.weight(1f), size = 12)
                FText(volumeText, size = 12, color = Gold)
            }
            Slider(value = controller.effectsVolume.coerceIn(0f, 1f), onValueChange = controller::updateEffectsVolume,
                valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().semantics { contentDescription = volumeLabel; stateDescription = volumeText })
        }
    }
}

@Composable private fun MusicControls(controller: GameController) {
    val enableLabel = tr(Res.string.music_enable)
    val volumeLabel = tr(Res.string.music_volume)
    val volumeText = tr(Res.string.audio_percent, faNumber((controller.musicVolume.coerceIn(0f, 1f) * 100).toInt()))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Gold.copy(alpha = if (controller.musicEnabled) .12f else .05f)), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(25.dp)) {
                    val tint = if (controller.musicEnabled) Gold else Muted
                    fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(size.width * x, size.height * y)
                    drawCircle(tint, size.width * .13f, point(.22f, .77f))
                    drawCircle(tint, size.width * .13f, point(.76f, .65f))
                    drawLine(tint, point(.33f, .76f), point(.33f, .24f), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(tint, point(.87f, .64f), point(.87f, .12f), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(tint, point(.33f, .24f), point(.87f, .12f), 3.dp.toPx(), StrokeCap.Round)
                }
            }
            Column(Modifier.weight(1f)) {
                FText(tr(Res.string.music_title), size = 17, weight = FontWeight.Bold)
                FText(tr(when {
                    !controller.musicSupported -> Res.string.music_unavailable
                    !controller.musicEnabled -> Res.string.music_off
                    controller.musicError != null -> Res.string.music_stopped
                    controller.musicEnabled && controller.paused -> Res.string.music_paused
                    controller.musicVolume == 0f -> Res.string.music_zero_volume
                    controller.musicPlaying -> Res.string.music_ready
                    controller.musicPending -> Res.string.music_pending
                    else -> Res.string.music_standby
                }), size = 11, color = Muted)
            }
            Switch(checked = controller.musicEnabled, onCheckedChange = controller::updateMusicEnabled,
                enabled = controller.musicSupported, modifier = Modifier.semantics { contentDescription = enableLabel })
        }
        if (controller.musicEnabled) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FText(volumeLabel, Modifier.weight(1f), size = 12)
                FText(volumeText, size = 12, color = Gold)
            }
            Slider(value = controller.musicVolume.coerceIn(0f, 1f), onValueChange = controller::updateMusicVolume,
                valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().semantics { contentDescription = volumeLabel; stateDescription = volumeText })
        }
        controller.musicError?.let { error ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Gold.copy(alpha = .07f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FText(tr(if (error == "MUSIC_BLOCKED") Res.string.music_blocked else Res.string.music_failed), size = 12)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(controller::clearMusicError, contentPadding = PaddingValues(horizontal = 8.dp)) { FText(tr(Res.string.audio_dismiss), size = 11, color = Muted) }
                    TextButton({ controller.updateMusicEnabled(true) }, enabled = controller.musicSupported, contentPadding = PaddingValues(horizontal = 8.dp)) { FText(tr(Res.string.music_retry), size = 12, color = Gold) }
                }
            }
        }
    }
}

@Composable private fun GameAudioButtons(controller: GameController, openAudio: () -> Unit, modifier: Modifier = Modifier) {
    val toggleLabel = tr(if (controller.audioEnabled) Res.string.audio_disable else Res.string.audio_enable)
    val settingsLabel = tr(Res.string.music_audio_settings)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { controller.updateAudioEnabled(!controller.audioEnabled) }, enabled = controller.audioSupported, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.semantics { contentDescription = toggleLabel }) {
            FText(tr(if (controller.audioEnabled) Res.string.music_speech_on else Res.string.music_speech_off), size = 11, color = if (controller.audioEnabled) Gold else Muted)
        }
        TextButton(openAudio, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.semantics { contentDescription = settingsLabel }) {
            FText(tr(Res.string.audio_settings_short), size = 11, color = if (controller.musicEnabled) Gold else Muted)
        }
    }
}

@Composable private fun AudioNarrationStatus(game: Game, controller: GameController) {
    val narratorId = controller.narratingPlayerId ?: return
    if (!controller.audioEnabled) return
    // This label is based on a public speech, never on the engine's current hidden actor.
    val publicSpeaker = game.players.firstOrNull { player -> player.id == narratorId && game.conversation.any { it.kind == EventKind.SPEECH && it.playerId == player.id } }
    val label = if (game.phase != Phase.NIGHT && publicSpeaker != null) tr(Res.string.audio_narrating, publicSpeaker.character.name) else tr(Res.string.audio_narrating_public)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        VoiceMark(active = true, small = true)
        FText(label, Modifier.weight(1f), size = 11, color = Gold)
    }
}

@Composable private fun AudioErrorNotice(controller: GameController) {
    val code = controller.audioError ?: return
    val message = tr(when (code) {
        "BALANCE" -> Res.string.audio_error_balance
        "AUTH" -> Res.string.audio_error_auth
        "TIMEOUT", "NETWORK", "SDK_UNAVAILABLE", "RATE_LIMIT" -> Res.string.audio_error_connection
        "AUTOPLAY", "PLAYBACK", "AUDIO_PLAYBACK", "AUDIO_BLOCKED", "PLAYBACK_BLOCKED" -> Res.string.audio_error_playback
        else -> Res.string.audio_error_generic
    })
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Gold.copy(alpha = .07f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FText(message, size = 12, color = Paper)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(controller::clearAudioError, contentPadding = PaddingValues(horizontal = 8.dp)) { FText(tr(Res.string.audio_dismiss), size = 11, color = Muted) }
            TextButton(onClick = { controller.clearAudioError(); controller.updateAudioEnabled(true) }, enabled = controller.audioSupported, contentPadding = PaddingValues(horizontal = 8.dp)) { FText(tr(Res.string.audio_reenable), size = 11, color = Gold) }
        }
    }
}

@Composable private fun CharacterCard(profile: CharacterProfile, controller: GameController, modifier: Modifier = Modifier) {
    val model = controller.modelForCharacter(profile.id)
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(14.dp))) {
        Image(painterResource(portrait(profile.id)), profile.name, Modifier.fillMaxWidth().aspectRatio(.91f), contentScale = ContentScale.Crop)
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FText(profile.name, size = 18, weight = FontWeight.Bold)
            FText(profile.subtitle, size = 11, color = Color(profile.color), maxLines = 1)
            if (model != null) FText(model.displayName, Modifier.padding(top = 3.dp), size = 10, color = Muted, maxLines = 1)
        }
    }
}

@Composable private fun Reveal(game: Game, begin: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Chip(tr(Res.string.your_role))
        Image(painterResource(Res.drawable.icon), null, Modifier.size(150.dp).clip(RoundedCornerShape(30.dp)))
        FText(roleName(game.human.role), size = 42, weight = FontWeight.ExtraBold, color = if (game.human.role.isMafiaTeam) Crimson else Gold)
        FText(roleDescription(game.human.role), Modifier.widthIn(max = 480.dp), size = 17, align = TextAlign.Center)
        if (game.human.role.isMafiaTeam && !GameEngine.knowsTeammates(game)) FText(tr(Res.string.teammate_unknown), Modifier.widthIn(max = 480.dp), size = 14, color = Crimson, align = TextAlign.Center)
        if (game.human.role.isMafiaTeam && GameEngine.knowsTeammates(game)) {
            val mate = game.players.single { it.role.isMafiaTeam && !it.isHuman }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(mate.character, 52)
                FText(tr(Res.string.teammate, mate.character.name), color = Crimson)
            }
        }
        FText(tr(Res.string.privacy_note), size = 12, color = Muted)
        GoldButton(tr(Res.string.sit_at_table), begin)
    }
}

@Composable private fun Avatar(profile: CharacterProfile, size: Int = 42, alive: Boolean = true) {
    Image(painterResource(portrait(profile.id)), profile.name, Modifier.size(size.dp).clip(CircleShape).alpha(if (alive) 1f else .35f).border(1.dp, Color(profile.color).copy(alpha = .5f), CircleShape), contentScale = ContentScale.Crop)
}

@Composable private fun GameScreen(game: Game, controller: GameController, compact: Boolean, openNotes: () -> Unit, openAudio: () -> Unit) {
    val actor = GameEngine.actor(game)
    val night = game.phase == Phase.NIGHT
    val narrationId = controller.narratingPlayerId.takeIf { controller.audioEnabled }
    // Text appears before the voice does, so say whether we are generating it or playing it.
    val activity = when {
        controller.speechPlaying -> CardActivity.SPEAKING
        controller.speechPreparing || controller.busy -> CardActivity.WAITING
        else -> CardActivity.NONE
    }
    val defending = game.phase == Phase.DEFENSE || (narrationId != null &&
        game.conversation.lastOrNull { it.kind in listOf(EventKind.SPEECH, EventKind.DEFENSE_SPEECH) }?.kind == EventKind.DEFENSE_SPEECH)
    val discussing = game.phase == Phase.DISCUSSION || (narrationId != null && !night && !defending)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shortScreen = maxHeight < 570.dp
        val scrollPage = maxHeight < 390.dp
        val gameHeight = if (scrollPage) 570.dp else maxHeight
        // A landscape phone or open keyboard must not squeeze the chat to zero.
        Box(Modifier.fillMaxSize().then(if (scrollPage) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Column(Modifier.fillMaxWidth().height(gameHeight), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(tr(if (night) Res.string.night_label else Res.string.day_label, faNumber(game.day)))
                FText(tr(Res.string.alive_label, faNumber(game.living.size)), Modifier.weight(1f), size = 12, color = Muted)
                TextButton(openNotes) { FText(tr(Res.string.private_button), size = 12, color = Gold) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (!compact) TableSidebar(game, narrationId, activity, Modifier.width(220.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    if (compact && !shortScreen) TableStrip(game, narrationId, activity)
                    if (!game.human.isAlive) FText(tr(Res.string.human_eliminated), size = 12, color = Crimson)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            FText(tr(if (defending) Res.string.defense_title else if (discussing) Res.string.discussion else when (game.phase) { Phase.VOTING -> Res.string.voting; Phase.NOMINATION -> Res.string.nomination_title; Phase.DEFENSE -> Res.string.defense_title; Phase.FINAL_VOTING -> Res.string.final_vote_title; Phase.NIGHT -> Res.string.night; Phase.DAWN -> Res.string.dawn; else -> Res.string.discussion }), size = if (compact) 17 else 21, weight = FontWeight.Bold)
                            if (discussing && !compact) FText(tr(passLabel(game.day, game.pass)), size = 11, color = Muted)
                        }
                        if (compact && discussing) FText(tr(passLabel(game.day, game.pass)), size = 10, color = Muted)
                        if (!compact) {
                            GameAudioButtons(controller, openAudio)
                            if (controller.busy && !controller.paused) TextButton(controller::pause, contentPadding = PaddingValues(horizontal = 6.dp)) { FText(tr(Res.string.pause), size = 12, color = Muted) }
                        }
                    }
                    if (compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        GameAudioButtons(controller, openAudio, Modifier.weight(1f))
                        if (controller.busy && !controller.paused) TextButton(controller::pause, contentPadding = PaddingValues(horizontal = 6.dp)) { FText(tr(Res.string.pause), size = 12, color = Muted) }
                    }
                    if (compact && game.phase == Phase.DISCUSSION) {
                        val position = game.discussionPlayers.indexOfFirst { it.isHuman }
                        FText(if (position >= 0) tr(Res.string.discussion_your_order, faNumber(position + 1), faNumber(game.discussionPlayers.size))
                            else tr(Res.string.discussion_order_note), size = 10, color = Muted)
                    }
                    AudioNarrationStatus(game, controller)
                    val outcome = when (game.phase) {
                        Phase.DAWN -> game.conversation.lastOrNull { it.day == game.day && it.kind in listOf(EventKind.NIGHT_SAVED, EventKind.NIGHT_KILLED) }
                        Phase.NIGHT -> game.conversation.lastOrNull { it.day == game.day && it.kind in listOf(EventKind.ELIMINATED, EventKind.VOTE_TIED, EventKind.NO_ELIMINATION) }
                        else -> null
                    }
                    if (outcome != null) FText(eventText(outcome, game), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Gold.copy(alpha = .08f)).padding(12.dp), size = 13,
                        color = if (outcome.kind in listOf(EventKind.NIGHT_KILLED, EventKind.ELIMINATED)) Crimson else Gold)
                    if (controller.localSaveFailed) FText(tr(Res.string.storage_local_failed), size = 11, color = Crimson)
                    HorizontalDivider(color = Line)
                    if (game.phase == Phase.NOMINATION && narrationId == null)
                        NominationTable(game, controller, Modifier.weight(1f), compact)
                    else Conversation(game, Modifier.weight(1f), compact)
                    if (defending) FText(tr(Res.string.defense_now, (narrationId?.let(game::player) ?: actor)?.character?.name.orEmpty()), size = 12, color = Gold)
                    Column(Modifier.fillMaxWidth().heightIn(max = if (shortScreen) 240.dp else 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (controller.audioError != null) AudioErrorNotice(controller)
                        if (controller.error != null) ErrorPanel(controller.error!!, controller::resume)
                        else if (controller.paused) {
                            FText(tr(if (controller.busy) Res.string.pause_pending else Res.string.paused), size = 12, color = Gold)
                            if (!controller.busy) GoldButton(tr(Res.string.resume), controller::resume, Modifier.fillMaxWidth())
                        } else when {
                            // The engine may have advanced, but the public speaker still owns this moment.
                            narrationId != null -> if (controller.speechPreparing) Thinking(
                                tr(Res.string.speech_preparing, game.players.firstOrNull { it.id == narrationId }?.character?.name.orEmpty()))
                            // Night and voting never reveal the identity, role, or progress of a hidden actor.
                            game.phase == Phase.NOMINATION -> Unit
                            controller.busy -> Thinking(if (night) tr(Res.string.night_thinking) else if (game.phase in listOf(Phase.VOTING, Phase.FINAL_VOTING)) tr(Res.string.vote_thinking) else tr(Res.string.thinking, actor?.character?.name.orEmpty()))
                            game.phase == Phase.DAWN -> GoldButton(tr(Res.string.next_day), controller::beginDay, Modifier.fillMaxWidth())
                            actor?.isHuman == true && game.phase in listOf(Phase.DISCUSSION, Phase.DEFENSE) -> SpeechInput(controller, shortScreen)
                            actor?.isHuman == true && (night || game.phase in listOf(Phase.VOTING, Phase.FINAL_VOTING)) -> TargetPicker(game, controller)
                            night -> GoldButton(tr(Res.string.start_night), controller::resume, Modifier.fillMaxWidth())
                            actor != null -> GoldButton(tr(Res.string.resume), controller::resume, Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        }
    }
}

private fun visibleSpeakerId(game: Game, narrationId: String?): String? {
    if (game.phase == Phase.NIGHT) return null
    return narrationId?.takeIf { id -> game.conversation.any { it.kind in listOf(EventKind.SPEECH, EventKind.DEFENSE_SPEECH) && it.playerId == id } }
        ?: if (game.phase in listOf(Phase.DISCUSSION, Phase.DEFENSE)) GameEngine.actor(game)?.id else null
}

private fun visibleTablePlayers(game: Game, narrationId: String?): List<Player> =
    if (game.phase == Phase.DISCUSSION || (game.phase == Phase.VOTING && narrationId != null))
        game.discussionPlayers + game.players.filterNot { it.isAlive }
    else game.players

@Composable private fun TableStrip(game: Game, narrationId: String?, activity: CardActivity) {
    val actorId = visibleSpeakerId(game, narrationId)
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(visibleTablePlayers(game, narrationId), key = { it.id }) { player ->
            val active = player.id == actorId
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
                Box(Modifier.border(1.dp, if (active) Gold else Color.Transparent, CircleShape).padding(3.dp), contentAlignment = Alignment.Center) {
                    Avatar(player.character, 43, player.isAlive)
                    if (active) ActivityBadge(activity, 16)
                }
                FText(player.character.name, size = 11, color = if (active) Gold else if (player.isAlive) Paper else Muted)
                if (!player.isAlive) FText(tr(Res.string.eliminated), size = 9, color = Crimson)
            }
        }
    }
}

@Composable private fun TableSidebar(game: Game, narrationId: String?, activity: CardActivity, modifier: Modifier) {
    val actorId = visibleSpeakerId(game, narrationId)
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FText(tr(if (game.phase == Phase.DISCUSSION) Res.string.discussion_order_title else Res.string.at_table), size = 16, weight = FontWeight.Bold)
        if (game.phase == Phase.DISCUSSION) FText(tr(Res.string.discussion_order_note), size = 10, color = Muted)
        visibleTablePlayers(game, narrationId).forEach { player ->
            val active = player.id == actorId
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) Gold.copy(alpha = .08f) else Color.Transparent).padding(vertical = 6.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    Avatar(player.character, 42, player.isAlive)
                    if (active) ActivityBadge(activity, 16)
                }
                Column(Modifier.weight(1f)) {
                    FText(player.character.name, size = 14, weight = FontWeight.Medium, color = if (active) Gold else if (player.isAlive) Paper else Muted)
                    FText(tr(when {
                        !player.isAlive -> Res.string.eliminated
                        active && activity == CardActivity.SPEAKING -> Res.string.speaking_now
                        active && activity == CardActivity.WAITING -> Res.string.status_preparing
                        active -> Res.string.speaking_now
                        else -> Res.string.hidden_role
                    }), size = 10, color = if (!player.isAlive) Crimson else Muted)
                }
            }
        }
        HorizontalDivider(color = Line)
        FText(tr(Res.string.table_privacy), size = 11, color = Muted)
    }
}

@Composable private fun PrivateNotes(game: Game, notes: String, editNotes: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FText(roleName(game.human.role), color = Gold, size = 22, weight = FontWeight.Bold)
            FText(roleDescription(game.human.role), size = 13)
            if (game.human.role.isMafiaTeam) FText(
                if (GameEngine.knowsTeammates(game)) tr(Res.string.teammate, game.players.single { it.role.isMafiaTeam && !it.isHuman }.character.name)
                else tr(Res.string.teammate_unknown), color = Crimson, size = 13)
            if (game.human.role == Role.DETECTIVE) {
                val history = game.investigations[game.human.id].orEmpty()
                if (history.isEmpty()) FText(tr(Res.string.private_empty), size = 12, color = Muted)
                history.forEach { FText(tr(if (it.isMafia) Res.string.investigation_mafia else Res.string.investigation_town, faNumber(it.night), game.player(it.targetId).character.name), size = 13, color = if (it.isMafia) Crimson else Gold) }
            }
            HorizontalDivider(color = Line)
            OutlinedTextField(notes, { editNotes(it.take(3000)) }, Modifier.fillMaxWidth(), label = { FText(tr(Res.string.notes_label), size = 13) }, placeholder = { FText(tr(Res.string.notes_hint), size = 13, color = Muted) }, minLines = 3, maxLines = 6, textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl, textAlign = TextAlign.Start), shape = RoundedCornerShape(12.dp))
            FText(tr(Res.string.notes_privacy), size = 11, color = Muted)
        }
}

@Composable private fun Conversation(game: Game, modifier: Modifier, compact: Boolean) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followLatest by remember(game.id) { mutableStateOf(true) }
    var automaticScroll by remember(game.id) { mutableStateOf(false) }
    var userScrolling by remember(game.id) { mutableStateOf(false) }
    LaunchedEffect(listState, game.id) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canScrollForward) ->
            if (!automaticScroll) {
                if (scrolling) {
                    userScrolling = true
                    followLatest = !canScrollForward
                } else if (userScrolling) {
                    followLatest = !canScrollForward
                    userScrolling = false
                }
            }
        }
    }
    LaunchedEffect(game.conversation.size, followLatest) {
        if (followLatest && game.conversation.isNotEmpty()) {
            automaticScroll = true
            try { listState.scrollToItem(game.conversation.lastIndex) }
            finally { automaticScroll = false }
        }
    }
    val showLatest by remember {
        derivedStateOf { listState.layoutInfo.totalItemsCount > 0 && listState.canScrollForward }
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 14.dp)) {
            itemsIndexed(game.conversation, key = { index, _ -> "${game.id}:$index" }) { _, message ->
                val player = game.players.firstOrNull { it.id == message.playerId }
                if (player != null && (message.kind in listOf(EventKind.SPEECH, EventKind.SKIP, EventKind.DEFENSE_SPEECH, EventKind.DEFENSE_SKIP))) {
                    val speakerColor = Color(player.character.color)
                    val bubbleShape = RoundedCornerShape(3.dp, 14.dp, 14.dp, 14.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Avatar(player.character, if (compact) 56 else 68)
                        Column(Modifier.weight(1f).clip(bubbleShape)
                            .background(if (player.isHuman) Color(0xFF29251E) else Panel)
                            .border(1.5.dp, speakerColor.copy(alpha = if (player.isHuman) .42f else .58f), bubbleShape)
                            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            FText(player.character.name, size = 14, weight = FontWeight.Bold, color = speakerColor)
                            FText(eventText(message, game), size = 15, color = if (message.kind in listOf(EventKind.SKIP, EventKind.DEFENSE_SKIP)) Muted else Paper)
                        }
                    }
                } else {
                    FText(eventText(message, game), Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), size = 12, color = if (message.kind in listOf(EventKind.NIGHT_KILLED, EventKind.ELIMINATED)) Crimson else Muted, align = TextAlign.Center)
                }
            }
        }
        if (showLatest) {
            FilledTonalButton(onClick = {
                followLatest = true
                scope.launch {
                    automaticScroll = true
                    try { listState.animateScrollToItem(game.conversation.lastIndex) }
                    finally { automaticScroll = false }
                }
            }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = Gold, contentColor = Ink), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                FText(tr(Res.string.latest_messages), size = 12, color = Ink)
            }
        }
    }
}

@Composable private fun SpeechInput(controller: GameController, shortScreen: Boolean) {
    var text by remember(controller.game?.day, controller.game?.pass, controller.game?.phase) { mutableStateOf("") }
    val transcript = controller.pendingTranscript
    // Dictation fills the field the player then edits; it never speaks for them.
    LaunchedEffect(transcript) {
        if (transcript != null) {
            val merged = (if (text.isBlank()) transcript else "$text $transcript").take(1000)
            controller.consumeTranscript()
            if (controller.voiceAutoSend && merged.isNotBlank()) { text = ""; controller.say(merged) } else text = merged
        }
    }
    // Leaving this turn must never leave the microphone open.
    DisposableEffect(Unit) { onDispose { controller.cancelVoiceInput() } }
    val voiceBusy = controller.recording || controller.transcribing
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FText(tr(if (controller.game?.phase == Phase.DEFENSE) Res.string.defense_your_turn else Res.string.your_turn), size = 12, color = Gold)
        OutlinedTextField(text, { text = it.take(1000) }, Modifier.fillMaxWidth(), placeholder = { FText(tr(Res.string.speech_hint), size = 14, color = Muted) }, maxLines = if (shortScreen) 2 else 3, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl, textAlign = TextAlign.Start), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), shape = RoundedCornerShape(12.dp))
        controller.micError?.let { code ->
            FText(tr(voiceErrorLabel(code)), size = 11, color = Crimson, modifier = Modifier.clickable(onClick = controller::dismissMicError))
        }
        if (controller.recording) FText(tr(Res.string.voice_recording), size = 11, color = Gold)
        else if (controller.micSupported && !controller.transcribing) FText(tr(Res.string.voice_cost_note), size = 11, color = Muted)
        if (controller.micSupported && !voiceBusy) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(controller.voiceAutoSend, controller::updateVoiceAutoSend, Modifier.size(26.dp),
                colors = CheckboxDefaults.colors(checkedColor = Gold, uncheckedColor = Muted, checkmarkColor = Ink))
            Spacer(Modifier.width(8.dp))
            FText(tr(Res.string.voice_auto_send), size = 11, color = Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GoldButton(tr(Res.string.speak), { controller.cancelVoiceInput(); controller.say(text); text = "" }, Modifier.weight(1f), enabled = text.isNotBlank() && !voiceBusy)
            if (controller.micSupported) when {
                controller.transcribing -> FText(tr(Res.string.voice_working), size = 12, color = Muted)
                controller.recording -> {
                    TextButton(controller::finishVoiceInput) { FText(tr(Res.string.voice_stop), color = Gold) }
                    TextButton(controller::cancelVoiceInput) { FText(tr(Res.string.voice_cancel), size = 12, color = Muted) }
                }
                else -> TextButton(controller::startVoiceInput) { FText(tr(Res.string.voice_start), color = Gold) }
            }
            if (!voiceBusy) TextButton({ controller.say(""); text = "" }) { FText(tr(Res.string.skip), color = Muted) }
        }
    }
}

private fun voiceErrorLabel(code: String) = when (code) {
    "MIC_DENIED" -> Res.string.voice_error_denied
    "MIC_UNAVAILABLE", "MIC_BUSY", "MIC_IDLE" -> Res.string.voice_error_unavailable
    "MIC_EMPTY" -> Res.string.voice_error_empty
    "MIC_TOO_LONG" -> Res.string.voice_error_long
    else -> Res.string.voice_error_failed
}

/** Public nomination spotlight. Pending AI ballot plans never reach this UI. */
@Composable private fun NominationTable(game: Game, controller: GameController, modifier: Modifier, compact: Boolean) {
    val candidate = game.nominationCandidate ?: return
    val actor = GameEngine.actor(game)
    val ballots = game.nominationVotes.filter { it.candidateId == candidate.id }
    var history by remember(game.id) { mutableStateOf(false) }
    var discussion by remember(game.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FText(tr(Res.string.nomination_progress, faNumber(game.nominationIndex + 1), faNumber(game.nominationOrder.size)), size = 12, color = Muted)
        FText(tr(Res.string.nomination_help), size = 12, color = Muted, align = TextAlign.Center)
        Image(painterResource(portrait(candidate.character.id)), candidate.character.name,
            Modifier.size(if (compact) 132.dp else 184.dp).clip(RoundedCornerShape(22.dp))
                .border(1.dp, Gold.copy(alpha = .55f), RoundedCornerShape(22.dp)), contentScale = ContentScale.Crop)
        FText(tr(Res.string.nomination_question, candidate.character.name), size = 20, weight = FontWeight.Bold, align = TextAlign.Center)
        if (controller.error == null && !controller.paused) {
            if (actor?.isHuman == true && !controller.busy) Row(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GoldButton(tr(Res.string.nomination_yes_button), { controller.nominate(true) }, Modifier.weight(1f))
                OutlinedButton({ controller.nominate(false) }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    FText(tr(Res.string.nomination_no_button), color = Paper)
                }
            } else if (actor != null) {
                FText(tr(Res.string.nomination_voter, actor.character.name), size = 13, color = Gold)
                if (!controller.busy) TextButton(controller::resume) { FText(tr(Res.string.resume), color = Gold) }
            }
        }
        FText(tr(Res.string.nomination_tally, faNumber(ballots.count { it.approved }), faNumber(ballots.size), faNumber(game.living.size - 1)), size = 13, color = Gold)
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            game.ballotOrder.filter { it.id != candidate.id }.forEach { voter ->
                val vote = ballots.firstOrNull { it.voterId == voter.id }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(voter.character, 32)
                    FText(voter.character.name, Modifier.weight(1f), size = 13)
                    FText(tr(when (vote?.approved) { true -> Res.string.nomination_yes_label; false -> Res.string.nomination_no_label; null -> Res.string.nomination_pending }),
                        size = 12, color = if (vote?.approved == true) Gold else Muted)
                }
            }
        }
        TextButton({ history = !history }) { FText(tr(if (history) Res.string.nomination_hide_history else Res.string.nomination_show_history), size = 12, color = Gold) }
        TextButton({ discussion = !discussion }) { FText(tr(if (discussion) Res.string.nomination_hide_discussion else Res.string.nomination_show_discussion), size = 12, color = Gold) }
        if (discussion) game.conversation.filter { it.day == game.day && it.kind in listOf(EventKind.SPEECH, EventKind.SKIP) }.forEach { message ->
            val speaker = game.player(requireNotNull(message.playerId))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Avatar(speaker.character, 36)
                Column(Modifier.weight(1f)) {
                    FText(speaker.character.name, size = 12, color = Gold)
                    FText(eventText(message, game), size = 13)
                }
            }
        }
        if (history) game.nominationOrder.take(game.nominationIndex).forEach { id ->
            val votes = game.nominationVotes.filter { it.candidateId == id }
            FText(tr(Res.string.nomination_previous, game.player(id).character.name, faNumber(votes.count { it.approved })), weight = FontWeight.Bold, size = 14)
            votes.forEach { vote -> FText(tr(if (vote.approved) Res.string.nomination_yes_event else Res.string.nomination_no_event,
                game.player(vote.voterId).character.name, game.player(id).character.name), size = 12, color = Muted) }
        }
    }
}

@Composable private fun TargetPicker(game: Game, controller: GameController) {
    var selected by remember(game.day, game.phase) { mutableStateOf<String?>(null) }
    val voting = game.phase in listOf(Phase.VOTING, Phase.FINAL_VOTING)
    val targetScroll = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FText(tr(if (voting) Res.string.vote_prompt else when { game.human.role.isMafiaTeam -> Res.string.kill_prompt; game.human.role == Role.DOCTOR -> Res.string.protect_prompt; else -> Res.string.investigate_prompt }), size = 17, color = Gold, weight = FontWeight.Bold)
        FText(tr(if (game.phase == Phase.FINAL_VOTING) Res.string.final_vote_help else if (voting) Res.string.vote_help else Res.string.night_private), size = 11, color = Muted)
        if (targetScroll.canScrollBackward || targetScroll.canScrollForward)
            FText(tr(Res.string.target_swipe_hint), size = 11, color = Muted)
        LazyRow(Modifier.fillMaxWidth().selectableGroup(), state = targetScroll, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(GameEngine.legalTargets(game, game.human.id), key = { it }) { id ->
                val player = game.player(id)
                val chosen = selected == id
                val label = tr(Res.string.target_select, player.character.name)
                Column(Modifier.width(104.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (chosen) Gold.copy(alpha = .14f) else Panel)
                    .border(if (chosen) 2.dp else 1.dp, if (chosen) Gold else Line, RoundedCornerShape(16.dp))
                    .selectable(selected = chosen, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { selected = id })
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Image(painterResource(portrait(player.character.id)), null,
                        Modifier.size(84.dp).clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
                    FText(player.character.name, size = 14, color = if (chosen) Gold else Paper,
                        weight = FontWeight.Bold, maxLines = 1, align = TextAlign.Center)
                    FText(if (chosen) tr(Res.string.target_selected) else " ", size = 10, color = Gold)
                }
            }
        }
        if (game.phase == Phase.FINAL_VOTING) TextButton({ controller.choose(GameEngine.ABSTAIN) }, Modifier.fillMaxWidth()) {
            FText(tr(Res.string.final_abstain_button), size = 14, color = Muted)
        }
        GoldButton(if (selected == null) tr(if (voting) Res.string.vote_prompt else Res.string.select_player) else tr(if (voting) Res.string.confirm_vote else Res.string.confirm_night, game.player(selected!!).character.name), { selected?.let(controller::choose) }, Modifier.fillMaxWidth(), enabled = selected != null)
    }
}

/** Round one opens the day; round two is where the table answers what round one raised. */
private fun passLabel(day: Int, pass: Int) =
    if (day == 1) Res.string.pass_label_intro
    else if (pass <= 1) Res.string.pass_label_open else Res.string.pass_label_answer

/** What the table shows above a portrait while everyone waits on that player. */
private enum class CardActivity { NONE, WAITING, SPEAKING }

/** Three bars that move only while audio is actually playing. */
@Composable private fun SpeakingBars(size: Int) {
    val transition = rememberInfiniteTransition()
    val heights = List(3) { index ->
        transition.animateFloat(0.3f, 1f, infiniteRepeatable(
            tween(430, delayMillis = index * 150, easing = LinearEasing), RepeatMode.Reverse))
    }
    Canvas(Modifier.size(size.dp)) {
        val bar = this.size.width / 5f
        heights.forEachIndexed { index, height ->
            val tall = this.size.height * height.value
            drawRoundRect(Gold, Offset(index * bar * 2f + bar / 2f, (this.size.height - tall) / 2f),
                Size(bar, tall), CornerRadius(bar / 2f))
        }
    }
}

@Composable private fun ActivityBadge(activity: CardActivity, size: Int) {
    if (activity == CardActivity.NONE) return
    Box(Modifier.size((size + 8).dp).clip(CircleShape).background(Ink.copy(alpha = .78f)), contentAlignment = Alignment.Center) {
        if (activity == CardActivity.SPEAKING) SpeakingBars(size)
        else CircularProgressIndicator(Modifier.size(size.dp), color = Gold, strokeWidth = 2.dp)
    }
}

@Composable private fun Thinking(message: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Gold, strokeWidth = 2.dp)
        FText(message, Modifier.weight(1f), size = 13, color = Muted)
    }
}
@Composable private fun ErrorPanel(code: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF30201F)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        FText(errorText(code), size = 13, color = Paper)
        TextButton(retry) { FText(tr(Res.string.retry), color = Gold) }
    }
}
@Composable private fun Results(game: Game, lobby: () -> Unit) {
    val won = game.human.role.isMafiaTeam == (game.winner == Team.MAFIA)
    val totalCost = game.usage.takeIf { it.isNotEmpty() && it.all { usage -> usage.estimatedCost != null } }
        ?.sumOf { requireNotNull(it.estimatedCost) }
    LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
        item { Image(painterResource(Res.drawable.icon), null, Modifier.size(100.dp).clip(RoundedCornerShape(22.dp))) }
        item { FText(tr(if (game.winner == Team.TOWN) Res.string.town_wins else Res.string.mafia_wins), size = 31, color = if (game.winner == Team.TOWN) Gold else Crimson, weight = FontWeight.Bold, align = TextAlign.Center) }
        item { Chip(tr(if (won) Res.string.you_won else Res.string.you_lost)) }
        item { FText(tr(Res.string.reveal_all), color = Muted) }
        items(game.players) { player ->
            Row(Modifier.widthIn(max = 500.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(player.character, 48, player.isAlive)
                FText(player.character.name, Modifier.weight(1f), weight = FontWeight.Bold)
                FText(roleName(player.role), color = if (player.role.isMafiaTeam) Crimson else Gold)
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                GoldButton(tr(Res.string.play_again), lobby)
                totalCost?.let { cost ->
                    val amount = "\u2066${gameCostAmount(cost)}\u2069"
                    FText("\u202B${tr(Res.string.game_total_cost, amount)}\u202C", size = 11, color = Muted)
                }
            }
        }
    }
}
