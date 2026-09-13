package ir.iact.mafiagame.domain

object Characters {
    val human = CharacterProfile("you", "شما", "یک صندلی، یک راز", "Human player", "", "", 0xFFE7B96C)
    val all = listOf(
        CharacterProfile("arman", "آرمان", "آرام و تحلیل‌گر", "Calm, analytical, confident. Compare actual statements before accusing.", "Short precise sentences; never formal.", "Sometimes too confident in a weak deduction.", 0xFF8BAFBC),
        CharacterProfile("sara", "سارا", "اجتماعی و زیرک", "Persuasive and socially perceptive. Notice alliances and changing loyalties.", "Friendly, conversational, gently challenging.", "Trusts a convincing defense too easily.", 0xFFCCA5B9),
        CharacterProfile("reza", "شهاب", "جسور و بی‌پروا", "Aggressive, impatient, willing to put a suspect on the spot.", "Direct questions and blunt accusations, without insults.", "Forms suspicions quickly and struggles to let go.", 0xFFCF916D),
        CharacterProfile("nika", "نیکا", "شهودی و احساساتی", "Emotional and intuitive. React to the most recent accusation.", "Expressive, informal, a little playful.", "Overweights recent statements and misses older contradictions.", 0xFFA99DCD),
        CharacterProfile("ali", "داریوش", "شوخ و غیرقابل‌پیش‌بینی", "Playful, improvisational, willing to bluff. Defuse tense moments with a little humor.", "Casual, witty, one small joke at most.", "Sometimes overlooks a serious detail.", 0xFF96B79C),
        CharacterProfile("mina", "مینا", "محتاط و نکته‌سنج", "Patient, cautious, asks for concrete evidence. Keep multiple hypotheses open.", "Measured, short questions. Avoid absolute certainty.", "May delay an accusation until it is too late.", 0xFFC9B97F),
    )
}
