package ir.iact.mafiagame.ai

import ir.iact.mafiagame.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val MafiaJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

@Serializable data class AiModel(val id: String, val displayName: String = id)
// The cap includes hidden reasoning tokens. Gameplay calls set a small explicit budget.
@Serializable data class CompletionRequest(val model: String, val messages: List<PromptMessage>, val maxTokens: Int = 1024)
@Serializable data class PromptMessage(val role: String, val content: String)
@Serializable data class CompletionResult(val content: String, val usage: AiUsage, val rejection: String? = null)
class AiResultRejected(code: String, val usage: AiUsage) : IllegalStateException(code)
class AiCallFailed(code: String, val usage: AiUsage?) : IllegalStateException(code)
/** Only accepted public dialogue crosses the speech boundary, never an agent's context. */
@Serializable data class NarrationRequest(val characterId: String, val text: String)
/** Local bundled audio state. Reading it never loads AI Pass or spends wallet credit. */
@Serializable data class MusicStatus(
    val enabled: Boolean = false,
    val error: String? = null,
    val playing: Boolean = false,
    val pending: Boolean = false,
)
/** Shared speech preference and local prerecorded tutorial playback state. */
@Serializable data class MediaStatus(
    val enabled: Boolean = false,
    val playing: Boolean = false,
    val pending: Boolean = false,
    val clipId: String? = null,
    val error: String? = null,
    val tutorialAvailable: Boolean = false,
)
@Serializable data class EffectsStatus(val enabled: Boolean = false)
@Serializable enum class WalletConnection { UNKNOWN, SIGNED_OUT, CONNECTED }
/** Decimal USD as returned by AI Pass, without converting money through Float. */
@Serializable data class WalletBalance(val remainingUsd: String)
@Serializable data class WalletStatus(
    val connection: WalletConnection = WalletConnection.UNKNOWN,
    val balance: WalletBalance? = null,
    val stale: Boolean = false,
    val error: String? = null,
    val updatedAtEpochMs: Long? = null,
    val revision: Long = 0,
)
data class SpeechResult(val speech: String, val beliefs: AgentBeliefs, val usage: AiUsage)
data class TargetResult(val target: String, val usage: AiUsage)

interface AiGateway {
    val available: Boolean
    /** Development-only switch for skipping artificial reading and ballot delays. */
    val fastPacing: Boolean get() = false
    val audioAvailable: Boolean get() = false
    val musicAvailable: Boolean get() = false
    val effectsAvailable: Boolean get() = false
    val walletAvailable: Boolean get() = false
    val storageAvailable: Boolean get() = false
    /** Identifies only the currently open game so a mobile OAuth redirect can return to it. */
    fun setActiveGameId(gameId: String?) = Unit
    fun saveLocal(state: SavedApp): Boolean = false
    suspend fun backup(state: SavedApp) = Unit
    suspend fun restoreBackup(): SavedApp? = null
    suspend fun models(): List<AiModel>
    suspend fun complete(request: CompletionRequest): CompletionResult
    /** Opens the provider-owned account/connect surface and returns its latest known state. */
    suspend fun openWallet(): WalletStatus = WalletStatus()
    suspend fun narrate(request: NarrationRequest): AiUsage? = null
    fun setAudioEnabled(enabled: Boolean) = Unit
    fun setAudioVolume(volume: Float) = Unit
    fun stopAudio() = Unit
    suspend fun mediaStatus(): MediaStatus = MediaStatus()
    suspend fun playTutorial(clipId: String) = Unit
    fun stopTutorial() = Unit
    fun setMusicEnabled(enabled: Boolean) = Unit
    fun setMusicVolume(volume: Float) = Unit
    fun setMusicActive(active: Boolean) = Unit
    fun stopMusic() = Unit
    suspend fun musicStatus(): MusicStatus = MusicStatus()
    suspend fun effectsStatus(): EffectsStatus = EffectsStatus()
    suspend fun playEffect(cue: String) = Unit
    fun setEffectsEnabled(enabled: Boolean) = Unit
    fun setEffectsVolume(volume: Float) = Unit
    fun setEffectsActive(active: Boolean) = Unit
    fun stopEffects() = Unit
    /** Cached reads never initialize the SDK; refresh reads never open login. */
    suspend fun walletStatus(refresh: Boolean = false): WalletStatus = WalletStatus()
}
object UnavailableGateway : AiGateway {
    override val available = false
    override suspend fun models(): List<AiModel> = error("WEB_ONLY")
    override suspend fun complete(request: CompletionRequest): CompletionResult = error("WEB_ONLY")
}

