package dev.detour.core

import dev.detour.core.AppCategory.ALLOWED
import dev.detour.core.AppCategory.BLOCKED
import dev.detour.core.AppCategory.DISTRACTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryResolverTest {
    private val device = DeviceContext(
        essentialPackages = setOf("com.google.android.apps.nexuslauncher", "com.google.android.inputmethod.latin"),
        allowedBrowser = DefaultClassifications.CHROME,
        suggestions = DefaultClassifications.suggestions(),
    )

    private fun app(pkg: String, launchable: Boolean = true, browser: Boolean = false) =
        AppFacts(pkg, launchable, browser)

    private fun resolve(facts: AppFacts, choice: AppCategory? = null) =
        CategoryResolver.resolve(facts, choice, device)

    @Test
    fun builtInEssentialsAreAllowedAndLocked() {
        for (pkg in listOf("dev.detour.guard", "dev.detour.filter", "com.android.systemui", "com.google.android.dialer")) {
            val r = resolve(app(pkg))
            assertEquals(pkg, ALLOWED, r.category)
            assertFalse(pkg, r.editable)
        }
    }

    @Test
    fun deviceEssentialsBeatUserChoice() {
        // Even a stored "Blocked" can't lock you out of your launcher or keyboard.
        val r = resolve(app("com.google.android.apps.nexuslauncher"), choice = BLOCKED)
        assertEquals(ALLOWED, r.category)
        assertEquals(CategoryReason.ESSENTIAL, r.reason)
    }

    @Test
    fun allowedBrowserIsAllowed() {
        val r = resolve(app("com.android.chrome", browser = true))
        assertEquals(ALLOWED, r.category)
        assertEquals(CategoryReason.ALLOWED_BROWSER, r.reason)
        assertFalse(r.editable)
    }

    @Test
    fun everyOtherBrowserIsBlockedEvenWithAStoredChoice() {
        assertEquals(BLOCKED, resolve(app("com.sec.android.app.sbrowser", browser = true)).category)
        assertEquals(BLOCKED, resolve(app("org.mozilla.firefox", browser = true), choice = ALLOWED).category)
        assertFalse(resolve(app("org.mozilla.firefox", browser = true)).editable)
    }

    @Test
    fun browserRuleFollowsTheChosenBrowser() {
        val firefoxDevice = device.copy(allowedBrowser = "org.mozilla.firefox")
        assertEquals(ALLOWED, CategoryResolver.resolve(app("org.mozilla.firefox", browser = true), null, firefoxDevice).category)
        assertEquals(BLOCKED, CategoryResolver.resolve(app("com.android.chrome", browser = true), null, firefoxDevice).category)
    }

    @Test
    fun suggestedDefaults() {
        assertEquals(DISTRACTING, resolve(app("com.google.android.youtube")).category)
        assertEquals(DISTRACTING, resolve(app("com.instagram.android")).category)
        assertEquals(DISTRACTING, resolve(app("com.reddit.frontpage")).category)
        assertEquals(DISTRACTING, resolve(app("com.facebook.katana")).category)
        assertEquals(DISTRACTING, resolve(app("com.snapchat.android")).category)
        assertEquals(ALLOWED, resolve(app("com.facebook.orca")).category)
        assertEquals(ALLOWED, resolve(app("com.google.android.apps.youtube.music")).category)
        assertEquals(CategoryReason.SUGGESTED, resolve(app("com.google.android.youtube")).reason)
    }

    @Test
    fun userChoiceBeatsSuggestion() {
        val r = resolve(app("com.google.android.youtube"), choice = BLOCKED)
        assertEquals(BLOCKED, r.category)
        assertEquals(CategoryReason.USER_CHOICE, r.reason)
    }

    @Test
    fun unknownLaunchableAppIsDistractingAndNeedsDecision() {
        val r = resolve(app("com.example.newgame"))
        assertEquals(DISTRACTING, r.category)
        assertTrue(r.needsDecision)
    }

    @Test
    fun nonLaunchableAppsAreAllowed() {
        val r = resolve(app("com.google.android.gms", launchable = false))
        assertEquals(ALLOWED, r.category)
        assertEquals(CategoryReason.NOT_LAUNCHABLE, r.reason)
    }
}
