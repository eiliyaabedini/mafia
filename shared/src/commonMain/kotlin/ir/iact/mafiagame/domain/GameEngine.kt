package ir.iact.mafiagame.domain

import kotlin.random.Random

/** Pure state transitions. The AI cannot assign roles, advance phases or apply eliminations. */
object GameEngine {
    const val ABSTAIN = "abstain"
    const val DISCUSSION_PASSES = 2
    const val MAX_SPEECH_CHARS = 420
    private val roles = listOf(Role.MAFIA, Role.MAFIA, Role.DETECTIVE, Role.DOCTOR, Role.CITIZEN, Role.CITIZEN, Role.CITIZEN)

    fun newGame(seed: Int = Random.nextInt(), characters: List<CharacterProfile> = Characters.all, humanName: String = "بازیکن مهمان"): Game {
        require(characters.size == 6 && characters.map { it.id }.distinct().size == 6)
        val random = Random(seed)
        val shuffled = roles.shuffled(random)
        val seating = listOf(Characters.human.copy(name = PlayerNames.normalize(humanName).takeIf { PlayerNames.valid(it) } ?: "بازیکن مهمان")) + characters.shuffled(random)
        return Game("game-$seed", seed, seating.mapIndexed { index, character ->
            Player("player_$index", character, shuffled[index], index == 0)
        })
    }

    fun actor(game: Game): Player? = when (game.phase) {
        Phase.DISCUSSION -> game.discussionPlayers.getOrNull(game.turn)
        Phase.DEFENSE -> game.defenseCandidates.getOrNull(game.turn)?.let(game::player)
        Phase.NOMINATION -> game.ballotOrder.firstOrNull { player ->
            player.id != game.nominationCandidate?.id && game.nominationVotes.none {
                it.candidateId == game.nominationCandidate?.id && it.voterId == player.id
            }
        }
        Phase.FINAL_VOTING -> game.ballotOrder.firstOrNull { it.id !in game.votes }
        Phase.VOTING -> game.living.firstOrNull { it.id !in game.votes }
        Phase.NIGHT -> nightActors(game).firstOrNull { it.id !in game.nightActions }
        else -> null
    }

    fun legalTargets(game: Game, playerId: String): List<String> {
        val player = game.player(playerId)
        if (!player.isAlive) return emptyList()
        return when (game.phase) {
            Phase.VOTING, Phase.NOMINATION -> game.living.filter { it.id != playerId }
            Phase.FINAL_VOTING -> game.living.filter { it.id in game.defenseCandidates && it.id != playerId }
            Phase.NIGHT -> when (player.role) {
                Role.MAFIA -> game.living.filter { it.role != Role.MAFIA }
                Role.DOCTOR -> game.living
                Role.DETECTIVE -> game.living.filter { it.id != playerId }
                Role.CITIZEN -> emptyList()
            }
            else -> emptyList()
        }.map { it.id }
    }

    fun nightActors(game: Game): List<Player> =
        listOf(Role.MAFIA, Role.DOCTOR, Role.DETECTIVE).flatMap { role ->
            game.living.filter { it.role == role }
        }

    fun contextFor(game: Game, playerId: String): AgentContext {
        val player = game.player(playerId)
        require(player.isAlive && !player.isHuman)
        return AgentContext(
            player.id, player.character, player.role,
            if (player.role == Role.MAFIA) game.players.filter { it.role == Role.MAFIA && it.id != playerId }.map { it.id } else emptyList(),
            if (player.role == Role.DETECTIVE) game.investigations[playerId].orEmpty() else emptyList(),
            game.beliefs[playerId] ?: AgentBeliefs(),
            game.players.map { PublicPlayer(it.id, it.character.name, it.isAlive, isHuman = it.isHuman) },
            game.day, game.pass, game.phase, game.conversation, legalTargets(game, playerId),
            game.defenseCandidates, game.phase == Phase.FINAL_VOTING, game.discussionOrder,
        )
    }

    fun beginDay(game: Game): Game {
        require(game.phase == Phase.REVEAL || game.phase == Phase.DAWN)
        val day = if (game.phase == Phase.DAWN) game.day + 1 else game.day
        // Each day gets an independent, reproducible draw. Either pass follows
        // that same public order; no identity or secret role receives priority.
        val order = game.living.map { it.id }.shuffled(Random(game.seed xor (day * 104729)))
        return game.copy(phase = Phase.DISCUSSION, day = day, pass = 1, turn = 0, votes = emptyMap(), nightActions = emptyMap(),
            discussionOrder = order, nominationOrder = emptyList(), nominationIndex = 0,
            nominationVotes = emptyList(), nominationPlans = emptyMap(), defenseCandidates = emptyList(),
            conversation = game.conversation + GameMessage(EventKind.DAY_STARTED, day))
    }

