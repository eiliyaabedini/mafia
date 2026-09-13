package ir.iact.mafiagame.domain

/** A public table name; never account identity or an instruction to an agent. */
object PlayerNames {
    fun normalize(value: String) = value.replace('ي', 'ی').replace('ك', 'ک')
        .trim().replace(Regex("\\s+"), " ")

    fun valid(value: String): Boolean {
        val name = normalize(value)
        val reserved = Characters.all.map { it.name } + listOf("شما", "بازیکن مهمان", "مافیا", "شهروند", "کارآگاه", "دکتر")
        return name.length in 2..24 && name.any { it.isLetter() } && name !in reserved &&
            name.all { (it.isLetter() && (it in '\u0600'..'\u06FF' || it in 'a'..'z' || it in 'A'..'Z')) ||
                it.isDigit() || it == ' ' || it == '\u200C' }
    }
}