/** A string-only boundary keeps JS SDK and browser credentials out of shared gameplay. */
class BrowserGateway(
    private val start: (String, String) -> Int,
    private val poll: (Int) -> String?,
    private val cancel: (Int) -> Unit,
    private val audioControl: (String, Double) -> Unit,
    private val writeSave: (String) -> Boolean = { false },
    private val activeGameControl: (String?) -> Unit = {},
    override val fastPacing: Boolean = false,
) : AiGateway {
    private val saveJson = Json { encodeDefaults = true; explicitNulls = false }
    override val available = true
    override val audioAvailable = true
    override val musicAvailable = true
    override val effectsAvailable = true
    override val walletAvailable = true
    override val storageAvailable = true
    override fun setActiveGameId(gameId: String?) = activeGameControl(gameId)
    override fun saveLocal(state: SavedApp): Boolean = writeSave(saveJson.encodeToString(SavedApp.serializer(), state))
    override suspend fun backup(state: SavedApp) { call("backup", saveJson.encodeToString(SavedApp.serializer(), state)) }
    override suspend fun restoreBackup(): SavedApp? {
        val result = call("restoreBackup")
        if (result == JsonNull) return null
        return SavedApp.parse(result.toString()) ?: error("STORAGE_INVALID")
    }
    private suspend fun call(operation: String, payload: String = "{}"): JsonElement {
        currentCoroutineContext().ensureActive()
        val id = start(operation, payload)
        try {
            // Allow the SDK's wallet connection flow several minutes. The bridge
            // separately limits the actual generation request to 90 seconds.
            val timeout = when (operation) {
                "complete" -> 435_000L
                "narrate" -> 125_000L
                // A local clip may wait for the learner's first browser gesture.
                // Navigation or mute cancels it; waiting is never a paid request.
                "tutorialPlay" -> 86_400_000L
                "backup", "restoreBackup", "walletOpen" -> 390_000L
                else -> 45_000L
            }
            return withTimeoutOrNull(timeout) {
                var result: JsonElement? = null
                while (result == null) {
                    currentCoroutineContext().ensureActive()
                    val raw = poll(id)
                    if (raw != null) {
                        val response = MafiaJson.parseToJsonElement(raw).jsonObject
                        response["error"]?.jsonPrimitive?.contentOrNull?.let { code ->
                            val usage = response["usage"]?.let { runCatching {
                                MafiaJson.decodeFromJsonElement<AiUsage>(it)
                            }.getOrNull() }
                            throw AiCallFailed(code, usage)
                        }
                        result = response["data"] ?: JsonNull
                    } else delay(100)
                }
                result
            } ?: error("TIMEOUT")
        } finally { cancel(id) }
    }
    override suspend fun models(): List<AiModel> = MafiaJson.decodeFromJsonElement(call("models"))
    override suspend fun complete(request: CompletionRequest): CompletionResult =
        MafiaJson.decodeFromJsonElement(call("complete", MafiaJson.encodeToString(CompletionRequest.serializer(), request)))
    override suspend fun openWallet(): WalletStatus =
        MafiaJson.decodeFromJsonElement(call("walletOpen"))
    override suspend fun narrate(request: NarrationRequest): AiUsage? =
        call("narrate", MafiaJson.encodeToString(NarrationRequest.serializer(), request)).let {
            if (it == JsonNull) null else MafiaJson.decodeFromJsonElement(it)
        }
    override fun setAudioEnabled(enabled: Boolean) { audioControl("enable", if (enabled) 1.0 else 0.0) }
    override fun setAudioVolume(volume: Float) { audioControl("volume", volume.toDouble()) }
    override fun stopAudio() { audioControl("stop", 0.0) }
    override suspend fun mediaStatus(): MediaStatus = MafiaJson.decodeFromJsonElement(call("mediaStatus"))
    override suspend fun playTutorial(clipId: String) {
        call("tutorialPlay", buildJsonObject { put("clipId", clipId) }.toString())
    }
    override fun stopTutorial() { audioControl("tutorialStop", 0.0) }
    override fun setMusicEnabled(enabled: Boolean) { audioControl("musicEnable", if (enabled) 1.0 else 0.0) }
    override fun setMusicVolume(volume: Float) { audioControl("musicVolume", volume.toDouble()) }
    override fun setMusicActive(active: Boolean) { audioControl("musicActive", if (active) 1.0 else 0.0) }
    override fun stopMusic() { audioControl("musicStop", 0.0) }
    override suspend fun musicStatus(): MusicStatus = MafiaJson.decodeFromJsonElement(call("musicStatus"))
    override suspend fun effectsStatus(): EffectsStatus = MafiaJson.decodeFromJsonElement(call("effectsStatus"))
    override suspend fun playEffect(cue: String) { call("effect", buildJsonObject { put("cue", cue) }.toString()) }
    override fun setEffectsEnabled(enabled: Boolean) { audioControl("effectsEnable", if (enabled) 1.0 else 0.0) }
    override fun setEffectsVolume(volume: Float) { audioControl("effectsVolume", volume.toDouble()) }
    override fun setEffectsActive(active: Boolean) { audioControl("effectsActive", if (active) 1.0 else 0.0) }
    override fun stopEffects() { audioControl("effectsStop", 0.0) }
    override suspend fun walletStatus(refresh: Boolean): WalletStatus =
        MafiaJson.decodeFromJsonElement(call("walletStatus", "{\"refresh\":$refresh}"))
}

