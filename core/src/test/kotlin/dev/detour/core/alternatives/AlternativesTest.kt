package dev.detour.core.alternatives

import dev.detour.core.alternatives.Feeling.ANXIOUS
import dev.detour.core.alternatives.Feeling.LONELY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class AlternativesTest {
    private val ranker = AlternativeRanker()
    private val alts = DefaultAlternatives.SEED
    private val walk = 1L
    private val breaths = 2L
    private val craft = 5L

    private fun outcomes(altId: Long, feeling: Feeling, vararg o: Outcome) =
        o.mapIndexed { i, outcome -> OutcomeRecord(altId, feeling, outcome, atMs = i.toLong()) }

    @Test
    fun untriedAlternativesKeepListOrder() {
        val ranked = ranker.rank(alts, ANXIOUS, null, emptyList())
        assertEquals(listOf(walk, breaths, craft), ranked.map { it.alternative.id })
    }

    @Test
    fun whatHelpedRisesToTheTop() {
        val history = outcomes(craft, ANXIOUS, Outcome.BETTER, Outcome.BETTER, Outcome.BETTER) +
            outcomes(walk, ANXIOUS, Outcome.WORSE, Outcome.DIDNT_DO)
        val ranked = ranker.rank(alts, ANXIOUS, null, history)
        assertEquals(listOf(craft, breaths, walk), ranked.map { it.alternative.id })
    }

    @Test
    fun historyIsPerFeeling() {
        val history = outcomes(craft, Feeling.BORED, Outcome.WORSE, Outcome.WORSE, Outcome.WORSE)
        val ranked = ranker.rank(alts, ANXIOUS, null, history)
        assertEquals(walk, ranked.first().alternative.id)
    }

    @Test
    fun onlyRecentWindowCounts() {
        val r = AlternativeRanker(window = 3)
        // Old failures, then 3 recent successes: the window only sees the successes.
        val history = (0 until 5).map { OutcomeRecord(walk, ANXIOUS, Outcome.WORSE, it.toLong()) } +
            (10 until 13).map { OutcomeRecord(walk, ANXIOUS, Outcome.BETTER, it.toLong()) }
        val top = r.rank(alts, ANXIOUS, null, history).first()
        assertEquals(walk, top.alternative.id)
        assertEquals(Evidence(ANXIOUS, 3, 3), top.evidence)
    }

    @Test
    fun skippedCheckInsAreIgnored() {
        val history = outcomes(walk, ANXIOUS, Outcome.SKIPPED, Outcome.SKIPPED, Outcome.SKIPPED)
        val top = ranker.rank(alts, ANXIOUS, null, history).first { it.alternative.id == walk }
        assertEquals(0.5, top.score, 1e-9)
        assertNull(top.evidence)
    }

    @Test
    fun evidenceTextIsPlain() {
        val history = outcomes(walk, ANXIOUS, *Array(9) { Outcome.BETTER }, Outcome.SAME, Outcome.WORSE)
        val top = ranker.rank(alts, ANXIOUS, null, history).first()
        assertEquals(
            "When anxious, “Take a walk” helped 9 of the last 11 times.",
            top.evidence!!.describe(top.alternative.title),
        )
    }

    @Test
    fun fillsToThreeWhenFewMatch() {
        val ranked = ranker.rank(alts, LONELY, null, emptyList())
        assertEquals(3, ranked.size)
        assertEquals(4L, ranked.first().alternative.id) // "Text or call a friend" is the only match
    }

    @Test
    fun atMostFiveAndNeverRideItOut() {
        val many = (1L..10L).map { Alternative(it, "Alt $it", feelings = setOf(ANXIOUS), durations = setOf(Duration.TWO_MIN)) } +
            DefaultAlternatives.SEED.last()
        val ranked = ranker.rank(many, ANXIOUS, null, emptyList())
        assertEquals(5, ranked.size)
        assertTrue(ranked.none { it.alternative.isRideItOut })
    }

    @Test
    fun placeBreaksTies() {
        val ranked = ranker.rank(alts, Feeling.TIRED, Place.IN_BED, emptyList())
        // Read a few pages fits "in bed"; breaths and stretch are "anywhere" and also fit.
        // Ties keep list order among fitting ones.
        assertEquals(listOf(2L, 3L, 6L), ranked.map { it.alternative.id })
        val bored = ranker.rank(alts, Feeling.BORED, Place.IN_BED, emptyList())
        assertEquals(3L, bored.first().alternative.id) // craft project (home only) drops below reading
    }

    @Test
    fun inferPlaceAtNight() {
        assertEquals(Place.IN_BED, AlternativeRanker.inferPlace(LocalTime.of(23, 0)))
        assertEquals(Place.IN_BED, AlternativeRanker.inferPlace(LocalTime.of(2, 0)))
        assertNull(AlternativeRanker.inferPlace(LocalTime.of(14, 0)))
    }

    // ---- check-in picker --------------------------------------------------------------------

    private val minute = 60_000L
    private fun choice(id: Long, at: Long, outcome: Outcome? = null) = ChoiceRecord(id, walk, ANXIOUS, at, outcome)

    @Test
    fun checkInAfterTenMinutes() {
        val c = choice(1, 0)
        assertNull(CheckInPicker.pick(listOf(c), 9 * minute))
        assertEquals(c, CheckInPicker.pick(listOf(c), 10 * minute))
    }

    @Test
    fun onlyTheLatestChoiceIsAskedAbout() {
        val old = choice(1, 0)
        val answered = choice(2, 5 * minute, Outcome.BETTER)
        assertNull(CheckInPicker.pick(listOf(old, answered), 60 * minute))
    }

    @Test
    fun staleChoicesAreDropped() {
        assertNull(CheckInPicker.pick(listOf(choice(1, 0)), 13 * 60 * minute))
    }
}
