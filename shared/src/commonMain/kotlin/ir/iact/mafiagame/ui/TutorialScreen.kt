package ir.iact.mafiagame.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.iact.mafiagame.domain.Characters
import mafiagame.shared.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource

/** All lessons and decisions are bundled locally; opening this screen never calls AI Pass. */
enum class TutorialTrack { BASICS, PRACTICE, STRATEGY }

data class TutorialAudioState(
    val supported: Boolean = false,
    val ready: Boolean = false,
    val enabled: Boolean = false,
    val volumeMuted: Boolean = false,
    val clipId: String? = null,
    val playing: Boolean = false,
    val pending: Boolean = false,
    val error: String? = null,
)

private val TutorialInk = Color(0xFF10120F)
private val TutorialPanel = Color(0xFF1C201B)
private val TutorialPaper = Color(0xFFF1EBDF)
private val TutorialMuted = Color(0xFFABAFA3)
private val TutorialGold = Color(0xFFE0B77A)
private val TutorialTeal = Color(0xFF94D3BA)
private val TutorialLine = Color(0xFF353D32)

@Composable
fun TutorialScreen(
    onClose: () -> Unit,
    onPlay: () -> Unit,
    initialTrack: TutorialTrack = TutorialTrack.BASICS,
    audio: TutorialAudioState = TutorialAudioState(),
    onNarrate: (String) -> Unit = {},
    onStopNarration: () -> Unit = {},
    onOpenAudio: () -> Unit = {},
) {
    var track by remember { mutableStateOf(initialTrack) }
    val positions = remember { mutableStateMapOf<TutorialTrack, Int>() }
    val answers = remember { mutableStateMapOf<String, Int>() }
    val index = positions[track] ?: 0
    val scenarios = if (track == TutorialTrack.PRACTICE) practiceScenarios else strategyScenarios
    val count = if (track == TutorialTrack.BASICS) basicLessons.size else scenarios.size
    val finished = index >= count
    val lesson = if (track == TutorialTrack.BASICS) basicLessons.getOrNull(index) else null
    val scenario = if (track != TutorialTrack.BASICS) scenarios.getOrNull(index) else null
    val selectedAnswer = scenario?.let { answers[it.id] }
    val narrationId = when {
        finished -> "complete_${track.name.lowercase()}"
        lesson != null -> "basic_${lesson.id}"
        scenario != null -> "${if (track == TutorialTrack.PRACTICE) "practice" else "strategy"}_${scenario.id}_${selectedAnswer?.let { "feedback_$it" } ?: "intro"}"
        else -> null
    }
    val narrate by rememberUpdatedState(onNarrate)
    val stopNarration by rememberUpdatedState(onStopNarration)
    LaunchedEffect(narrationId, audio.supported, audio.ready, audio.enabled, audio.volumeMuted) {
        if (narrationId != null && audio.supported && audio.ready && audio.enabled && !audio.volumeMuted) narrate(narrationId)
        else stopNarration()
    }
    DisposableEffect(Unit) { onDispose { stopNarration() } }
    val closeTutorial = { stopNarration(); onClose() }
    val playGame = { stopNarration(); onPlay() }
    val art = lesson?.art ?: scenario?.art ?: TutorialArt.TABLE
    val scroll = rememberScrollState()
    LaunchedEffect(track, index) { scroll.scrollTo(0) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(Modifier.fillMaxSize(), color = TutorialInk) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth < 760.dp
                val short = maxHeight < 460.dp
                Column(Modifier.widthIn(max = 1120.dp).fillMaxSize().align(Alignment.TopCenter).padding(horizontal = if (compact) 14.dp else 28.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = if (short) 2.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            TutorialText(tr(Res.string.tutorial_title), size = if (compact) 21 else 26, weight = FontWeight.Bold)
                        }
                        TextButton(onOpenAudio, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                            TutorialText(tr(Res.string.tutorial_audio_menu), size = 12, color = TutorialGold)
                        }
                        TextButton(closeTutorial, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            TutorialText(tr(if (compact) Res.string.tutorial_back_short else Res.string.tutorial_back_table), size = 12, color = TutorialGold)
                        }
                    }
                    TutorialTracks(track) { track = it }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TutorialText(if (finished) tr(Res.string.tutorial_track_complete) else tr(Res.string.tutorial_step_count,
                            faNumber((index + 1).coerceAtMost(count)), faNumber(count)), size = 11, color = TutorialMuted)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(count) { dot ->
                                Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if (dot <= index) TutorialTeal else TutorialLine))
                            }
                        }
                    }
                    if (audio.supported) {
                        TutorialPlaybackControls(audio, narrationId, onReplay = { narrationId?.let(narrate) }, onStop = stopNarration)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (!compact && !short) {
                            Column(Modifier.weight(.85f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                TutorialIllustration(art, Modifier.fillMaxWidth(), compact = false)
                                TutorialText(tr(trackDescription(track)), size = 17, color = TutorialMuted)
                                if (scenario != null) TutorialText(tr(Res.string.tutorial_scripted_note), size = 12, color = TutorialTeal)
                            }
                        }
                        Column(Modifier.weight(1.25f).fillMaxHeight().verticalScroll(scroll).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (compact && !short) TutorialIllustration(art, Modifier.fillMaxWidth(), compact = true)
                            when {
                                finished -> TutorialCompletion(track, playGame, onNextTrack = {
                                    track = if (track == TutorialTrack.BASICS) TutorialTrack.PRACTICE else TutorialTrack.STRATEGY
                                }, onRestart = {
                                    if (track != TutorialTrack.BASICS) scenarios.forEach { answers.remove(it.id) }
                                    positions[track] = 0
                                })
                                lesson != null -> TutorialLessonCard(lesson)
                                scenario != null -> TutorialScenarioCard(scenario, selectedAnswer,
                                    onAnswer = { answers[scenario.id] = it },
                                    onRetry = { answers.remove(scenario.id) })
                            }
                        }
                    }
                    if (scroll.canScrollForward) TutorialText(tr(Res.string.tutorial_scroll_hint),
                        Modifier.fillMaxWidth().padding(bottom = 6.dp), size = 12, color = TutorialTeal, align = TextAlign.Center)
                    HorizontalDivider(color = TutorialLine)
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            if (index > 0) positions[track] = index - 1
                        }, enabled = index > 0, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                            TutorialText(tr(Res.string.tutorial_previous), size = 14, color = if (index > 0) TutorialMuted else TutorialLine)
                        }
                        Spacer(Modifier.weight(1f))
                        if (!finished) TutorialButton(tr(if (index == count - 1) Res.string.tutorial_finish_track else Res.string.tutorial_next),
                            onClick = { positions[track] = index + 1 },
                            enabled = scenario == null || selectedAnswer != null)
                        else TextButton(closeTutorial, modifier = Modifier.heightIn(min = 48.dp)) {
                            TutorialText(tr(Res.string.tutorial_back_table), size = 14, color = TutorialGold)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun TutorialPlaybackControls(audio: TutorialAudioState, clipId: String?, onReplay: () -> Unit, onStop: () -> Unit) {
    val current = clipId != null && audio.clipId == clipId
    val playing = current && audio.playing
    val pending = current && audio.pending
    val error = audio.error.takeIf { current }
    val status = when {
        !audio.supported -> Res.string.tutorial_audio_unavailable
        !audio.ready -> Res.string.tutorial_audio_loading
        !audio.enabled -> Res.string.tutorial_audio_muted
        audio.volumeMuted -> Res.string.tutorial_audio_zero_volume
        error != null -> Res.string.tutorial_audio_failed
        playing -> Res.string.tutorial_audio_playing
        pending -> Res.string.tutorial_audio_loading
        else -> null
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TutorialPanel)
        .border(1.dp, TutorialLine, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status != null) TutorialText(tr(status), Modifier.weight(1f), size = 11, color = if (playing) TutorialTeal else TutorialMuted)
        else Spacer(Modifier.weight(1f))
        TextButton(onReplay, enabled = audio.supported && audio.ready && audio.enabled && !audio.volumeMuted,
            contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            TutorialText(tr(if (error != null) Res.string.tutorial_audio_retry else Res.string.tutorial_audio_replay), size = 12, color = TutorialGold)
        }
        TextButton(onStop, enabled = playing || pending, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            TutorialText(tr(Res.string.tutorial_audio_stop), size = 12, color = if (playing || pending) TutorialMuted else TutorialLine)
        }
    }
}

