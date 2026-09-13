package ir.iact.mafiagame

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import ir.iact.mafiagame.ai.BrowserGateway

internal expect fun bridgeStart(operation: String, payload: String): Int
internal expect fun bridgePoll(id: Int): String?
internal expect fun bridgeCancel(id: Int)
internal expect fun bridgeAudioControl(action: String, value: Double)
internal expect fun browserReady()
internal expect fun tutorialWelcomeSeen(): Boolean
internal expect fun rememberTutorialWelcome()
internal expect fun savedPlayerName(): String
internal expect fun savePlayerName(name: String)
internal expect fun readLocalSave(): String
internal expect fun writeLocalSave(value: String): Boolean
internal expect fun fastPacingEnabled(): Boolean
internal expect fun setActiveGameId(gameId: String)
internal expect fun consumeAuthResumeGameId(): String

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "webApp") {
        val initialSave = remember { ir.iact.mafiagame.domain.SavedApp.parse(readLocalSave()) }
        val authResumeGameId = remember { consumeAuthResumeGameId() }
        val gateway = remember {
            BrowserGateway(
                ::bridgeStart,
                ::bridgePoll,
                ::bridgeCancel,
                ::bridgeAudioControl,
                ::writeLocalSave,
                activeGameControl = { setActiveGameId(it.orEmpty()) },
                fastPacing = fastPacingEnabled(),
            )
        }
        // A mobile OAuth return belongs to the game that was open before the
        // full-page redirect. Never cover that restored table with onboarding.
        val showWelcome = remember { authResumeGameId.isEmpty() && !tutorialWelcomeSeen() }
        val playerName = remember { savedPlayerName() }
        App(gateway, showWelcomeOnLaunch = showWelcome, onWelcomeDismissed = ::rememberTutorialWelcome,
            initialPlayerName = playerName, onPlayerNameSaved = ::savePlayerName,
            initialSave = initialSave,
            resumeSavedGameOnLaunch = authResumeGameId.isNotEmpty() && initialSave?.game?.id == authResumeGameId)
        LaunchedEffect(Unit) {
            withFrameNanos { }
            browserReady()
        }
    }
}
