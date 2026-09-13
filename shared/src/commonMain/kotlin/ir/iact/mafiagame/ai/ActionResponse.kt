package ir.iact.mafiagame.ai

import ir.iact.mafiagame.domain.AgentContext
import ir.iact.mafiagame.domain.GameEngine
import kotlinx.serialization.json.*

/** Local format repair only. Never guesses a vote from reasoning or chooses a fallback target. */
internal object ActionResponse {
    fun parse(raw: String, context: AgentContext): String {
        require(raw.length <= 24_000) { "AI_BAD_JSON" }
        val text = raw.trim().removePrefix("\uFEFF")
        // An exact allowed ID is already unambiguous and costs nothing to repair.
        if (text in context.legalTargets || context.canAbstain && text == GameEngine.ABSTAIN) return text
        val element = AiResponseJson.parse(text)
        fun target(value: JsonElement?): String? {
            val candidate = when (value) {
                is JsonPrimitive -> value.takeIf { it.isString }?.content?.trim()
                is JsonObject -> {
                    val ids = listOf("playerId", "id").mapNotNull { key ->
                        (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    }.distinct()
                    ids.singleOrNull()
                }
                else -> null
            } ?: return null
            if (candidate in context.legalTargets || context.canAbstain && candidate == GameEngine.ABSTAIN) return candidate
            return context.players.filter { it.id in context.legalTargets && it.name == candidate }.singleOrNull()?.id
        }
        val values = if (element is JsonObject)
            listOf("target", "vote", "targetId", "playerId").filter { it in element }.map { element[it] }
            else listOf(element)
        require(values.isNotEmpty()) { "AI_WRONG_ACTION" }
        val choices = values.map { target(it) }
        require(choices.all { it != null } && choices.distinct().size == 1) { "AI_ILLEGAL_TARGET" }
        return choices.first()!!
    }
}