    fun speak(game: Game, playerId: String, text: String): Game {
        require(game.phase in listOf(Phase.DISCUSSION, Phase.DEFENSE) && actor(game)?.id == playerId) { "Not this player's speaking turn" }
        val content = text.trim()
        require(content.length <= if (game.player(playerId).isHuman) 1000 else MAX_SPEECH_CHARS) { "Speech too long" }
        val defense = game.phase == Phase.DEFENSE
        val kind = if (defense) { if (content.isEmpty()) EventKind.DEFENSE_SKIP else EventKind.DEFENSE_SPEECH }
            else if (content.isEmpty()) EventKind.SKIP else EventKind.SPEECH
        val next = game.copy(conversation = game.conversation + GameMessage(kind, game.day, playerId, text = content))
        if (defense) return if (game.turn + 1 < game.defenseCandidates.size) next.copy(turn = game.turn + 1)
            else next.copy(phase = Phase.FINAL_VOTING, turn = 0, votes = emptyMap(),
                conversation = next.conversation + GameMessage(EventKind.FINAL_VOTE_STARTED, game.day))
        return when {
            game.turn + 1 < game.discussionPlayers.size -> next.copy(turn = game.turn + 1)
            game.pass < DISCUSSION_PASSES -> next.copy(turn = 0, pass = game.pass + 1)
            else -> next.copy(phase = Phase.NOMINATION, turn = 0, nominationOrder = game.discussionOrder,
                conversation = next.conversation + GameMessage(EventKind.NOMINATION_STARTED, game.day))
        }
    }

    /** One AI decision per voter; choices are revealed candidate by candidate, never leaked in context. */
    fun planNominations(game: Game, playerId: String, approvedTargets: List<String>): Game {
        require(game.phase == Phase.NOMINATION && actor(game)?.id == playerId && !game.player(playerId).isHuman)
        require(playerId !in game.nominationPlans)
        require(approvedTargets.distinct().size == approvedTargets.size && approvedTargets.all { it in legalTargets(game, playerId) })
        return game.copy(nominationPlans = game.nominationPlans + (playerId to approvedTargets))
    }

    fun nominate(game: Game, playerId: String, approved: Boolean): Game {
        require(game.phase == Phase.NOMINATION && actor(game)?.id == playerId) { "Not this player's nomination turn" }
        val candidate = requireNotNull(game.nominationCandidate).id
        require(candidate != playerId && game.player(candidate).isAlive)
        if (!game.player(playerId).isHuman) {
            val plan = requireNotNull(game.nominationPlans[playerId])
            require(approved == (candidate in plan))
        }
        val next = game.copy(nominationVotes = game.nominationVotes + NominationVote(playerId, candidate, approved),
            conversation = game.conversation + GameMessage(if (approved) EventKind.NOMINATION_YES else EventKind.NOMINATION_NO,
                game.day, playerId, candidate))
        if (actor(next) != null) return next
        if (game.nominationIndex + 1 < game.nominationOrder.size) return next.copy(nominationIndex = game.nominationIndex + 1)
        val totals = next.nominationVotes.filter { it.approved }.groupingBy { it.candidateId }.eachCount()
        val cutoff = totals.values.sortedDescending().let { it.getOrNull(1) ?: it.firstOrNull() }
        if (cutoff == null) return startNight(next.copy(conversation = next.conversation + GameMessage(EventKind.NO_ELIMINATION, game.day)))
        val finalists = game.nominationOrder.filter { (totals[it] ?: 0) >= cutoff }
        // Never choose an arbitrary finalist from a tie at the cutoff. If that
        // tie produces three or more candidates, the day ends without defense.
        if (finalists.size > 2) return startNight(next.copy(
            conversation = next.conversation + GameMessage(EventKind.VOTE_TIED, game.day),
        ))
        return next.copy(phase = Phase.DEFENSE, turn = 0, defenseCandidates = finalists,
            conversation = next.conversation + finalists.map { GameMessage(EventKind.DEFENSE_STARTED, game.day, targetId = it) })
    }

