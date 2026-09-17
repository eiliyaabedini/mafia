package ir.iact.mafiagame

import ir.iact.mafiagame.domain.*
import kotlin.test.*

/** Covers the cafe rules the engine is supposed to implement, not the AI layer. */
class GameRulesTest {

    private fun start(seed: Int = 7) = GameEngine.beginDay(GameEngine.newGame(seed, humanName = "کاربر"))

    private fun runDiscussion(from: Game): Game {
        var game = from
        while (game.phase == Phase.DISCUSSION) {
            game = GameEngine.speak(game, assertNotNull(GameEngine.actor(game)).id, "حرف")
        }
        return game
    }

    private fun runNomination(from: Game, approvals: Map<String, List<String>>): Game {
        var game = from
        while (game.phase == Phase.NOMINATION) {
            val actor = assertNotNull(GameEngine.actor(game))
            val wanted = approvals[actor.id].orEmpty()
            if (!actor.isHuman && actor.id !in game.nominationPlans) {
                game = GameEngine.planNominations(game, actor.id, wanted)
            }
            val candidate = assertNotNull(game.nominationCandidate).id
            game = GameEngine.nominate(game, actor.id, candidate in wanted)
        }
        return game
    }

    private fun runDefense(from: Game): Game {
        var game = from
        while (game.phase == Phase.DEFENSE) {
            game = GameEngine.speak(game, assertNotNull(GameEngine.actor(game)).id, "دفاع")
        }
        return game
    }

    private fun dayTwoNomination(approvals: Map<String, List<String>>): Game =
        runNomination(runDiscussion(GameEngine.beginDay(runDiscussion(start()))), approvals)

    @Test
    fun sevenPlayersAreOneGodfatherAndOneMafia() {
        assertEquals(
            mapOf(Role.GODFATHER to 1, Role.MAFIA to 1, Role.DETECTIVE to 1, Role.DOCTOR to 1, Role.CITIZEN to 3),
            GameEngine.newGame(1).players.groupingBy { it.role }.eachCount(),
        )
    }

    @Test
    fun dayOneIsOneRoundWithNoVoteAndNoDeath() {
        assertEquals(1, GameEngine.passesFor(1))
        assertEquals(2, GameEngine.passesFor(2))
        var game = start()
        repeat(game.living.size) {
            assertEquals(Phase.DISCUSSION, game.phase)
            assertEquals(1, game.pass, "day one must not open a second round")
            game = GameEngine.speak(game, assertNotNull(GameEngine.actor(game)).id, "سلام")
        }
        assertEquals(Phase.DAWN, game.phase)
        assertEquals(7, game.living.size)
        assertTrue(game.conversation.any { it.kind == EventKind.INTRO_NIGHT })
        assertTrue(game.conversation.none { it.kind == EventKind.NOMINATION_STARTED })
        assertTrue(game.conversation.none { it.kind == EventKind.NIGHT_KILLED })
        assertTrue(game.conversation.none { it.kind == EventKind.ELIMINATED })
    }

    @Test
    fun dayTwoRunsTwoRoundsThenNomination() {
        val game = runDiscussion(GameEngine.beginDay(runDiscussion(start())))
        assertEquals(2, game.day)
        assertEquals(Phase.NOMINATION, game.phase)
    }

    @Test
    fun thresholdIsAboveFortyPercentOfTheLiving() {
        assertFalse(GameEngine.clearsThreshold(2, 7))
        assertTrue(GameEngine.clearsThreshold(3, 7))
        assertFalse(GameEngine.clearsThreshold(2, 5))
        assertTrue(GameEngine.clearsThreshold(3, 5))
        assertTrue(GameEngine.clearsThreshold(2, 4))
    }

    @Test
    fun everyCandidateAboveThresholdDefendsAndTheRestDoNot() {
        val game = dayTwoNomination(mapOf(
            "player_2" to listOf("player_1"),
            "player_3" to listOf("player_1"),
            "player_4" to listOf("player_1", "player_5"),
            "player_0" to listOf("player_5"),
            "player_6" to listOf("player_5"),
        ))
        assertEquals(Phase.DEFENSE, game.phase)
        // player_1 and player_5 each drew three approvals out of seven living.
        assertEquals(setOf("player_1", "player_5"), game.defenseCandidates.toSet())
    }

    @Test
    fun nobodyAboveThresholdSkipsStraightToNight() {
        val game = dayTwoNomination(mapOf(
            "player_2" to listOf("player_1"),
            "player_3" to listOf("player_1"),
        ))
        assertEquals(Phase.NIGHT, game.phase)
        assertTrue(game.conversation.any { it.kind == EventKind.NO_ELIMINATION })
    }

