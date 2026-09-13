package ir.iact.mafiagame.ui

import ir.iact.mafiagame.ai.WalletBalance
import kotlin.math.abs
import kotlin.math.roundToLong

/** The compact brand chip mirrors AI Pass's English, two-decimal balance display. */
fun walletBadgeAmount(balance: WalletBalance): String {
    val value = balance.remainingUsd.toDoubleOrNull()
    if (value == null || !value.isFinite() || abs(value) > 9_000_000_000_000.0) return "$${balance.remainingUsd.take(10)}"
    val cents = (value * 100.0).roundToLong()
    val magnitude = abs(cents)
    val sign = if (cents < 0) "-" else ""
    return "$${sign}${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
}
