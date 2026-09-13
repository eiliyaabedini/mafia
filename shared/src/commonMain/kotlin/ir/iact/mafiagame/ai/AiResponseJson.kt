package ir.iact.mafiagame.ai

import kotlinx.serialization.json.*

/** Format repair, not answer inference. Discard prose only around one explicit final JSON fence. */
internal object AiResponseJson {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { allowTrailingComma = true }
    private val finalFence = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```\\s*$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): JsonElement {
        require(raw.length <= 24_000) { "AI_BAD_JSON" }
        val text = raw.trim().removePrefix("\uFEFF")
        val fence = finalFence.find(text)
        val cleaned = if (fence != null) {
            val prefix = text.take(fence.range.first)
            // Never pick among competing JSON answers, incomplete objects,
            // multiple code blocks, or a block followed by another conclusion.
            require(text.windowed(3).count { it == "```" } == 2 &&
                prefix.none { it == '{' || it == '}' || it == '[' || it == ']' }) { "AI_BAD_JSON" }
            fence.groupValues[1].trim()
        } else text
        return try { json.parseToJsonElement(cleaned) }
        catch (_: Exception) { throw IllegalArgumentException("AI_BAD_JSON") }
    }
}
