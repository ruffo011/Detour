package dev.detour.core.alternatives

enum class Feeling(val label: String) {
    ANXIOUS("anxious"),
    BORED("bored"),
    LONELY("lonely"),
    AVOIDING("avoiding something"),
    TIRED("tired"),
    OTHER("other"),
}

enum class Duration(val label: String) {
    TWO_MIN("~2 min"),
    FIFTEEN_MIN("~15 min"),
    ONE_HOUR("~1 hr"),
}

enum class Place(val label: String) {
    HOME("home"),
    OUT("out"),
    WORK("work"),
    IN_BED("in bed"),
}

enum class Outcome(val label: String) {
    BETTER("better"),
    SAME("same"),
    WORSE("worse"),
    DIDNT_DO("didn't do it"),

    /** You skipped the check-in. Not counted either way. */
    SKIPPED("skipped"),
}

data class Alternative(
    val id: Long,
    val title: String,
    val note: String = "",
    val feelings: Set<Feeling>,
    val durations: Set<Duration>,
    /** Where it makes sense. Empty = anywhere. */
    val places: Set<Place> = emptySet(),
    /** The built-in 60-second urge-surfing timer. */
    val isRideItOut: Boolean = false,
) {
    fun fitsPlace(place: Place?): Boolean = place == null || places.isEmpty() || place in places
}

/** One past outcome of choosing an alternative while feeling something. */
data class OutcomeRecord(
    val alternativeId: Long,
    val feeling: Feeling,
    val outcome: Outcome,
    val atMs: Long,
)

object DefaultAlternatives {
    /** Placeholders to edit. Ids are only for tests; the database assigns real ones. */
    val SEED: List<Alternative> = listOf(
        Alternative(1, "Take a walk", feelings = setOf(Feeling.ANXIOUS, Feeling.AVOIDING),
            durations = setOf(Duration.FIFTEEN_MIN), places = setOf(Place.HOME, Place.OUT)),
        Alternative(2, "10 slow breaths", feelings = setOf(Feeling.ANXIOUS, Feeling.TIRED),
            durations = setOf(Duration.TWO_MIN)),
        Alternative(3, "Read a few pages", feelings = setOf(Feeling.BORED, Feeling.TIRED),
            durations = setOf(Duration.FIFTEEN_MIN), places = setOf(Place.HOME, Place.IN_BED)),
        Alternative(4, "Text or call a friend", feelings = setOf(Feeling.LONELY),
            durations = setOf(Duration.TWO_MIN, Duration.FIFTEEN_MIN)),
        Alternative(5, "Work on a craft project", feelings = setOf(Feeling.BORED, Feeling.ANXIOUS),
            durations = setOf(Duration.ONE_HOUR), places = setOf(Place.HOME)),
        Alternative(6, "Stand up and stretch", feelings = setOf(Feeling.TIRED, Feeling.AVOIDING),
            durations = setOf(Duration.TWO_MIN)),
        Alternative(7, "Do the first 2 minutes of the thing I'm avoiding", feelings = setOf(Feeling.AVOIDING),
            durations = setOf(Duration.TWO_MIN)),
        Alternative(8, "Ride it out", note = "Notice the urge. You don't have to act on it. It will pass.",
            feelings = Feeling.entries.toSet(), durations = setOf(Duration.TWO_MIN), isRideItOut = true),
    )
}