    @Test
    fun aLeaderUnderFortyPercentIsNotEliminated() {
        var game = runDefense(dayTwoNomination(mapOf(
            "player_2" to listOf("player_1"),
            "player_3" to listOf("player_1"),
            "player_4" to listOf("player_1"),
        )))
        assertEquals(Phase.FINAL_VOTING, game.phase)
        var cast = 0
        while (game.phase == Phase.FINAL_VOTING) {
            val actor = assertNotNull(GameEngine.actor(game))
            val legal = GameEngine.legalTargets(game, actor.id)
            // Only two of the seven back the single finalist; 40% needs three.
            val target = if (legal.isNotEmpty() && cast < 2) legal.first().also { cast++ } else GameEngine.ABSTAIN
            game = GameEngine.finalVote(game, actor.id, target)
        }
        assertEquals(7, game.living.size)
        assertTrue(game.conversation.any { it.kind == EventKind.NO_ELIMINATION })
    }

    @Test
    fun aLeaderAboveFortyPercentIsEliminated() {
        var game = runDefense(dayTwoNomination(mapOf(
            "player_2" to listOf("player_1"),
            "player_3" to listOf("player_1"),
            "player_4" to listOf("player_1"),
        )))
        while (game.phase == Phase.FINAL_VOTING) {
            val actor = assertNotNull(GameEngine.actor(game))
            val legal = GameEngine.legalTargets(game, actor.id)
            game = GameEngine.finalVote(game, actor.id, legal.firstOrNull() ?: GameEngine.ABSTAIN)
        }
        assertEquals(6, game.living.size)
        assertFalse(game.player("player_1").isAlive)
    }

    private fun nightOfDayTwo(seed: Int) =
        runNomination(runDiscussion(GameEngine.beginDay(runDiscussion(start(seed)))), emptyMap())

    private fun investigate(seed: Int, pick: (Game) -> String): Investigation {
        var game = nightOfDayTwo(seed)
        assertEquals(Phase.NIGHT, game.phase)
        val detective = game.players.single { it.role == Role.DETECTIVE }
        val wanted = pick(game)
        while (game.phase == Phase.NIGHT) {
            val actor = assertNotNull(GameEngine.actor(game))
            val target = if (actor.id == detective.id) wanted else GameEngine.legalTargets(game, actor.id).first()
            game = GameEngine.nightAction(game, actor.id, target)
        }
        return game.investigations.getValue(detective.id).single()
    }

    @Test
    fun theDetectiveIsToldTheGodfatherIsNotMafia() {
        val finding = investigate(11) { game -> game.players.single { it.role == Role.GODFATHER }.id }
        assertFalse(finding.isMafia, "the Godfather must read as innocent")
    }

    @Test
    fun theDetectiveCatchesThePlainMafia() {
        val finding = investigate(11) { game -> game.players.single { it.role == Role.MAFIA }.id }
        assertTrue(finding.isMafia)
    }

    @Test
    fun bothMafiaRolesCountTowardTheResult() {
        val game = GameEngine.newGame(3)
        assertNull(GameEngine.winningTeam(game))
        val mafiaGone = game.copy(players = game.players.map {
            if (it.role.isMafiaTeam) it.copy(isAlive = false) else it
        })
        assertEquals(Team.TOWN, GameEngine.winningTeam(mafiaGone))
        val survivor = game.players.first { it.role == Role.CITIZEN }.id
        val parity = game.copy(players = game.players.map {
            if (it.role == Role.GODFATHER || it.id == survivor) it else it.copy(isAlive = false)
        })
        assertEquals(Team.MAFIA, GameEngine.winningTeam(parity))
    }

    @Test
    fun theGodfatherAndMafiaShareTeammatesAndNightTargets() {
        val game = nightOfDayTwo(11)
        val godfather = game.players.single { it.role == Role.GODFATHER }
        val mafia = game.players.single { it.role == Role.MAFIA }
        listOf(godfather, mafia).filterNot { it.isHuman }.forEach { player ->
            val context = GameEngine.contextFor(game, player.id)
            val mate = if (player.id == godfather.id) mafia.id else godfather.id
            assertEquals(listOf(mate), context.teammates)
        }
        listOf(godfather, mafia).forEach { player ->
            val targets = GameEngine.legalTargets(game, player.id)
            assertFalse(godfather.id in targets)
            assertFalse(mafia.id in targets)
            assertEquals(5, targets.size)
        }
    }
}
