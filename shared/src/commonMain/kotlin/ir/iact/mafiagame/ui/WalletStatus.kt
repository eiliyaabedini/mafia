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

/** Compact USD total for a finished game, with extra precision below one cent. */
fun gameCostAmount(cost: Double): String {
    if (!cost.isFinite() || cost < 0.0) return "$0.00"
    val units = (cost * 10_000.0).roundToLong()
    val whole = units / 10_000
    val fraction = (units % 10_000).toString().padStart(4, '0').trimEnd('0').padEnd(2, '0')
    return "$$whole.$fraction"
}
