package dev.detour.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCategoryTest {
    @Test
    fun strictnessOrder() {
        assertTrue(AppCategory.BLOCKED.isStricterThan(AppCategory.DISTRACTING))
        assertTrue(AppCategory.DISTRACTING.isStricterThan(AppCategory.ALLOWED))
        assertFalse(AppCategory.ALLOWED.isStricterThan(AppCategory.ALLOWED))
        assertFalse(AppCategory.ALLOWED.isStricterThan(AppCategory.BLOCKED))
    }

    @Test
    fun unclassifiedAppsDefaultToDistracting() {
        assertEquals(AppCategory.DISTRACTING, AppCategory.DEFAULT_FOR_UNCLASSIFIED)
    }
}
