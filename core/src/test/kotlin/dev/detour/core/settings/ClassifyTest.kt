package dev.detour.core.settings

import dev.detour.core.AppCategory.ALLOWED
import dev.detour.core.AppCategory.BLOCKED
import dev.detour.core.AppCategory.DISTRACTING
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.settings.ChangeKind.LOOSEN
import dev.detour.core.settings.ChangeKind.NEUTRAL
import dev.detour.core.settings.ChangeKind.TIGHTEN
import dev.detour.core.time.AllowedHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ClassifyTest {
    private val yt = GroupState("youtube", "YouTube", setOf("youtube.com", "youtu.be"), blocked = true)
    private val base = ProtectedSettings(
        categories = mapOf("yt" to DISTRACTING, "maps" to ALLOWED, "fb" to BLOCKED),
        budget = BudgetSettings(dailyBudgetMinutes = 60, opensCap = null, allowedHours = AllowedHours.DEFAULT, waitsSeconds = listOf(5, 15, 30, 60)),
        blocklist = listOf(yt),
        guardTargets = setOf(GuardTarget("vpn", "com.android.settings", "VpnSettings")),
    )

    private fun op(o: SettingsOp) = classify(base, o.apply(base))
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun noChangeIsNeutral() = assertEquals(NEUTRAL, classify(base, base))

    // ---- categories --------------------------------------------------------------------

    @Test
    fun stricterCategoryTightens() {
        assertEquals(TIGHTEN, op(SettingsOp.SetCategory("yt", BLOCKED)))
        assertEquals(TIGHTEN, op(SettingsOp.SetCategory("maps", DISTRACTING)))
    }

    @Test
    fun looserCategoryLoosens() {
        assertEquals(LOOSEN, op(SettingsOp.SetCategory("yt", ALLOWED)))
        assertEquals(LOOSEN, op(SettingsOp.SetCategory("fb", DISTRACTING)))
    }

    @Test
    fun newAppsAreJudgedConservatively() {
        assertEquals(LOOSEN, op(SettingsOp.SetCategory("new", ALLOWED)))
        assertEquals(TIGHTEN, op(SettingsOp.SetCategory("new", BLOCKED)))
        assertEquals(LOOSEN, classify(base, base.copy(categories = base.categories - "fb"))) // removed a block
        assertEquals(NEUTRAL, classify(base, base.copy(categories = base.categories - "maps")))
    }

    @Test
    fun sameCategoryIsNeutral() = assertEquals(NEUTRAL, op(SettingsOp.SetCategory("yt", DISTRACTING)))

    // ---- budget ------------------------------------------------------------------------

    @Test
    fun budget() {
        assertEquals(TIGHTEN, op(SettingsOp.SetDailyBudget(45)))
        assertEquals(LOOSEN, op(SettingsOp.SetDailyBudget(75)))
        assertEquals(LOOSEN, op(SettingsOp.SetDailyBudget(null)))
        assertEquals(TIGHTEN, classify(base.copy(budget = base.budget.copy(dailyBudgetMinutes = null)), base))
    }

    @Test
    fun opensCap() {
        assertEquals(TIGHTEN, op(SettingsOp.SetOpensCap(10)))
        val capped = base.copy(budget = base.budget.copy(opensCap = 10))
        assertEquals(TIGHTEN, classify(capped, SettingsOp.SetOpensCap(8).apply(capped)))
        assertEquals(LOOSEN, classify(capped, SettingsOp.SetOpensCap(12).apply(capped)))
        assertEquals(LOOSEN, classify(capped, SettingsOp.SetOpensCap(null).apply(capped)))
    }

    @Test
    fun allowedHours() {
        assertEquals(TIGHTEN, op(SettingsOp.SetAllowedHours(AllowedHours(t(10), t(22)))))   // shrink both ends
        assertEquals(TIGHTEN, op(SettingsOp.SetAllowedHours(AllowedHours(t(9), t(21, 30)))))
        assertEquals(LOOSEN, op(SettingsOp.SetAllowedHours(AllowedHours(t(8, 30), t(23)))))  // earlier start
        assertEquals(LOOSEN, op(SettingsOp.SetAllowedHours(AllowedHours(t(10), t(23, 30))))) // shifted: adds time
        assertEquals(LOOSEN, op(SettingsOp.SetAllowedHours(null)))                           // turned off
        val off = base.copy(budget = base.budget.copy(allowedHours = null))
        assertEquals(TIGHTEN, classify(off, base))                                           // turned on
    }

    @Test
    fun resetTimeChangeIsAlwaysLoosen() {
        assertEquals(LOOSEN, op(SettingsOp.SetResetTime(t(3))))
        assertEquals(LOOSEN, op(SettingsOp.SetResetTime(t(5))))
    }

    @Test
    fun waits() {
        assertEquals(TIGHTEN, op(SettingsOp.SetWaits(listOf(10, 20, 30, 60))))
        assertEquals(TIGHTEN, op(SettingsOp.SetWaits(listOf(5, 15, 30, 60, 120))))  // added a longer step
        assertEquals(LOOSEN, op(SettingsOp.SetWaits(listOf(5, 15, 30))))           // last step now 30 instead of 60
        assertEquals(LOOSEN, op(SettingsOp.SetWaits(listOf(1, 60, 60, 60))))       // mixed
        assertEquals(LOOSEN, op(SettingsOp.SetWaits(emptyList())))
    }

    @Test
    fun checkInAndReopen() {
        assertEquals(TIGHTEN, op(SettingsOp.SetCheckInMinutes(5)))
        assertEquals(LOOSEN, op(SettingsOp.SetCheckInMinutes(20)))
        assertEquals(TIGHTEN, op(SettingsOp.SetReopenSeconds(10)))
        assertEquals(LOOSEN, op(SettingsOp.SetReopenSeconds(120)))
    }

    @Test
    fun observing() {
        val observing = base.copy(budget = base.budget.copy(observeUntilMs = 1_000))
        assertEquals(TIGHTEN, classify(observing, SettingsOp.SetObserveUntil(null).apply(observing)))  // stop early
        assertEquals(TIGHTEN, classify(observing, SettingsOp.SetObserveUntil(500).apply(observing)))
        assertEquals(LOOSEN, classify(observing, SettingsOp.SetObserveUntil(2_000).apply(observing)))  // extend
        assertEquals(LOOSEN, op(SettingsOp.SetObserveUntil(1_000)))                                     // restart
    }

    @Test
    fun confirmingTheBudgetIsCosmetic() = assertEquals(NEUTRAL, op(SettingsOp.SetBudgetConfirmed(true)))

    // ---- blocklist ---------------------------------------------------------------------

    @Test
    fun blocklist() {
        assertEquals(TIGHTEN, op(SettingsOp.AddDomain("youtube", "ytimg.com")))
        assertEquals(LOOSEN, op(SettingsOp.RemoveDomain("youtube", "youtu.be")))
        assertEquals(LOOSEN, op(SettingsOp.SetGroupBlocked("youtube", false)))
        assertEquals(LOOSEN, op(SettingsOp.DeleteGroup("youtube")))
        assertEquals(NEUTRAL, op(SettingsOp.AddGroup("custom", "Mine")))      // empty group protects nothing
        val withCustom = SettingsOp.AddGroup("custom", "Mine").apply(base)
        assertEquals(TIGHTEN, classify(withCustom, SettingsOp.AddDomain("custom", "tiktok.com").apply(withCustom)))
    }

    @Test
    fun unblockedGroupDomainsDontMatterUntilBlocked() {
        val off = SettingsOp.SetGroupBlocked("youtube", false).apply(base)
        assertEquals(NEUTRAL, classify(off, SettingsOp.RemoveDomain("youtube", "youtu.be").apply(off)))
        assertEquals(TIGHTEN, classify(off, SettingsOp.SetGroupBlocked("youtube", true).apply(off)))
    }

    // ---- browser and settings guard ----------------------------------------------------

    @Test
    fun browserAndGuard() {
        assertEquals(LOOSEN, op(SettingsOp.SetAllowedBrowser("org.mozilla.firefox")))
        assertEquals(LOOSEN, op(SettingsOp.SetSettingsGuard(false)))
        val off = base.copy(settingsGuardEnabled = false)
        assertEquals(TIGHTEN, classify(off, base))
        assertEquals(TIGHTEN, op(SettingsOp.AddGuardTarget(GuardTarget("dns", "com.android.settings", "Dns"))))
        assertEquals(LOOSEN, op(SettingsOp.RemoveGuardTarget(base.guardTargets.single())))
    }

    // ---- combining ---------------------------------------------------------------------

    @Test
    fun anyLooseningMakesTheWholeChangeLoosen() {
        val mixed = base.copy(
            categories = base.categories + ("yt" to BLOCKED),                 // tighter
            budget = base.budget.copy(dailyBudgetMinutes = 120),             // looser
        )
        assertEquals(LOOSEN, classify(base, mixed))
    }

    @Test
    fun gateDecisions() {
        assertTrue(ChangeGate.decide(base, SettingsOp.SetDailyBudget(30)).immediate)
        assertFalse(ChangeGate.decide(base, SettingsOp.SetDailyBudget(90)).immediate)
        assertTrue(ChangeGate.decide(base, SettingsOp.SetBudgetConfirmed(true)).immediate)
    }

    // ---- encoding ----------------------------------------------------------------------

    @Test
    fun everyOpRoundTrips() {
        val ops = listOf(
            SettingsOp.SetCategory("com.example", BLOCKED),
            SettingsOp.SetDailyBudget(30), SettingsOp.SetDailyBudget(null),
            SettingsOp.SetOpensCap(5), SettingsOp.SetOpensCap(null),
            SettingsOp.SetAllowedHours(AllowedHours(t(9), t(23))), SettingsOp.SetAllowedHours(null),
            SettingsOp.SetResetTime(t(4, 30)),
            SettingsOp.SetWaits(listOf(5, 15, 30)), SettingsOp.SetWaits(emptyList()),
            SettingsOp.SetCheckInMinutes(10), SettingsOp.SetReopenSeconds(45),
            SettingsOp.SetObserveUntil(123L), SettingsOp.SetObserveUntil(null),
            SettingsOp.SetBudgetConfirmed(true),
            SettingsOp.SetGroupBlocked("g", false), SettingsOp.AddDomain("g", "a.com"), SettingsOp.RemoveDomain("g", "a.com"),
            SettingsOp.AddGroup("g", "My sites"), SettingsOp.DeleteGroup("g"),
            SettingsOp.SetAllowedBrowser("com.android.chrome"), SettingsOp.SetSettingsGuard(false),
            SettingsOp.AddGuardTarget(GuardTarget("k", "p", "c")), SettingsOp.RemoveGuardTarget(GuardTarget("k", "p", "c")),
        )
        for (o in ops) assertEquals(o, SettingsOp.decode(o.encode()))
        assertEquals(null, SettingsOp.decode("nonsense\tx"))
        assertEquals(null, SettingsOp.decode("cat\tpkg\tNOT_A_CATEGORY"))
    }

    // ---- restore -----------------------------------------------------------------------

    @Test
    fun restoreCannotLoosen() {
        // A backup from a looser time: bigger budget, YouTube allowed, YouTube group off, guard off.
        val backup = base.copy(
            categories = mapOf("yt" to ALLOWED, "fb" to BLOCKED, "insta" to BLOCKED),
            budget = base.budget.copy(dailyBudgetMinutes = 120, waitsSeconds = listOf(10, 20, 40, 80)),
            blocklist = listOf(yt.copy(blocked = false), GroupState("reddit", "Reddit", setOf("reddit.com"), true)),
            settingsGuardEnabled = false,
        )
        val plan = RestorePlanner.plan(base, backup)
        // Tighter bits apply now...
        assertTrue(SettingsOp.SetCategory("insta", BLOCKED) in plan.immediate)
        assertTrue(SettingsOp.SetWaits(listOf(10, 20, 40, 80)) in plan.immediate)
        assertTrue(SettingsOp.AddDomain("reddit", "reddit.com") in plan.immediate)
        // ...looser bits wait.
        assertTrue(SettingsOp.SetCategory("yt", ALLOWED) in plan.queued)
        assertTrue(SettingsOp.SetDailyBudget(120) in plan.queued)
        assertTrue(SettingsOp.SetGroupBlocked("youtube", false) in plan.queued)
        assertTrue(SettingsOp.SetSettingsGuard(false) in plan.queued)
        // Applying only the immediate part never yields anything looser than now.
        val afterImmediate = plan.immediate.fold(base) { s, o -> o.apply(s) }
        assertTrue(classify(base, afterImmediate) != LOOSEN)
    }

    @Test
    fun restoreIsAMergeNotAReplace() {
        // Missing from the backup = left alone (never removed by a restore).
        val backup = base.copy(categories = emptyMap(), blocklist = emptyList(), guardTargets = emptySet())
        assertEquals(emptyList<SettingsOp>(), RestorePlanner.diff(base, backup))
    }

    @Test
    fun restoreOntoFreshInstallAppliesTighterImmediately() {
        val fresh = ProtectedSettings(budget = BudgetSettings(observeUntilMs = 5_000))
        val backup = base
        val plan = RestorePlanner.plan(fresh, backup)
        assertTrue(SettingsOp.SetObserveUntil(null) in plan.immediate)   // ending observing is tighter
        assertTrue(SettingsOp.SetDailyBudget(60) in plan.immediate)
        assertTrue(SettingsOp.SetCategory("fb", BLOCKED) in plan.immediate)
        assertTrue(SettingsOp.SetCategory("maps", ALLOWED) in plan.queued)  // unknown -> Allowed waits
    }
}
