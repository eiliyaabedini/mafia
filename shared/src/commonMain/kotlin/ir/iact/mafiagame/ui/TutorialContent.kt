package ir.iact.mafiagame.ui

import mafiagame.shared.generated.resources.*
import org.jetbrains.compose.resources.StringResource

internal enum class TutorialArt { TABLE, ROLES, CYCLE, CLUES }

internal data class TutorialLesson(
    val id: String,
    val title: StringResource,
    val body: StringResource,
    val art: TutorialArt,
)

internal data class TutorialChat(val characterId: String, val text: StringResource)

internal data class TutorialChoice(
    val label: StringResource,
    val feedback: StringResource,
    val isRecommended: Boolean,
)

internal data class TutorialScenario(
    val id: String,
    val title: StringResource,
    val prompt: StringResource,
    val messages: List<TutorialChat>,
    val choices: List<TutorialChoice>,
    val takeaway: StringResource,
    val art: TutorialArt = TutorialArt.CLUES,
)

/** Authored, offline examples. No game state, model requests, or hidden-role inference. */
internal val basicLessons = listOf(
    TutorialLesson("welcome", Res.string.tutorial_basic_welcome_title, Res.string.tutorial_basic_welcome_body, TutorialArt.TABLE),
    TutorialLesson("citizen", Res.string.tutorial_basic_citizen_title, Res.string.tutorial_basic_citizen_body, TutorialArt.TABLE),
    TutorialLesson("roles", Res.string.tutorial_basic_roles_title, Res.string.tutorial_basic_roles_body, TutorialArt.ROLES),
    TutorialLesson("cycle", Res.string.tutorial_basic_cycle_title, Res.string.tutorial_basic_cycle_body, TutorialArt.CYCLE),
    TutorialLesson("night", Res.string.tutorial_basic_night_title, Res.string.tutorial_basic_night_body, TutorialArt.CYCLE),
    TutorialLesson("outcomes", Res.string.tutorial_basic_outcomes_title, Res.string.tutorial_basic_outcomes_body, TutorialArt.CLUES),
)

internal val practiceScenarios = listOf(
    TutorialScenario(
        id = "listen",
        title = Res.string.tutorial_practice_listen_title,
        prompt = Res.string.tutorial_practice_listen_prompt,
        messages = listOf(
            TutorialChat("sara", Res.string.tutorial_practice_listen_sara),
            TutorialChat("reza", Res.string.tutorial_practice_listen_reza),
            TutorialChat("arman", Res.string.tutorial_practice_listen_arman),
        ),
        choices = listOf(
            TutorialChoice(Res.string.tutorial_practice_listen_choice_mismatch, Res.string.tutorial_practice_listen_feedback_mismatch, true),
            TutorialChoice(Res.string.tutorial_practice_listen_choice_mafia, Res.string.tutorial_practice_listen_feedback_mafia, false),
            TutorialChoice(Res.string.tutorial_practice_listen_choice_town, Res.string.tutorial_practice_listen_feedback_town, false),
        ),
        takeaway = Res.string.tutorial_practice_listen_takeaway,
    ),
    TutorialScenario(
        id = "question",
        title = Res.string.tutorial_practice_question_title,
        prompt = Res.string.tutorial_practice_question_prompt,
        messages = emptyList(),
        choices = listOf(
            TutorialChoice(Res.string.tutorial_practice_question_choice_accuse, Res.string.tutorial_practice_question_feedback_accuse, false),
            TutorialChoice(Res.string.tutorial_practice_question_choice_ask, Res.string.tutorial_practice_question_feedback_ask, true),
            TutorialChoice(Res.string.tutorial_practice_question_choice_claim, Res.string.tutorial_practice_question_feedback_claim, false),
        ),
        takeaway = Res.string.tutorial_practice_question_takeaway,
    ),
    TutorialScenario(
        id = "vote",
        title = Res.string.tutorial_practice_vote_title,
        prompt = Res.string.tutorial_practice_vote_prompt,
        messages = emptyList(),
        choices = listOf(
            TutorialChoice(Res.string.tutorial_practice_vote_choice_reza, Res.string.tutorial_practice_vote_feedback_reza, false),
            TutorialChoice(Res.string.tutorial_practice_vote_choice_both, Res.string.tutorial_practice_vote_feedback_both, false),
            TutorialChoice(Res.string.tutorial_practice_vote_choice_tie, Res.string.tutorial_practice_vote_feedback_tie, true),
        ),
        takeaway = Res.string.tutorial_practice_vote_takeaway,
        art = TutorialArt.TABLE,
    ),
    TutorialScenario(
        id = "night",
        title = Res.string.tutorial_practice_night_title,
        prompt = Res.string.tutorial_practice_night_prompt,
        messages = emptyList(),
        choices = listOf(
            TutorialChoice(Res.string.tutorial_practice_night_choice_sara, Res.string.tutorial_practice_night_feedback_sara, false),
            TutorialChoice(Res.string.tutorial_practice_night_choice_alive, Res.string.tutorial_practice_night_feedback_alive, true),
            TutorialChoice(Res.string.tutorial_practice_night_choice_mafia, Res.string.tutorial_practice_night_feedback_mafia, false),
        ),
        takeaway = Res.string.tutorial_practice_night_takeaway,
        art = TutorialArt.CYCLE,
    ),
    TutorialScenario(
        id = "finish",
        title = Res.string.tutorial_practice_finish_title,
        prompt = Res.string.tutorial_practice_finish_prompt,
        messages = emptyList(),
        choices = listOf(
            TutorialChoice(Res.string.tutorial_practice_finish_choice_team, Res.string.tutorial_practice_finish_feedback_team, true),
            TutorialChoice(Res.string.tutorial_practice_finish_choice_alive, Res.string.tutorial_practice_finish_feedback_alive, false),
            TutorialChoice(Res.string.tutorial_practice_finish_choice_guess, Res.string.tutorial_practice_finish_feedback_guess, false),
        ),
        takeaway = Res.string.tutorial_practice_finish_takeaway,
        art = TutorialArt.ROLES,
    ),
)

