package dev.detour.core.alternatives

/** A past choice of an alternative, possibly still waiting for its "how was it?" check-in. */
data class ChoiceRecord(
    val id: Long,
    val alternativeId: Long,
    val feeling: Feeling,
    val chosenAtMs: Long,
    val outcome: Outcome?,
)

/**
 * Picks the choice to ask about when you next unlock the phone: the most recent one without an
 * outcome, chosen at least [minDelayMs] ago (so you had time to do it) and at most [maxAgeMs]
 * ago (after that the memory is too fuzzy to be useful).
 */
object CheckInPicker {
    const val MIN_DELAY_MS = 10 * 60_000L
    const val MAX_AGE_MS = 12 * 60 * 60_000L

    fun pick(
        choices: List<ChoiceRecord>,
        nowMs: Long,
        minDelayMs: Long = MIN_DELAY_MS,
        maxAgeMs: Long = MAX_AGE_MS,
    ): ChoiceRecord? {
        val latest = choices.maxByOrNull { it.chosenAtMs } ?: return null
        if (latest.outcome != null) return null
        val age = nowMs - latest.chosenAtMs
        return latest.takeIf { age in minDelayMs..maxAgeMs }
    }
}
