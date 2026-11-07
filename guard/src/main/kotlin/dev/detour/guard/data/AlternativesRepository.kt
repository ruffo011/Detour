package dev.detour.guard.data

import dev.detour.core.alternatives.Alternative
import dev.detour.core.alternatives.ChoiceRecord
import dev.detour.core.alternatives.DefaultAlternatives
import dev.detour.core.alternatives.Duration
import dev.detour.core.alternatives.Feeling
import dev.detour.core.alternatives.Outcome
import dev.detour.core.alternatives.OutcomeRecord
import dev.detour.core.alternatives.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AlternativesRepository(private val dao: AlternativeDao, private val intentions: IntentionDao) {

    val alternatives: Flow<List<Alternative>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** Outcome history for ranking (most recent first). */
    val history: Flow<List<OutcomeRecord>> = dao.observeOutcomes(HISTORY_LIMIT).map { rows ->
        rows.mapNotNull { it.toOutcomeRecord() }
    }

    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        DefaultAlternatives.SEED.forEachIndexed { i, alt -> dao.insert(alt.toEntity(position = i, keepId = false)) }
    }

    suspend fun save(alt: Alternative) {
        if (alt.id == 0L) {
            val position = (dao.all().maxOfOrNull { it.position } ?: -1) + 1
            dao.insert(alt.toEntity(position, keepId = false))
        } else {
            val existing = dao.byId(alt.id) ?: return
            dao.update(alt.toEntity(existing.position, keepId = true).copy(isRideItOut = existing.isRideItOut))
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun byId(id: Long): Alternative? = dao.byId(id)?.toModel()

    suspend fun recordChoice(alternativeId: Long, feeling: Feeling, packageName: String?) {
        dao.insertChoice(
            AlternativeChoiceEntity(
                alternativeId = alternativeId,
                feeling = feeling.name,
                packageName = packageName,
                chosenAtMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun recentChoices(): List<ChoiceRecord> = dao.recentChoices(20).mapNotNull { row ->
        val feeling = enumOrNull<Feeling>(row.feeling) ?: return@mapNotNull null
        ChoiceRecord(row.id, row.alternativeId, feeling, row.chosenAtMs, row.outcome?.let { enumOrNull<Outcome>(it) })
    }

    suspend fun choice(id: Long): AlternativeChoiceEntity? = dao.choiceById(id)

    suspend fun setOutcome(choiceId: Long, outcome: Outcome) =
        dao.setOutcome(choiceId, outcome.name, System.currentTimeMillis())

    suspend fun saveIntention(packageName: String, text: String, feeling: Feeling?) {
        intentions.insert(IntentionEntity(packageName = packageName, text = text.trim(), feeling = feeling?.name, atMs = System.currentTimeMillis()))
    }

    companion object {
        private const val HISTORY_LIMIT = 500
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    runCatching { enumValueOf<T>(name) }.getOrNull()

private inline fun <reified T : Enum<T>> String.toEnumSet(): Set<T> =
    split(',').filter { it.isNotBlank() }.mapNotNull { enumOrNull<T>(it) }.toSet()

private fun Set<Enum<*>>.csv(): String = joinToString(",") { it.name }

private fun AlternativeEntity.toModel() = Alternative(
    id = id,
    title = title,
    note = note,
    feelings = feelings.toEnumSet<Feeling>(),
    durations = durations.toEnumSet<Duration>(),
    places = places.toEnumSet<Place>(),
    isRideItOut = isRideItOut,
)

private fun Alternative.toEntity(position: Int, keepId: Boolean) = AlternativeEntity(
    id = if (keepId) id else 0,
    title = title.trim(),
    note = note.trim(),
    feelings = feelings.csv(),
    durations = durations.csv(),
    places = places.csv(),
    isRideItOut = isRideItOut,
    position = position,
)

private fun AlternativeChoiceEntity.toOutcomeRecord(): OutcomeRecord? {
    val f = enumOrNull<Feeling>(feeling) ?: return null
    val o = outcome?.let { enumOrNull<Outcome>(it) } ?: return null
    return OutcomeRecord(alternativeId, f, o, chosenAtMs)
}
