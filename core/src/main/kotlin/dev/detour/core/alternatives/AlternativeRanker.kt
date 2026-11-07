package dev.detour.core.alternatives

import java.time.LocalTime

/** Plain evidence shown under an alternative once there's enough history. */
data class Evidence(val feeling: Feeling, val helped: Int, val total: Int) {
    fun describe(title: String): String =
        "When ${feeling.label}, “$title” helped $helped of the last $total times."
}

data class RankedAlternative(val alternative: Alternative, val score: Double, val evidence: Evidence?)

/**
 * Orders your alternatives for a feeling by what has actually helped you before.
 *
 * Score = smoothed success rate over the last [window] tries for that feeling:
 * (times "better" + 1) / (tries + 2). An untried alternative scores 0.5, so new ideas get a fair
 * chance. "Didn't do it" counts as a try that didn't help. Skipped check-ins are ignored.
 * Ride it out is never in this list; the screen always offers it separately.
 */
class AlternativeRanker(
    private val window: Int = 12,
    private val minEvidence: Int = 3,
) {
    fun rank(
        alternatives: List<Alternative>,
        feeling: Feeling,
        place: Place?,
        history: List<OutcomeRecord>,
        max: Int = 5,
        min: Int = 3,
    ): List<RankedAlternative> {
        val candidates = alternatives.filter { !it.isRideItOut }
        val scored = candidates.mapIndexed { index, alt -> Scored(index, scoreFor(alt, feeling, history)) }

        val order = compareByDescending<Scored> { it.ranked.score }
            .thenByDescending { it.ranked.alternative.fitsPlace(place) }
            .thenBy { it.index }

        val matching = scored.filter { feeling in it.ranked.alternative.feelings }.sortedWith(order)
        val others = scored.filter { feeling !in it.ranked.alternative.feelings }.sortedWith(order)

        val result = matching.take(max).toMutableList()
        if (result.size < min) result += others.take(min - result.size)
        return result.map { it.ranked }
    }

    private data class Scored(val index: Int, val ranked: RankedAlternative)

    private fun scoreFor(alt: Alternative, feeling: Feeling, history: List<OutcomeRecord>): RankedAlternative {
        val tries = history
            .filter { it.alternativeId == alt.id && it.feeling == feeling && it.outcome != Outcome.SKIPPED }
            .sortedByDescending { it.atMs }
            .take(window)
        val helped = tries.count { it.outcome == Outcome.BETTER }
        val score = (helped + 1.0) / (tries.size + 2.0)
        val evidence = if (tries.size >= minEvidence) Evidence(feeling, helped, tries.size) else null
        return RankedAlternative(alt, score, evidence)
    }

    companion object {
        /** The only place we can infer without location: late at night you're probably in bed. */
        fun inferPlace(time: LocalTime): Place? =
            if (time >= LocalTime.of(22, 0) || time < LocalTime.of(6, 0)) Place.IN_BED else null
    }
}