data class NominationResult(val approvedTargets: List<String>, val usage: AiUsage)

interface MafiaAiService {
    suspend fun speak(context: AgentContext): SpeechResult
    suspend fun nominate(context: AgentContext): NominationResult
    suspend fun vote(context: AgentContext): TargetResult
    suspend fun chooseNightAction(context: AgentContext): TargetResult
}

class AiPassMafiaService(private val gateway: AiGateway, private val model: AiModel) : MafiaAiService {
    private suspend fun complete(request: CompletionRequest): CompletionResult {
        val result = gateway.complete(request)
        result.rejection?.let { throw AiResultRejected(it, result.usage) }
        return result
    }
    private fun prompt(context: AgentContext, task: String): List<PromptMessage> =
        AgentPrompt.messages(context, task)

    override suspend fun speak(context: AgentContext): SpeechResult {
        val response = complete(CompletionRequest(model.id, prompt(context,
            "${if (context.phase == Phase.DEFENSE) "This is your defense after nomination voting. Answer the strongest accusation against YOU, refer to actual votes or statements, and explain your choices. " else ""}Speak as ${context.character.name}, responding to the table from your own position in Persian. Return {\"speakerId\":\"${context.playerId}\",\"speech\":\"...\",\"suspicions\":{\"player_id\":0.5},\"intention\":\"brief assessment\"}. speakerId must be your own exact ID. Suspicions are optional; list at most 2, never your private teammates as facts."), maxTokens = 800))
        try {
        val json = parseObject(response.content)
        require(stringField(json, "speakerId") == context.playerId) { "INVALID_RESPONSE" }
        val speech = stringField(json, "speech")?.replace(unsafeControls, "")?.trim().orEmpty()
        // A player may enter a Latin-script name; naming them does not make an
        // otherwise Persian line invalid. Permit only exact known roster names.
        val languageText = context.players.fold(speech) { text, player ->
            text.replace(player.name, "", ignoreCase = true)
        }
        val letters = languageText.filter { it.isLetter() }
        require(letters.isNotEmpty() && letters.all { it in '\u0600'..'\u06FF' }) { "INVALID_RESPONSE" }
        val sentences = speech.split(Regex("(?<=[.!؟?])\\s*|\\n+")).filter { it.isNotBlank() }.take(3).joinToString(" ")
        val bounded = sentences.split(Regex("\\s+")).take(65).joinToString(" ").let {
            if (it.length <= GameEngine.MAX_SPEECH_CHARS) it
            else it.take(GameEngine.MAX_SPEECH_CHARS - 1).substringBeforeLast(' ') + "…"
        }
        require(bounded.any { it.isLetter() }) { "INVALID_RESPONSE" }
        val suspects = (json["suspicions"] as? JsonObject).orEmpty().mapNotNull { (id, value) ->
            val score = (value as? JsonPrimitive)?.doubleOrNull
            if (id != context.playerId && context.players.any { it.id == id && it.isAlive } && score != null && score.isFinite()) id to score.coerceIn(0.0, 1.0) else null
        }.take(2).toMap()
        return SpeechResult(bounded, AgentBeliefs(suspects, stringField(json, "intention").orEmpty().replace(unsafeControls, "").take(160)), response.usage)
        } catch (e: IllegalArgumentException) { throw AiResultRejected(e.message ?: "INVALID_RESPONSE", response.usage) }
    }

