package dev.detour.core

/**
 * How guard treats an app. Ordered from least to most strict, so moving an app to a
 * category with a higher [strictness] is a "tighten" change and a lower one is "loosen".
 */
enum class AppCategory(val strictness: Int) {
    /** Never interrupted. */
    ALLOWED(0),

    /** Counts against the shared daily budget and goes through the pause flow. */
    DISTRACTING(1),

    /** Always blocked; there is no "open anyway". */
    BLOCKED(2);

    fun isStricterThan(other: AppCategory): Boolean = strictness > other.strictness

    companion object {
        /** Newly installed or unclassified apps start here until classified. */
        val DEFAULT_FOR_UNCLASSIFIED = DISTRACTING
    }
}