@Composable private fun TutorialTracks(selectedTrack: TutorialTrack, onSelect: (TutorialTrack) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TutorialPanel).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TutorialTrack.entries.forEach { track ->
            val active = track == selectedTrack
            Box(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(10.dp))
                .background(if (active) TutorialGold.copy(alpha = .13f) else Color.Transparent)
                .border(1.dp, if (active) TutorialGold.copy(alpha = .35f) else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable(role = Role.Tab) { onSelect(track) }.semantics { selected = active }
                .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                TutorialText(tr(trackTitle(track)), size = 14, color = if (active) TutorialGold else TutorialMuted,
                    weight = if (active) FontWeight.Bold else FontWeight.Normal, align = TextAlign.Center)
            }
        }
    }
}

@Composable private fun TutorialIllustration(art: TutorialArt, modifier: Modifier, compact: Boolean) {
    Box(modifier.aspectRatio(if (compact) 1.7f else 1.5f).clip(RoundedCornerShape(20.dp))
        .border(1.dp, TutorialGold.copy(alpha = .22f), RoundedCornerShape(20.dp))) {
        Image(painterResource(artResource(art)), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)))))
        TutorialText(tr(artCaption(art)), Modifier.align(Alignment.BottomStart).padding(16.dp), size = if (compact) 14 else 17,
            weight = FontWeight.Bold, color = TutorialPaper)
    }
}