    override suspend fun nominate(context: AgentContext): NominationResult {
        require(context.phase == Phase.NOMINATION)
        val response = complete(CompletionRequest(model.id, prompt(context,
            "Choose which other living players should defend themselves. You may nominate multiple players or nobody. " +
                "Return {\"approvedTargets\":[\"legal player id\"]}, with no duplicates; use [] for nobody."), maxTokens = 320))
        val approved = try {
            val value = parseObject(response.content)["approvedTargets"] as? JsonArray ?: error("AI_WRONG_ACTION")
            val ids = value.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("AI_WRONG_ACTION") }
            require(ids.distinct().size == ids.size && ids.all { it in context.legalTargets }) { "AI_ILLEGAL_TARGET" }
            ids
        } catch (e: Exception) { throw AiResultRejected(e.message ?: "AI_BAD_JSON", response.usage) }
        return NominationResult(approved, response.usage)
    }

    override suspend fun vote(context: AgentContext) = target(context, if (context.canAbstain)
        "After the defense speeches, choose ONE eligible finalist to eliminate, or abstain. Compare nomination votes with defenses. " +
            "Return {\"target\":\"one legal target id\"} or {\"target\":\"abstain\"}."
        else "Choose one living player to eliminate. Return {\"target\":\"one legal target id\"}.")
    override suspend fun chooseNightAction(context: AgentContext) = target(context, when (context.role) {
        Role.MAFIA -> "Propose a non-Mafia victim."
        Role.DOCTOR -> "Choose someone to protect. You may protect yourself."
        Role.DETECTIVE -> "Choose someone to investigate. Consider your previous investigations."
        Role.CITIZEN -> error("ILLEGAL_ACTION")
    } + " Return {\"target\":\"one legal target id\"}.")

    private suspend fun target(context: AgentContext, task: String): TargetResult {
        require(context.legalTargets.isNotEmpty() || context.canAbstain) { "ILLEGAL_ACTION" }
        val response = complete(CompletionRequest(model.id, prompt(context, task), maxTokens = 320))
        val target = try { ActionResponse.parse(response.content, context) }
            catch (e: IllegalArgumentException) { throw AiResultRejected(e.message ?: "INVALID_RESPONSE", response.usage) }
        return TargetResult(target, response.usage)
    }

    private fun parseObject(raw: String): JsonObject {
        return AiResponseJson.parse(raw) as? JsonObject ?: throw IllegalArgumentException("AI_BAD_JSON")
    }

    private fun stringField(json: JsonObject, key: String): String? =
        (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        val unsafeControls = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069]")
    }
}