/** Each advanced scenario starts a separate situation with explicitly stated knowledge. */
internal val strategyScenarios = listOf(
    TutorialScenario(
        id = "evidence",
        title = Res.string.tutorial_strategy_evidence_title,
        prompt = Res.string.tutorial_strategy_evidence_prompt,
        messages = listOf(
            TutorialChat("arman", Res.string.tutorial_strategy_evidence_arman),
            TutorialChat("mina", Res.string.tutorial_strategy_evidence_mina),
        ),
        choices = listOf(
            TutorialChoice(Res.string.tutorial_strategy_evidence_choice_silence, Res.string.tutorial_strategy_evidence_feedback_silence, false),
            TutorialChoice(Res.string.tutorial_strategy_evidence_choice_ask, Res.string.tutorial_strategy_evidence_feedback_ask, true),
            TutorialChoice(Res.string.tutorial_strategy_evidence_choice_arman, Res.string.tutorial_strategy_evidence_feedback_arman, false),
        ),
        takeaway = Res.string.tutorial_strategy_evidence_takeaway,
    ),
    TutorialScenario(
        id = "detective",
        title = Res.string.tutorial_strategy_detective_title,
        prompt = Res.string.tutorial_strategy_detective_prompt,
        messages = emptyList(),
        choices = listOf(
            TutorialChoice(Res.string.tutorial_strategy_detective_choice_doctor, Res.string.tutorial_strategy_detective_feedback_doctor, false),
            TutorialChoice(Res.string.tutorial_strategy_detective_choice_public, Res.string.tutorial_strategy_detective_feedback_public, false),
            TutorialChoice(Res.string.tutorial_strategy_detective_choice_risk, Res.string.tutorial_strategy_detective_feedback_risk, true),
        ),
        takeaway = Res.string.tutorial_strategy_detective_takeaway,
        art = TutorialArt.ROLES,
    ),
    TutorialScenario(
        id = "teammate",
        title = Res.string.tutorial_strategy_teammate_title,
        prompt = Res.string.tutorial_strategy_teammate_prompt,
        messages = listOf(
            TutorialChat("sara", Res.string.tutorial_strategy_teammate_sara_before),
            TutorialChat("sara", Res.string.tutorial_strategy_teammate_sara_after),
            TutorialChat("arman", Res.string.tutorial_strategy_teammate_arman),
        ),
        choices = listOf(
            TutorialChoice(Res.string.tutorial_strategy_teammate_choice_ask, Res.string.tutorial_strategy_teammate_feedback_ask, true),
            TutorialChoice(Res.string.tutorial_strategy_teammate_choice_defend, Res.string.tutorial_strategy_teammate_feedback_defend, false),
            TutorialChoice(Res.string.tutorial_strategy_teammate_choice_reveal, Res.string.tutorial_strategy_teammate_feedback_reveal, false),
        ),
        takeaway = Res.string.tutorial_strategy_teammate_takeaway,
    ),
)
