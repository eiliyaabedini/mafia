package ir.iact.mafiagame.domain

import kotlinx.serialization.Serializable

@Serializable enum class Role { MAFIA, DETECTIVE, DOCTOR, CITIZEN }
@Serializable enum class Phase { REVEAL, DISCUSSION, VOTING, NOMINATION, DEFENSE, FINAL_VOTING, NIGHT, DAWN, FINISHED }
@Serializable enum class Team { TOWN, MAFIA }
@Serializable enum class EventKind { DAY_STARTED, SPEECH, SKIP, VOTE, VOTE_TIED, NOMINATION_STARTED, NOMINATION_YES, NOMINATION_NO, DEFENSE_STARTED, DEFENSE_SPEECH, DEFENSE_SKIP, FINAL_VOTE_STARTED, FINAL_VOTE, FINAL_ABSTAIN, NO_ELIMINATION, ELIMINATED, NIGHT_STARTED, NIGHT_SAVED, NIGHT_KILLED, GAME_ENDED }

@Serializable data class CharacterProfile(
    val id: String,
    val name: String,
    val subtitle: String,
    val personality: String,
    val speakingStyle: String,
    val weakness: String,
    val color: Long,
)
@Serializable data class Player(val id: String, val character: CharacterProfile, val role: Role, val isHuman: Boolean = false, val isAlive: Boolean = true)
@Serializable data class GameMessage(val kind: EventKind, val day: Int, val playerId: String? = null, val targetId: String? = null, val text: String = "")
@Serializable data class NominationVote(val voterId: String, val candidateId: String, val approved: Boolean)
@Serializable data class Investigation(val night: Int, val targetId: String, val isMafia: Boolean)
@Serializable data class AgentBeliefs(val suspicions: Map<String, Double> = emptyMap(), val intention: String = "")
@Serializable data class AiUsage(val model: String, val inputTokens: Long = 0, val cachedInputTokens: Long = 0, val outputTokens: Long = 0, val estimatedCost: Double? = null)
@Serializable data class Game(
    val id: String,
    val seed: Int,
    val players: List<Player>,
    val phase: Phase = Phase.REVEAL,
    val day: Int = 1,
    val pass: Int = 1,
    val turn: Int = 0,
    val conversation: List<GameMessage> = emptyList(),
    val votes: Map<String, String> = emptyMap(),
    val nightActions: Map<String, String> = emptyMap(),
    val investigations: Map<String, List<Investigation>> = emptyMap(),
    val beliefs: Map<String, AgentBeliefs> = emptyMap(),
    val usage: List<AiUsage> = emptyList(),
    val winner: Team? = null,
    val discussionOrder: List<String> = emptyList(),
    val nominationOrder: List<String> = emptyList(),
    val nominationIndex: Int = 0,
    val nominationVotes: List<NominationVote> = emptyList(),
    /** Unrevealed AI choices are private; contextFor never exports this field. */
    val nominationPlans: Map<String, List<String>> = emptyMap(),
    val defenseCandidates: List<String> = emptyList(),
) {
    val living get() = players.filter { it.isAlive }
    /** Public speaking order, separate from stable seats, IDs, and secret roles. */
    val discussionPlayers get() = discussionOrder.distinct().mapNotNull { id ->
        players.firstOrNull { it.id == id && it.isAlive }
    }
    val nominationCandidate get() = nominationOrder.getOrNull(nominationIndex)?.let(::player)
    val ballotOrder get() = if (nominationOrder.isEmpty()) living else nominationOrder.map(::player).filter { it.isAlive }
    val human get() = players.single { it.isHuman }
    fun player(id: String) = players.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown player: $id")
}

/** Deliberately excludes roles, pending ballots/night actions and other players' private state. */
@Serializable data class PublicPlayer(val id: String, val name: String, val isAlive: Boolean, val isHuman: Boolean = false)
@Serializable data class AgentContext(
    val playerId: String,
    val character: CharacterProfile,
    val role: Role,
    val teammates: List<String>,
    val investigations: List<Investigation>,
    val beliefs: AgentBeliefs,
    val players: List<PublicPlayer>,
    val day: Int,
    val pass: Int,
    val phase: Phase,
    val conversation: List<GameMessage>,
    val legalTargets: List<String>,
    val defenseCandidates: List<String> = emptyList(),
    val canAbstain: Boolean = false,
    val speakingOrder: List<String> = emptyList(),
)
