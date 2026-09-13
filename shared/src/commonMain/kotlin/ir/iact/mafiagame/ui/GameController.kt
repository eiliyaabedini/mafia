package ir.iact.mafiagame.ui

import androidx.compose.runtime.*
import ir.iact.mafiagame.ai.*
import ir.iact.mafiagame.domain.*
import kotlinx.coroutines.*

private const val LAST_BALLOT_HOLD_MS = 2_800L

class GameController(private val gateway: AiGateway, private val scope: CoroutineScope,
    initialPlayerName: String = "", private val rememberPlayerName: (String) -> Unit = {}, initialSave: SavedApp? = null,
    resumeInitialGame: Boolean = false) {
    var playerName by mutableStateOf(PlayerNames.normalize(initialSave?.playerName ?: initialPlayerName).takeIf { PlayerNames.valid(it) }.orEmpty()); private set
    var savedGame by mutableStateOf(initialSave?.game); private set
    var notes by mutableStateOf(initialSave?.notes.orEmpty()); private set
    var storageBusy by mutableStateOf(false); private set
    var storageMessage by mutableStateOf<String?>(null); private set
    var localSaveFailed by mutableStateOf(false); private set
    private var storageJob: Job? = null
    private var restoringState = true
    fun updatePlayerName(value: String) {
        if (game != null || busy || storageBusy || !PlayerNames.valid(value)) return
        playerName = PlayerNames.normalize(value)
        rememberPlayerName(playerName)
        persist()
    }
    var game by mutableStateOf(if (resumeInitialGame) initialSave?.game else null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var paused by mutableStateOf(resumeInitialGame && initialSave?.game != null); private set
    var loaded by mutableStateOf(false); private set
    var audioEnabled by mutableStateOf(gateway.audioAvailable); private set
    var audioVolume by mutableStateOf(0.8f); private set
    var narratingPlayerId by mutableStateOf<String?>(null); private set
    var audioError by mutableStateOf<String?>(null); private set
    var audioPausedAfterError by mutableStateOf(false); private set
    var audioPreferenceReady by mutableStateOf(!gateway.audioAvailable); private set
    var mediaStatus by mutableStateOf(MediaStatus(enabled = gateway.audioAvailable)); private set
    var tutorialClipId by mutableStateOf<String?>(null); private set
    var tutorialLoading by mutableStateOf(false); private set
    var tutorialError by mutableStateOf<String?>(null); private set
    var musicEnabled by mutableStateOf(gateway.musicAvailable); private set
    var musicVolume by mutableStateOf(0.25f); private set
    var musicError by mutableStateOf<String?>(null); private set
    var musicPlaying by mutableStateOf(false); private set
    var musicPending by mutableStateOf(false); private set
    var effectsEnabled by mutableStateOf(gateway.effectsAvailable); private set
    var effectsVolume by mutableStateOf(0.55f); private set
    /** Keeps the completed ballot on its original screen while the committed engine state waits underneath. */
    var ballotHold by mutableStateOf<Game?>(null); private set
    val presentedGame get() = ballotHold ?: game
    var walletStatus by mutableStateOf(WalletStatus()); private set
    var walletRefreshing by mutableStateOf(false); private set
    var walletOpening by mutableStateOf(false); private set
    val supported get() = gateway.available
    val audioSupported get() = gateway.audioAvailable
    val musicSupported get() = gateway.musicAvailable
    val effectsSupported get() = gateway.effectsAvailable
    val walletSupported get() = gateway.walletAvailable
    private var walletJob: Job? = null
    private var walletOpenJob: Job? = null
    private var walletMonitor: Job? = null
    private var musicMonitor: Job? = null
    private var mediaMonitor: Job? = null
    private var tutorialJob: Job? = null
    private var effectsMonitor: Job? = null
    private val effectsJobs = mutableSetOf<Job>()
    private var effectsRevision = 0
    private var effectsEpoch = 0
    private var mediaRevision = 0
    private var tutorialRevision = 0
    private var musicRevision = 0
    private var musicSessionActive = false
    private var dismissedMusicError: String? = null
    private var job: Job? = null
    private var generation = 0
    private enum class LobbyOperation { START_GAME, CHECK_MODELS }
    private var lobbyOperation: LobbyOperation? = null
    // A game keeps its validated assignments for every phase and retry.
    private var gameModels: Map<String, AiModel> = emptyMap()

    init {
        initialSave?.let { applyPreferences(it) }
        game?.let { restored ->
            gameModels = Characters.all.associate { it.id to requireNotNull(CharacterModels.forCharacter(it.id)) }
            gateway.setActiveGameId(restored.id)
        }
        restoringState = false
        if (effectsSupported) effectsMonitor = scope.launch {
            while (isActive) {
                val revision = effectsRevision
                try {
                    val status = gateway.effectsStatus()
                    if (revision == effectsRevision) effectsEnabled = status.enabled
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Effects never affect gameplay or report a game failure. */ }
                delay(750)
            }
        }
        if (audioSupported) mediaMonitor = scope.launch {
            while (isActive) {
                val revision = mediaRevision
                try {
                    val status = gateway.mediaStatus()
                    if (revision == mediaRevision) {
                        mediaStatus = status
                        audioEnabled = status.enabled
                        audioPreferenceReady = true
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the last local snapshot; never initiate speech from a failed read. */ }
                // Initial and muted reads synchronize the browser preference.
                // This operation is local and does not initialize AI Pass.
                delay(500)
            }
        }
        if (walletSupported) walletMonitor = scope.launch {
            while (isActive) {
                try { acceptWalletStatus(gateway.walletStatus()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { markWalletStale() }
                // Observe the bridge's event-updated memory only. This does not
                // load the SDK, read browser storage, or make network requests.
                delay(1_500)
            }
        }
        if (musicSupported) musicMonitor = scope.launch {
            while (isActive) {
                val revision = musicRevision
                try {
                    val status = gateway.musicStatus()
                    if (revision == musicRevision) {
                        musicEnabled = status.enabled
                        musicPlaying = status.playing
                        musicPending = status.pending
                        musicError = status.error?.takeUnless { it == dismissedMusicError }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    // A missing status snapshot must not mute the user's music,
                    // change their preference, or invent a pre-gesture failure.
                }
                // Read initially and while muted too, so the persisted browser
                // preference wins. This only observes local memory, never AI Pass.
                delay(750)
            }
        }
    }

    private fun snapshot() = SavedApp(playerName = playerName, audioEnabled = audioEnabled, audioVolume = audioVolume,
        musicEnabled = musicEnabled, musicVolume = musicVolume, effectsEnabled = effectsEnabled,
        effectsVolume = effectsVolume, game = game ?: savedGame, notes = notes)

    private fun persist() {
        if (!restoringState && gateway.storageAvailable) localSaveFailed = !gateway.saveLocal(snapshot())
    }

    fun updateNotes(value: String) { notes = value.take(3000); persist() }

    private fun applyPreferences(state: SavedApp) {
        updateAudioVolume(state.audioVolume); updateAudioEnabled(state.audioEnabled)
        updateMusicVolume(state.musicVolume); updateMusicEnabled(state.musicEnabled)
        updateEffectsVolume(state.effectsVolume); updateEffectsEnabled(state.effectsEnabled)
    }

    /** Restoring never starts an AI request. The ordinary Resume button owns that. */
    fun resumeSavedGame() {
        if (busy || storageBusy || game != null) return
        val saved = savedGame ?: return
        gameModels = Characters.all.associate { it.id to requireNotNull(CharacterModels.forCharacter(it.id)) }
        game = saved
        gateway.setActiveGameId(saved.id)
        paused = true
        error = null
        audioError = null
        audioPausedAfterError = false
        stopEffects()
        syncMusicActivity()
    }

    fun backup() {
        if (storageBusy || !gateway.storageAvailable) return
        val state = snapshot()
        storageBusy = true; storageMessage = null
        storageJob = scope.launch {
            try { gateway.backup(state); storageMessage = "BACKUP_SAVED" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { storageMessage = e.message ?: "STORAGE_FAILED" }
            finally { storageBusy = false }
        }
    }

    fun restoreBackup() {
        if (storageBusy || busy || game != null || !gateway.storageAvailable) return
        storageBusy = true; storageMessage = null
        storageJob = scope.launch {
            try {
                val state = gateway.restoreBackup()
                if (state == null) storageMessage = "BACKUP_EMPTY"
                else {
                    restoringState = true
                    playerName = state.playerName; savedGame = state.game; notes = state.notes
                    rememberPlayerName(playerName)
                    applyPreferences(state)
                    restoringState = false
                    localSaveFailed = !gateway.saveLocal(state)
                    storageMessage = "BACKUP_RESTORED"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { storageMessage = e.message ?: "STORAGE_FAILED" }
            finally { restoringState = false; storageBusy = false }
        }
    }

    private fun acceptWalletStatus(status: WalletStatus) {
        if (status.revision >= walletStatus.revision) walletStatus = status
    }

    private fun markWalletStale() {
        walletStatus = walletStatus.copy(stale = walletStatus.balance != null, error = "WALLET_UNAVAILABLE")
    }

    /** Independent from the game job: a balance read cannot fail or pause a turn. */
    fun refreshWallet() {
        if (!walletSupported || walletJob?.isActive == true) return
        walletRefreshing = true
        walletJob = scope.launch {
            try { acceptWalletStatus(gateway.walletStatus(refresh = true)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { markWalletStale() }
            finally { walletRefreshing = false }
        }
    }

    /** Login, balance, payment, gift card and disconnect UI stay inside the official SDK. */
    fun openWallet() {
        if (!walletSupported || walletOpenJob?.isActive == true) return
        walletOpening = true
        walletOpenJob = scope.launch {
            try { acceptWalletStatus(gateway.openWallet()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A dismissed or failed SDK surface leaves the current chip state intact. */ }
            finally { walletOpening = false; walletOpenJob = null }
        }
    }

    fun modelForCharacter(characterId: String): AiModel? =
        if (game != null) gameModels[characterId] else CharacterModels.forCharacter(characterId)

    private fun createConfiguredGame(assignments: Map<String, AiModel>) {
        gameModels = assignments.toMap()
        paused = false
        audioError = null
        audioPausedAfterError = false
        notes = ""
        stopEffects()
        gateway.setEffectsActive(true)
        commitGame(GameEngine.newGame(humanName = playerName))
        syncMusicActivity()
    }

    fun updateAudioEnabled(enabled: Boolean) {
        if (!audioSupported) return
        mediaRevision++
        audioEnabled = enabled
        audioPreferenceReady = true
        audioError = null
        audioPausedAfterError = false
        tutorialError = null
        mediaStatus = mediaStatus.copy(enabled = enabled,
            playing = mediaStatus.playing && enabled, pending = mediaStatus.pending && enabled, error = null)
        gateway.setAudioVolume(audioVolume)
        // Called directly from a user gesture so browsers can unlock playback.
        gateway.setAudioEnabled(enabled)
        if (!enabled) { narratingPlayerId = null; stopTutorialNarration() }
        persist()
    }

    fun updateAudioVolume(volume: Float) {
        if (!volume.isFinite()) return
        audioVolume = volume.coerceIn(0f, 1f)
        gateway.setAudioVolume(audioVolume)
        if (audioVolume == 0f) narratingPlayerId = null
        persist()
    }

    fun clearAudioError() { audioError = null }

    /** Uses only a bundled manifest ID. This path cannot call a model or TTS API. */
    fun narrateTutorial(clipId: String) {
        stopTutorialNarration()
        if (!audioSupported || !mediaStatus.tutorialAvailable || !audioPreferenceReady || !audioEnabled || audioVolume <= 0f) return
        val revision = ++tutorialRevision
        tutorialClipId = clipId
        tutorialError = null
        tutorialLoading = true
        tutorialJob = scope.launch {
            try { gateway.playTutorial(clipId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (revision == tutorialRevision) tutorialError = e.message ?: "TUTORIAL_AUDIO_FAILED"
            } finally {
                if (revision == tutorialRevision) { tutorialLoading = false; tutorialJob = null }
            }
        }
    }

    fun stopTutorialNarration() {
        tutorialRevision++
        tutorialJob?.cancel()
        tutorialJob = null
        tutorialLoading = false
        tutorialClipId = null
        tutorialError = null
        gateway.stopTutorial()
    }

    fun updateMusicEnabled(enabled: Boolean) {
        if (!musicSupported) return
        musicRevision++
        musicError = null
        dismissedMusicError = null
        musicEnabled = enabled
        musicPlaying = false
        gateway.setMusicVolume(musicVolume)
        syncMusicActivity()
        // Keep this synchronous with the switch/retry gesture for browser playback.
        gateway.setMusicEnabled(enabled)
        persist()
    }

    fun updateMusicVolume(volume: Float) {
        if (!volume.isFinite()) return
        musicVolume = volume.coerceIn(0f, 1f)
        if (musicVolume == 0f) { musicPlaying = false; musicPending = false }
        gateway.setMusicVolume(musicVolume)
        persist()
    }

    fun clearMusicError() { dismissedMusicError = musicError; musicError = null }

    fun setMusicSessionActive(active: Boolean) {
        musicRevision++
        musicSessionActive = active
        syncMusicActivity()
    }

    private fun syncMusicActivity() {
        val active = musicSessionActive && !paused
        if (!active) musicPlaying = false
        musicPending = active && musicEnabled && musicVolume > 0f && !musicPlaying && musicError == null
        gateway.setMusicActive(active)
    }

    private fun stopMusic() {
        musicRevision++
        musicPlaying = false
        musicPending = false
        musicError = null
        dismissedMusicError = null
        // This ends a playback session; only the explicit switch changes the
        // remembered on/off preference. The next lobby can resume that choice.
        gateway.stopMusic()
    }

    fun updateEffectsEnabled(enabled: Boolean) {
        if (!effectsSupported) return
        effectsRevision++
        effectsEnabled = enabled
        gateway.setEffectsVolume(effectsVolume)
        gateway.setEffectsEnabled(enabled)
        if (!enabled) stopEffects()
        else gateway.setEffectsActive(game != null && !paused)
        persist()
    }

    fun updateEffectsVolume(volume: Float) {
        if (!volume.isFinite()) return
        effectsVolume = volume.coerceIn(0f, 1f)
        gateway.setEffectsVolume(effectsVolume)
        if (effectsVolume == 0f) stopEffects()
        else gateway.setEffectsActive(game != null && !paused)
        persist()
    }

    private fun stopEffects() {
        effectsEpoch++
        effectsJobs.toList().forEach { it.cancel() }
        effectsJobs.clear()
        gateway.setEffectsActive(false)
        gateway.stopEffects()
    }

    private fun commitGame(next: Game, announceEffects: Boolean = true) {
        val previous = game
        game = next
        gateway.setActiveGameId(next.id)
        savedGame = next
        persist()
        if (announceEffects) announcePublicEffects(previous, next)
    }

    /** Sounds describe only freshly committed public events, never private actions. */
    private fun announcePublicEffects(previous: Game?, next: Game) {
        if (!effectsSupported || !effectsEnabled || effectsVolume <= 0f || paused) return
        val cues = if (previous == null || previous.id != next.id) listOf("game_start") else buildList {
            next.conversation.drop(previous.conversation.size).forEach { event ->
                val cue = when (event.kind) {
                    EventKind.DAY_STARTED -> "daybreak".takeIf { event.day > 1 }
                    EventKind.NIGHT_STARTED -> "nightfall"
                    EventKind.VOTE_TIED -> "vote_tied"
                    EventKind.ELIMINATED -> "eliminated"
                    EventKind.NIGHT_KILLED -> "night_killed"
                    EventKind.NIGHT_SAVED -> "night_saved"
                    EventKind.GAME_ENDED -> when (next.winner) {
                        Team.TOWN -> "town_win"
                        Team.MAFIA -> "mafia_win"
                        null -> null
                    }
                    else -> null
                }
                if (cue != null) add(cue)
            }
            if (previous.phase != next.phase && next.phase in listOf(Phase.VOTING, Phase.NOMINATION, Phase.FINAL_VOTING)) add("vote_open")
        }.distinct().takeLast(3)
        if (cues.isEmpty()) return
        val epoch = effectsEpoch
        lateinit var effectJob: Job
        effectJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                for (cue in cues) {
                    if (epoch != effectsEpoch || game?.id != next.id || paused || !effectsEnabled) break
                    try { gateway.playEffect(cue) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Missing/blocked effects cannot stall the next turn. */ }
                }
            } finally { effectsJobs.remove(effectJob) }
        }
        effectsJobs += effectJob
        effectJob.start()
    }

    /** Stop media and observers when the App/controller leaves composition. */
    fun dispose() {
        persist()
        storageJob?.cancel()
        walletMonitor?.cancel()
        musicMonitor?.cancel()
        mediaMonitor?.cancel()
        effectsMonitor?.cancel()
        walletJob?.cancel()
        walletOpenJob?.cancel()
        stopTutorialNarration()
        cancelOperation()
        stopMusic()
        stopEffects()
    }

    fun loadModels(startGame: Boolean = true) {
        if (busy || game != null) return
        lobbyOperation = if (startGame) LobbyOperation.START_GAME else LobbyOperation.CHECK_MODELS
        loaded = false
        launch { version ->
            val assignments = CharacterModels.resolve(gateway.models())
            currentCoroutineContext().ensureActive()
            if (generation != version) return@launch
            if (startGame) createConfiguredGame(assignments)
            if (startGame) refreshWallet()
            loaded = true
            lobbyOperation = null
        }
    }

    fun retryLobbyOperation() {
        if (busy || game != null || error == null) return
        when (lobbyOperation) {
            LobbyOperation.START_GAME -> loadModels(startGame = true)
            LobbyOperation.CHECK_MODELS -> loadModels(startGame = false)
            null -> Unit
        }
    }

    fun newGame() {
        if (!PlayerNames.valid(playerName) || storageBusy) return
        // Revalidate the live catalog for each new game, including a replay.
        loadModels(startGame = true)
    }

    fun leave() {
        savedGame = game ?: savedGame
        persist()
        stopEffects()
        stopTutorialNarration()
        cancelOperation()
        stopMusic()
        game = null
        gateway.setActiveGameId(null)
        gameModels = emptyMap()
        lobbyOperation = null
        error = null
        paused = false
        audioError = null
        audioPausedAfterError = false
    }

    fun beginDay() {
        val current = game ?: return
        if (busy || current.phase !in listOf(Phase.REVEAL, Phase.DAWN)) return
        paused = false
        error = null
        gateway.setEffectsActive(true)
        commitGame(GameEngine.beginDay(current))
        syncMusicActivity()
        drive()
    }

    fun say(text: String) {
        val current = game ?: return
        if (busy || paused || error != null || current.phase !in listOf(Phase.DISCUSSION, Phase.DEFENSE) || GameEngine.actor(current)?.isHuman != true) return
        if (text.trim().length > 1000) return
        commitGame(GameEngine.speak(current, current.human.id, text))
        drive()
    }

    /** Keep the existing ballot UI visible after its last vote instead of replacing it with a separate reveal. */
    private fun completedBallotHold(before: Game, after: Game): Game? {
        val boundaryReached = when (before.phase) {
            Phase.NOMINATION -> after.phase != Phase.NOMINATION || after.nominationIndex != before.nominationIndex
            Phase.FINAL_VOTING -> after.phase != Phase.FINAL_VOTING
            Phase.VOTING -> after.phase != Phase.VOTING
            else -> false
        }
        if (!boundaryReached) return null
        val kinds = when (before.phase) {
            Phase.NOMINATION -> setOf(EventKind.NOMINATION_YES, EventKind.NOMINATION_NO)
            Phase.FINAL_VOTING -> setOf(EventKind.FINAL_VOTE, EventKind.FINAL_ABSTAIN)
            Phase.VOTING -> setOf(EventKind.VOTE)
            else -> emptySet()
        }
        val message = after.conversation.drop(before.conversation.size).lastOrNull { it.kind in kinds } ?: return null
        return when (before.phase) {
            Phase.NOMINATION -> before.copy(
                conversation = before.conversation + message,
                nominationVotes = after.nominationVotes,
                nominationPlans = after.nominationPlans,
                usage = after.usage,
            )
            Phase.FINAL_VOTING, Phase.VOTING -> before.copy(
                conversation = before.conversation + message,
                votes = after.votes,
                usage = after.usage,
            )
            else -> null
        }
    }

    private fun commitHumanAction(before: Game, after: Game, continueGame: Boolean) {
        val hold = completedBallotHold(before, after)
        if (hold == null) {
            commitGame(after)
            if (continueGame) drive()
            return
        }

        // Commit the deterministic result, but delay its sound and next actor until
        // the final ballot has been readable on screen.
        ballotHold = hold
        commitGame(after, announceEffects = false)
        launch { version ->
            pacingDelay(LAST_BALLOT_HOLD_MS)
            if (generation != version) return@launch
            ballotHold = null
            announcePublicEffects(before, after)
        }
        val holdVersion = generation
        job?.invokeOnCompletion { cause ->
            if (cause == null && generation == holdVersion && continueGame && !paused && error == null) {
                scope.launch { drive() }
            }
        }
    }

    fun choose(target: String) {
        val current = game ?: return
        if (busy || paused || error != null || GameEngine.actor(current)?.isHuman != true) return
        if (target !in GameEngine.legalTargets(current, current.human.id) && !(current.phase == Phase.FINAL_VOTING && target == GameEngine.ABSTAIN)) return
        val next = when (current.phase) {
            Phase.VOTING -> GameEngine.vote(current, current.human.id, target)
            Phase.FINAL_VOTING -> GameEngine.finalVote(current, current.human.id, target)
            Phase.NIGHT -> GameEngine.nightAction(current, current.human.id, target)
            else -> return
        }
        // Let the player read the public ballot result before any night calls.
        commitHumanAction(current, next, next.phase != Phase.NIGHT || current.phase == Phase.NIGHT)
    }

    fun nominate(approved: Boolean) {
        val current = game ?: return
        if (busy || paused || error != null || current.phase != Phase.NOMINATION || GameEngine.actor(current)?.isHuman != true) return
        val next = GameEngine.nominate(current, current.human.id, approved)
        commitHumanAction(current, next, next.phase != Phase.NIGHT)
    }

    fun pause() {
        if (game == null) return
        // Keep the current paid response; the driver stops before another call.
        paused = true
        stopEffects()
        musicRevision++
        syncMusicActivity()
        gateway.stopAudio()
        narratingPlayerId = null
    }

    fun resume() { if (busy) return; paused = false; error = null; gateway.setEffectsActive(game != null); syncMusicActivity(); drive() }
    fun clearError() { error = null }

    private fun cancelOperation() {
        // Invalidate ownership before cancellation: a late completion/finally must
        // never alter a newer request, or put an abandoned game back on screen.
        generation++
        val previous = job
        job = null
        previous?.cancel()
        gateway.stopAudio()
        narratingPlayerId = null
        ballotHold = null
        busy = false
    }

    /** Only presentation delays use this switch; request and playback timeouts remain unchanged. */
    private suspend fun pacingDelay(milliseconds: Long) {
        if (!gateway.fastPacing) delay(milliseconds)
    }

    private fun readingDelayMs(speech: String, spectator: Boolean): Long {
        val wordCount = speech.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return if (spectator) {
            (1_600L + wordCount * 170L).coerceIn(3_000L, 7_000L)
        } else {
            (1_000L + wordCount * 120L).coerceIn(1_800L, 4_500L)
        }
    }

    private fun launch(block: suspend (Int) -> Unit) {
        val version = ++generation
        busy = true; error = null
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            try { block(version) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == version) {
                    if (e is AiResultRejected) game = game?.let { it.copy(usage = it.usage + e.usage) }
                    persist()
                    error = e.message ?: "REQUEST_FAILED"
                }
            }
            finally {
                if (generation == version) {
                    busy = false
                    job = null
                }
            }
        }
        job = nextJob
        nextJob.start()
    }

    private fun drive() {
        if (busy || paused) return
        val firstActor = game?.let(GameEngine::actor) ?: return
        if (firstActor.isHuman) return
        launch { version ->
            while (generation == version && !paused) {
                val current = game ?: break
                val actor = GameEngine.actor(current) ?: break
                if (actor.isHuman) break
                pacingDelay(650)
                if (generation != version || paused) break
                val model = gameModels[actor.character.id] ?: error("NO_MODELS")
                val service = AiPassMafiaService(gateway, model)
                val context = GameEngine.contextFor(current, actor.id)
                var publicSpeech: String? = null
                val next = when (current.phase) {
                    Phase.DISCUSSION, Phase.DEFENSE -> {
                        val result = service.speak(context)
                        publicSpeech = result.speech
                        GameEngine.speak(current, actor.id, result.speech).copy(
                            beliefs = current.beliefs + (actor.id to result.beliefs), usage = current.usage + result.usage)
                    }
                    Phase.NOMINATION -> {
                        val prepared = if (actor.id in current.nominationPlans) current else {
                            val result = service.nominate(context)
                            GameEngine.planNominations(current, actor.id, result.approvedTargets).copy(usage = current.usage + result.usage)
                        }
                        GameEngine.nominate(prepared, actor.id, prepared.nominationCandidate!!.id in prepared.nominationPlans.getValue(actor.id))
                    }
                    Phase.FINAL_VOTING -> {
                        val result = service.vote(context)
                        GameEngine.finalVote(current, actor.id, result.target).copy(usage = current.usage + result.usage)
                    }
                    Phase.VOTING -> {
                        val result = service.vote(context)
                        GameEngine.vote(current, actor.id, result.target).copy(usage = current.usage + result.usage)
                    }
                    Phase.NIGHT -> {
                        val result = service.chooseNightAction(context)
                        GameEngine.nightAction(current, actor.id, result.target).copy(usage = current.usage + result.usage)
                    }
                    else -> break
                }
                currentCoroutineContext().ensureActive()
                if (generation != version) break
                val hold = completedBallotHold(current, next)
                if (hold != null) ballotHold = hold
                commitGame(next, announceEffects = publicSpeech == null && hold == null)
                // Commit the public text first. A voice failure must never resend
                // a paid dialogue request or roll back a completed game turn.
                var narrationPlayed = false
                if (publicSpeech != null && audioPreferenceReady && audioEnabled && !audioPausedAfterError && audioVolume > 0f && !paused) {
                    narratingPlayerId = actor.id
                    try {
                        gateway.narrate(NarrationRequest(actor.character.id, publicSpeech))?.let { usage ->
                            game?.let { latest -> commitGame(latest.copy(usage = latest.usage + usage), announceEffects = false) }
                        }
                        narrationPlayed = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (generation == version) {
                            audioError = e.message ?: "AUDIO_FAILED"
                            audioPausedAfterError = true
                            // A failed clip pauses narration for this game, but
                            // never changes the user's remembered speech choice.
                            gateway.stopAudio()
                        }
                    } finally {
                        if (generation == version) narratingPlayerId = null
                    }
                    currentCoroutineContext().ensureActive()
                    if (generation != version) break
                }
                // Let the last spoken statement finish before the voting cue.
                // A pause discards this cue; resume never replays old events.
                if (publicSpeech != null) {
                    announcePublicEffects(current, next)
                    pacingDelay(if (narrationPlayed) 650L else readingDelayMs(publicSpeech, spectator = !current.human.isAlive))
                }
                if (hold != null) {
                    try { pacingDelay(LAST_BALLOT_HOLD_MS) }
                    finally { if (generation == version) ballotHold = null }
                    currentCoroutineContext().ensureActive()
                    if (generation != version || paused) break
                    announcePublicEffects(current, next)
                }
                // Night begins only on a visible user action, keeping ballots readable.
                if (next.phase == Phase.NIGHT && current.phase != Phase.NIGHT) break
            }
        }
    }
}