@Composable private fun TutorialLessonCard(lesson: TutorialLesson) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(TutorialPanel)
        .border(1.dp, TutorialLine, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TutorialText(tr(lesson.title), Modifier.semantics { heading() }, size = 25, color = TutorialGold, weight = FontWeight.Bold)
        TutorialText(tr(lesson.body), size = 18)
    }
    TutorialText(tr(Res.string.tutorial_basics_hint), size = 12, color = TutorialMuted)
}

@Composable private fun TutorialScenarioCard(scenario: TutorialScenario, selectedAnswer: Int?, onAnswer: (Int) -> Unit, onRetry: () -> Unit) {
    TutorialText(tr(scenario.title), Modifier.semantics { heading() }, size = 25, color = TutorialGold, weight = FontWeight.Bold)
    TutorialText(tr(scenario.prompt), size = 17)
    if (scenario.messages.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(TutorialPanel)
            .border(1.dp, TutorialLine, RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TutorialText(tr(Res.string.tutorial_sample_chat), size = 11, color = TutorialMuted)
            scenario.messages.forEach { message -> TutorialChatBubble(message) }
        }
    }
    TutorialText(tr(Res.string.tutorial_choose), size = 13, color = TutorialTeal, weight = FontWeight.Bold)
    scenario.choices.forEachIndexed { index, choice ->
        val chosen = selectedAnswer == index
        val accent = if (choice.isRecommended) TutorialTeal else TutorialGold
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (chosen) accent.copy(alpha = .08f) else TutorialPanel)
            .border(1.dp, if (chosen) accent.copy(alpha = .65f) else TutorialLine, RoundedCornerShape(14.dp)),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton) { onAnswer(index) }
                .semantics { selected = chosen }.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(if (chosen) accent.copy(alpha = .2f) else TutorialLine), contentAlignment = Alignment.Center) {
                    TutorialText(faNumber(index + 1), size = 13, color = if (chosen) accent else TutorialMuted)
                }
                TutorialText(tr(choice.label), Modifier.weight(1f), size = 17, color = if (chosen) TutorialPaper else TutorialMuted)
            }
            if (chosen) {
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = accent.copy(alpha = .2f))
                Column(Modifier.padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TutorialText(tr(if (choice.isRecommended) Res.string.tutorial_good_choice else Res.string.tutorial_reconsider), size = 13, color = accent, weight = FontWeight.Bold)
                    TutorialText(tr(choice.feedback), size = 17)
                }
            }
        }
    }
    if (selectedAnswer != null) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TutorialTeal.copy(alpha = .07f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TutorialText(tr(Res.string.tutorial_takeaway), size = 12, color = TutorialTeal, weight = FontWeight.Bold)
            TutorialText(tr(scenario.takeaway), size = 17)
        }
        TextButton(onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            TutorialText(tr(Res.string.tutorial_retry), size = 13, color = TutorialGold)
        }
    } else TutorialText(tr(Res.string.tutorial_no_wrong_pressure), size = 12, color = TutorialMuted)
}

@Composable private fun TutorialChatBubble(message: TutorialChat) {
    val profile = Characters.all.firstOrNull { it.id == message.characterId } ?: Characters.human
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Image(painterResource(portrait(profile.id)), null, Modifier.size(38.dp).clip(CircleShape)
            .border(1.dp, Color(profile.color).copy(alpha = .5f), CircleShape), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TutorialText(profile.name, size = 12, color = Color(profile.color), weight = FontWeight.Bold)
            TutorialText(tr(message.text), size = 17)
        }
    }
}

