package ir.iact.mafiagame.ui

import androidx.compose.runtime.Composable
import ir.iact.mafiagame.domain.*
import mafiagame.shared.generated.resources.*
import org.jetbrains.compose.resources.*

fun faNumber(value: Any): String = value.toString().map { if (it in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it }.joinToString("")
private val directionControls = Regex("[\u202A-\u202E\u2066-\u2069]")
private val latinRun = Regex("[A-Za-z0-9][A-Za-z0-9._+#/@:-]*")

/** Keep logical Persian order, and isolate model names without nesting direction controls. */
fun rtl(text: String): String = text.replace(directionControls, "").split('\n').joinToString("\n") { line ->
    val ordered = if (line.any { it in 'A'..'Z' || it in 'a'..'z' } && line.none { it in '\u0600'..'\u06FF' }) {
        "\u2066$line\u2069"
    } else line.replace(latinRun) { "\u2066${it.value}\u2069" }
    "\u202B$ordered\u202C"
}
@Composable fun tr(resource: StringResource, vararg args: Any): String = stringResource(resource, *args)
@Composable fun roleName(role: Role) = tr(when (role) {
    Role.MAFIA -> Res.string.role_mafia; Role.DETECTIVE -> Res.string.role_detective
    Role.DOCTOR -> Res.string.role_doctor; Role.CITIZEN -> Res.string.role_citizen
})
@Composable fun roleDescription(role: Role) = tr(when (role) {
    Role.MAFIA -> Res.string.desc_mafia; Role.DETECTIVE -> Res.string.desc_detective
    Role.DOCTOR -> Res.string.desc_doctor; Role.CITIZEN -> Res.string.desc_citizen
})
@Composable fun eventText(event: GameMessage, game: Game): String {
    fun name(id: String?) = game.players.firstOrNull { it.id == id }?.character?.name.orEmpty()
    return when (event.kind) {
        EventKind.DAY_STARTED -> tr(Res.string.day_event, faNumber(event.day))
        EventKind.SPEECH, EventKind.DEFENSE_SPEECH -> event.text
        EventKind.SKIP, EventKind.DEFENSE_SKIP -> tr(Res.string.skip_event)
        EventKind.NOMINATION_STARTED -> tr(Res.string.nomination_started_event)
        EventKind.NOMINATION_YES -> tr(Res.string.nomination_yes_event, name(event.playerId), name(event.targetId))
        EventKind.NOMINATION_NO -> tr(Res.string.nomination_no_event, name(event.playerId), name(event.targetId))
        EventKind.DEFENSE_STARTED -> tr(Res.string.defense_started_event, name(event.targetId))
        EventKind.FINAL_VOTE_STARTED -> tr(Res.string.final_started_event)
        EventKind.FINAL_VOTE -> tr(Res.string.final_vote_event, name(event.playerId), name(event.targetId))
        EventKind.FINAL_ABSTAIN -> tr(Res.string.final_abstain_event, name(event.playerId))
        EventKind.NO_ELIMINATION -> tr(Res.string.no_elimination_event)
        EventKind.VOTE -> tr(Res.string.vote_event, name(event.playerId), name(event.targetId))
        EventKind.VOTE_TIED -> tr(Res.string.tie_event)
        EventKind.ELIMINATED -> tr(Res.string.eliminated_event, name(event.targetId))
        EventKind.NIGHT_STARTED -> tr(Res.string.night_event)
        EventKind.NIGHT_SAVED -> tr(Res.string.saved_event)
        EventKind.NIGHT_KILLED -> tr(Res.string.killed_event, name(event.targetId))
        EventKind.GAME_ENDED -> tr(Res.string.end_event)
    }
}
@Composable fun errorText(code: String) = tr(when (code) {
    "NETWORK", "SDK_UNAVAILABLE" -> Res.string.error_network
    "AUTH" -> Res.string.error_auth
    "BALANCE" -> Res.string.error_balance
    "INVALID_RESPONSE" -> Res.string.error_invalid
    "AI_BAD_JSON", "AI_WRONG_ACTION" -> Res.string.error_action_format
    "AI_ILLEGAL_TARGET" -> Res.string.error_action_target
    "AI_TRUNCATED" -> Res.string.error_action_truncated
    "AI_EMPTY", "AI_REFUSED" -> Res.string.error_action_empty
    "SETUP_REQUIRED" -> Res.string.error_setup
    "NO_MODELS" -> Res.string.error_models
    "WEB_ONLY" -> Res.string.web_only
    "RATE_LIMIT" -> Res.string.error_rate
    "TIMEOUT" -> Res.string.error_timeout
    else -> Res.string.error_generic
})
fun portrait(id: String): DrawableResource = when (id) {
    "arman" -> Res.drawable.arman; "sara" -> Res.drawable.sara; "reza" -> Res.drawable.reza
    "nika" -> Res.drawable.nika; "ali" -> Res.drawable.ali; "mina" -> Res.drawable.mina
    else -> Res.drawable.icon
}
