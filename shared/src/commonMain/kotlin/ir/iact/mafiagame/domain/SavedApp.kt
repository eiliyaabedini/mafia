package ir.iact.mafiagame.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One local save and optional private AI Pass backup. No credentials or audio. */
@Serializable data class SavedApp(
    val version: Int = 1,
    val playerName: String = "",
    val audioEnabled: Boolean = true,
    val audioVolume: Float = .8f,
    val musicEnabled: Boolean = true,
    val musicVolume: Float = .25f,
    val effectsEnabled: Boolean = true,
    val effectsVolume: Float = .55f,
    val game: Game? = null,
    val notes: String = "",
    val welcomeSeen: Boolean = false,
) {
    fun validated(): SavedApp {
        val normalizedPlayerName = PlayerNames.normalize(playerName)
        val validBeforeCharacterRename = normalizedPlayerName in setOf("شهاب", "داریوش") && game?.let { savedGame ->
            PlayerNames.normalize(savedGame.human.character.name) == normalizedPlayerName &&
                savedGame.players.filterNot { it.isHuman }.none {
                    PlayerNames.normalize(it.character.name) == normalizedPlayerName
                }
        } == true
        require(version == 1 && notes.length <= 3000)
        require(listOf(audioVolume, musicVolume, effectsVolume).all { it.isFinite() && it in 0f..1f })
        require(playerName.isEmpty() || PlayerNames.valid(playerName) || validBeforeCharacterRename)
        val restored = game?.let { game ->
            val ids = game.players.map { it.id }.toSet()
            require(game.players.size == 7 && ids == (0..6).map { "player_$it" }.toSet())
            require(game.players.count { it.isHuman } == 1 && game.human.id == "player_0")
            require(game.players.map { it.character.id }.toSet() == (Characters.all.map { it.id } + "you").toSet())
            require(game.human.character.id == "you" && PlayerNames.valid(game.human.character.name))
            require(game.players.groupingBy { it.role }.eachCount() == mapOf(Role.MAFIA to 2, Role.DOCTOR to 1, Role.DETECTIVE to 1, Role.CITIZEN to 3))
            require(game.day in 1..1000 && game.pass in 1..GameEngine.DISCUSSION_PASSES && game.turn in 0..6)
            require(game.conversation.size <= 5000 && game.conversation.all {
                it.day in 1..game.day && (it.playerId == null || it.playerId in ids) &&
                    (it.targetId == null || it.targetId in ids) && it.text.length <= 1000 &&
                    (it.kind !in listOf(EventKind.SPEECH, EventKind.SKIP, EventKind.VOTE, EventKind.DEFENSE_SPEECH, EventKind.DEFENSE_SKIP, EventKind.NOMINATION_YES, EventKind.NOMINATION_NO, EventKind.FINAL_VOTE, EventKind.FINAL_ABSTAIN) || it.playerId != null)
            })
            require(game.votes.all { (id, target) -> id in ids && (target in ids || target == GameEngine.ABSTAIN) && id != target })
            require(game.nightActions.all { (id, target) -> id in ids && target in ids })
            require(game.investigations.all { (id, history) ->
                id in ids && game.player(id).role == Role.DETECTIVE && history.size <= game.day &&
                    history.all { it.night in 1..game.day && it.targetId in ids && it.targetId != id }
            })
            require(game.beliefs.all { (id, beliefs) -> id in ids && beliefs.intention.length <= 1000 &&
                beliefs.suspicions.all { (target, score) -> target in ids && score.isFinite() && score in 0.0..1.0 } })
            require(game.usage.size <= 20_000 && game.usage.all { it.model.length <= 200 &&
                it.inputTokens >= 0 && it.outputTokens >= 0 && it.cachedInputTokens in 0..it.inputTokens &&
                (it.estimatedCost == null || it.estimatedCost.isFinite() && it.estimatedCost >= 0) })
            require(game.nominationOrder.distinct().size == game.nominationOrder.size && game.nominationOrder.all { it in ids })
            require(game.nominationIndex in 0..6 && game.defenseCandidates.distinct().size == game.defenseCandidates.size && game.defenseCandidates.all { it in ids })
            require(game.nominationPlans.all { (voter, targets) -> voter in ids && !game.player(voter).isHuman &&
                targets.distinct().size == targets.size && targets.all { it in ids && it != voter } })
            val expectedNominations = game.nominationOrder.flatMap { candidate ->
                game.nominationOrder.filter { it != candidate }.map { voter -> voter to candidate }
            }
            require(game.nominationVotes.map { it.voterId to it.candidateId } == expectedNominations.take(game.nominationVotes.size))
            require(game.nominationVotes.size <= expectedNominations.size)
            require(game.nominationVotes.all { vote -> game.player(vote.voterId).isHuman ||
                game.nominationPlans[vote.voterId]?.let { vote.approved == (vote.candidateId in it) } == true })
            if (game.phase in listOf(Phase.NOMINATION, Phase.DEFENSE, Phase.FINAL_VOTING)) {
                val voters = game.living.map { it.id }.toSet()
                require(game.nominationOrder.toSet() == voters && game.nominationOrder == game.discussionOrder)
                require(game.nominationPlans.values.flatten().all { it in voters })
                if (game.phase == Phase.NOMINATION) {
                    require(game.nominationIndex < voters.size && game.votes.isEmpty() && game.defenseCandidates.isEmpty())
                    require(game.nominationVotes.size in (game.nominationIndex * (voters.size - 1)) until ((game.nominationIndex + 1) * (voters.size - 1)))
                } else {
                    require(game.nominationVotes.size == voters.size * (voters.size - 1))
                    val totals = game.nominationVotes.filter { it.approved }.groupingBy { it.candidateId }.eachCount()
                    val cutoff = totals.values.sortedDescending().let { it.getOrNull(1) ?: it.firstOrNull() }
                    require(cutoff != null && game.defenseCandidates == game.nominationOrder.filter { (totals[it] ?: 0) >= cutoff })
                    if (game.phase == Phase.DEFENSE) require(game.turn < game.defenseCandidates.size && game.votes.isEmpty())
                    else {
                        require(game.votes.keys == game.nominationOrder.take(game.votes.size).toSet() && game.votes.size < voters.size)
                        require(game.votes.all { (id, target) -> target == GameEngine.ABSTAIN || target in GameEngine.legalTargets(game, id) })
                    }
                }
            }
            if (game.phase == Phase.VOTING) {
                val voters = game.living.map { it.id }
                require(game.votes.keys == voters.take(game.votes.size).toSet() && game.votes.size < voters.size)
                require(game.votes.all { (id, target) -> target in GameEngine.legalTargets(game, id) })
            }
            if (game.phase == Phase.NIGHT) {
                val actors = GameEngine.nightActors(game).map { it.id }
                require(game.nightActions.keys.all { it in actors } && game.nightActions.size < actors.size)
                require(game.nightActions.all { (id, target) -> target in GameEngine.legalTargets(game, id) })
            }
            if (game.phase == Phase.DISCUSSION) {
                require(game.discussionOrder.size == game.living.size && game.discussionOrder.toSet() == game.living.map { it.id }.toSet())
                require(game.turn < game.discussionOrder.size)
            }
            require(if (game.phase == Phase.FINISHED) game.winner == GameEngine.winningTeam(game) && game.winner != null else game.winner == null && GameEngine.winningTeam(game) == null)
            // Restore authoritative character definitions, never executable or
            // altered persona instructions from an imported save.
            game.copy(players = game.players.map { player -> player.copy(character =
                if (player.isHuman) Characters.human.copy(name = PlayerNames.normalize(player.character.name))
                else Characters.all.single { it.id == player.character.id }.let { current ->
                    // Finish games created before the display-name change with
                    // their original names so old dialogue stays unambiguous.
                    val legacyName = legacyCharacterNames[player.character.id]
                        ?.takeIf { it == player.character.name }
                    current.copy(name = legacyName ?: current.name)
                }) })
        }
        return copy(playerName = normalizedPlayerName, game = restored)
    }

    companion object {
        private val legacyCharacterNames = mapOf("reza" to "رضا", "ali" to "علی")
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(raw: String): SavedApp? = try {
            if (raw.isBlank() || raw.length > 850_000) null else json.decodeFromString<SavedApp>(raw).validated()
        } catch (_: Exception) { null }
    }
}