@Composable private fun TutorialCompletion(track: TutorialTrack, onPlay: () -> Unit, onNextTrack: () -> Unit, onRestart: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .background(Brush.verticalGradient(listOf(TutorialTeal.copy(alpha = .11f), TutorialPanel)))
        .border(1.dp, TutorialTeal.copy(alpha = .3f), RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        TutorialText(tr(Res.string.tutorial_complete_kicker), size = 12, color = TutorialTeal)
        TutorialText(tr(when (track) {
            TutorialTrack.BASICS -> Res.string.tutorial_basics_complete_title
            TutorialTrack.PRACTICE -> Res.string.tutorial_practice_complete_title
            TutorialTrack.STRATEGY -> Res.string.tutorial_strategy_complete_title
        }), Modifier.semantics { heading() }, size = 26, weight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Characters.all.forEach { profile ->
                Image(painterResource(portrait(profile.id)), null, Modifier.weight(1f).aspectRatio(1f).clip(CircleShape), contentScale = ContentScale.Crop)
            }
        }
        TutorialText(tr(when (track) {
            TutorialTrack.BASICS -> Res.string.tutorial_basics_complete_body
            TutorialTrack.PRACTICE -> Res.string.tutorial_practice_complete_body
            TutorialTrack.STRATEGY -> Res.string.tutorial_strategy_complete_body
        }), size = 17)
        if (track != TutorialTrack.STRATEGY) TutorialButton(tr(if (track == TutorialTrack.BASICS) Res.string.tutorial_try_practice else Res.string.tutorial_try_strategy), onNextTrack, Modifier.fillMaxWidth())
        TutorialButton(tr(Res.string.tutorial_play), onPlay, Modifier.fillMaxWidth(), secondary = track != TutorialTrack.STRATEGY)
        TextButton(onRestart, modifier = Modifier.heightIn(min = 48.dp)) {
            TutorialText(tr(Res.string.tutorial_restart), size = 13, color = TutorialGold)
        }
    }
}

@Composable private fun TutorialButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, secondary: Boolean = false) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (secondary) TutorialPanel else TutorialGold,
            contentColor = if (secondary) TutorialGold else TutorialInk),
        border = if (secondary) BorderStroke(1.dp, TutorialGold.copy(alpha = .35f)) else null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp)) {
        TutorialText(label, size = 14, color = if (!enabled) TutorialMuted else if (secondary) TutorialGold else TutorialInk, weight = FontWeight.Bold, align = TextAlign.Center)
    }
}

@Composable private fun TutorialText(text: String, modifier: Modifier = Modifier, size: Int = 17, color: Color = TutorialPaper,
    weight: FontWeight = FontWeight.Normal, align: TextAlign = TextAlign.Start) {
    Text(rtl(text), modifier, color = color, fontSize = size.sp, lineHeight = (size * 1.7f).sp,
        fontWeight = weight, textAlign = align, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl))
}

private fun trackTitle(track: TutorialTrack): StringResource = when (track) {
    TutorialTrack.BASICS -> Res.string.tutorial_track_basics
    TutorialTrack.PRACTICE -> Res.string.tutorial_track_practice
    TutorialTrack.STRATEGY -> Res.string.tutorial_track_strategy
}
private fun trackDescription(track: TutorialTrack): StringResource = when (track) {
    TutorialTrack.BASICS -> Res.string.tutorial_basics_description
    TutorialTrack.PRACTICE -> Res.string.tutorial_practice_description
    TutorialTrack.STRATEGY -> Res.string.tutorial_strategy_description
}
private fun artResource(art: TutorialArt): DrawableResource = when (art) {
    TutorialArt.TABLE -> Res.drawable.tutorial_table
    TutorialArt.ROLES -> Res.drawable.tutorial_roles
    TutorialArt.CYCLE -> Res.drawable.tutorial_cycle
    TutorialArt.CLUES -> Res.drawable.tutorial_clues
}
private fun artCaption(art: TutorialArt): StringResource = when (art) {
    TutorialArt.TABLE -> Res.string.tutorial_art_table
    TutorialArt.ROLES -> Res.string.tutorial_art_roles
    TutorialArt.CYCLE -> Res.string.tutorial_art_cycle
    TutorialArt.CLUES -> Res.string.tutorial_art_clues
}
