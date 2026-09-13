package ir.iact.mafiagame.ai

/** Fixed thinking models belong to characters, independent of seats and roles. */
internal object CharacterModels {
    private val assignments = mapOf(
        "arman" to AiModel("gpt-5.6-luna", "GPT-5.6 Luna"),
        "sara" to AiModel("gpt-5.6-luna", "GPT-5.6 Luna"),
        "reza" to AiModel("deepseek-v4-flash-0731", "DeepSeek V4 Flash"),
        "nika" to AiModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite"),
        "ali" to AiModel("deepseek-v4-flash-0731", "DeepSeek V4 Flash"),
        "mina" to AiModel("gpt-5.6-luna", "GPT-5.6 Luna"),
    )

    fun forCharacter(characterId: String): AiModel? = assignments[characterId]

    /** Validate every assigned exact ID. Never replace a missing model. */
    fun resolve(catalog: List<AiModel>): Map<String, AiModel> {
        val available = catalog.associateBy { it.id }
        return assignments.mapValues { (_, assigned) ->
            val model = available[assigned.id] ?: error("NO_MODELS")
            model.copy(displayName = assigned.displayName)
        }
    }
}