    fun finalVote(game: Game, playerId: String, targetId: String): Game {
        require(game.phase == Phase.FINAL_VOTING && actor(game)?.id == playerId) { "Not this player's final vote" }
        require(targetId == ABSTAIN || targetId in legalTargets(game, playerId)) { "Illegal final vote" }
        val next = game.copy(votes = game.votes + (playerId to targetId), conversation = game.conversation +
            GameMessage(if (targetId == ABSTAIN) EventKind.FINAL_ABSTAIN else EventKind.FINAL_VOTE, game.day, playerId,
                targetId.takeUnless { it == ABSTAIN }))
        if (actor(next) != null) return next
        val totals = next.votes.values.filter { it != ABSTAIN }.groupingBy { it }.eachCount()
        val leaders = totals.filterValues { it == totals.values.maxOrNull() }.keys
        val resolved = when (leaders.size) {
            0 -> next.copy(conversation = next.conversation + GameMessage(EventKind.NO_ELIMINATION, game.day))
            1 -> next.copy(players = next.players.map { if (it.id == leaders.single()) it.copy(isAlive = false) else it },
                conversation = next.conversation + GameMessage(EventKind.ELIMINATED, game.day, targetId = leaders.single()))
            else -> next.copy(conversation = next.conversation + GameMessage(EventKind.VOTE_TIED, game.day))
        }
        return finishIfWon(resolved) ?: startNight(resolved)
    }

    private fun startNight(game: Game) = game.copy(phase = Phase.NIGHT, nightActions = emptyMap(),
        conversation = game.conversation + GameMessage(EventKind.NIGHT_STARTED, game.day))

    /** Compatibility for unfinished ballots saved before nomination/defense voting was added. */
    fun vote(game: Game, playerId: String, targetId: String): Game {
        require(game.phase == Phase.VOTING && actor(game)?.id == playerId) { "Not this player's voting turn" }
        require(targetId in legalTargets(game, playerId)) { "Illegal vote" }
        val next = game.copy(votes = game.votes + (playerId to targetId))
        if (next.votes.size < next.living.size) return next
        val totals = next.votes.values.groupingBy { it }.eachCount()
        val leaders = totals.filterValues { it == totals.values.maxOrNull() }.keys.toList()
        val ballots = next.votes.map { (voter, target) -> GameMessage(EventKind.VOTE, game.day, voter, target) }
        // A tied day vote eliminates nobody. Ballots only become public when everyone has voted.
        val resolved = if (leaders.size != 1) next.copy(conversation = next.conversation + ballots + GameMessage(EventKind.VOTE_TIED, game.day))
        else next.copy(players = next.players.map { if (it.id == leaders.single()) it.copy(isAlive = false) else it },
            conversation = next.conversation + ballots + GameMessage(EventKind.ELIMINATED, game.day, targetId = leaders.single()))
        return finishIfWon(resolved) ?: resolved.copy(phase = Phase.NIGHT, nightActions = emptyMap(),
            conversation = resolved.conversation + GameMessage(EventKind.NIGHT_STARTED, game.day))
    }

    fun nightAction(game: Game, playerId: String, targetId: String): Game {
        require(game.phase == Phase.NIGHT && actor(game)?.id == playerId) { "Not this player's night turn" }
        require(targetId in legalTargets(game, playerId)) { "Illegal night target" }
        val next = game.copy(nightActions = game.nightActions + (playerId to targetId))
        return if (actor(next) == null) resolveNight(next) else next
    }

    private fun resolveNight(game: Game): Game {
        val proposed = game.nightActions.filterKeys { game.player(it).role == Role.MAFIA }.values
        val totals = proposed.groupingBy { it }.eachCount()
        val tied = totals.filterValues { it == totals.values.maxOrNull() }.keys.sorted()
        // Every living Mafia must have submitted a valid attack before dawn.
        // A missing action must never masquerade as a successful Doctor save.
        require(tied.isNotEmpty()) { "MISSING_MAFIA_ACTION" }
        val victim = tied.random(Random(game.seed xor (game.day * 7919)))
        val protection = game.nightActions.filterKeys { game.player(it).role == Role.DOCTOR }.values.toSet()
        val killed = victim.takeUnless { it in protection }
        var investigations = game.investigations
        game.nightActions.filterKeys { game.player(it).role == Role.DETECTIVE }.forEach { (detective, target) ->
            investigations = investigations + (detective to (investigations[detective].orEmpty() + Investigation(game.day, target, game.player(target).role == Role.MAFIA)))
        }
        val next = game.copy(phase = Phase.DAWN,
            players = game.players.map { if (it.id == killed) it.copy(isAlive = false) else it },
            investigations = investigations,
            nightActions = emptyMap(),
            conversation = game.conversation + GameMessage(if (killed == null) EventKind.NIGHT_SAVED else EventKind.NIGHT_KILLED, game.day, targetId = killed))
        return finishIfWon(next) ?: next
    }

    fun winningTeam(game: Game): Team? {
        val mafia = game.living.count { it.role == Role.MAFIA }
        return when { mafia == 0 -> Team.TOWN; mafia >= game.living.size - mafia -> Team.MAFIA; else -> null }
    }

    private fun finishIfWon(game: Game): Game? = winningTeam(game)?.let {
        game.copy(phase = Phase.FINISHED, winner = it, nightActions = emptyMap(),
            conversation = game.conversation + GameMessage(EventKind.GAME_ENDED, game.day))
    }
}
