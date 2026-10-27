package com.arrivelog.data.backup

import com.arrivelog.data.settings.SettingsDto
import com.arrivelog.data.settings.toDomain
import com.arrivelog.data.settings.toDto
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.TransactionRunner
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.repository.WorkplaceRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec section 8: backup / restore to one JSON file. Holds settings, workplaces and the day log.
 * The raw event log and per-day engine state are left out: they are debugging data, not records.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: String,
    val settings: SettingsDto,
    val workplaces: List<WorkplaceDto>,
    val workDays: List<WorkDayDto>,
) {
    companion object {
        const val FORMAT = "arrivelog-backup"
        const val VERSION = 1
    }
}

@Serializable
data class WorkplaceDto(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val ssids: List<String> = emptyList(),
    val bssids: List<String> = emptyList(),
)

/** Dates, instants and zones as ISO strings, enums by name: readable and stable across versions. */
@Serializable
data class WorkDayDto(
    val date: String,
    val status: String = DayStatus.WORKING.name,
    val arrivalAt: String? = null,
    val arrivalSource: String? = null,
    val arrivalWorkplaceId: Long? = null,
    val exitAt: String? = null,
    val exitSource: String? = null,
    val zoneId: String? = null,
    val note: String? = null,
    val arrivalEdited: Boolean = false,
    val exitEdited: Boolean = false,
    val originalArrivalAt: String? = null,
    val originalExitAt: String? = null,
)

class InvalidBackupException(message: String) : Exception(message)

data class BackupContents(val settings: AppSettings, val workplaces: List<Workplace>, val workDays: List<WorkDay>)

/** Pure JSON <-> domain conversion, so the round trip is unit-testable without Android. */
object BackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(contents: BackupContents, createdAt: Instant): String = json.encodeToString(
        BackupFile.serializer(),
        BackupFile(
            createdAt = createdAt.toString(),
            settings = contents.settings.toDto(),
            workplaces = contents.workplaces.map { it.toDto() },
            workDays = contents.workDays.sortedBy { it.date }.map { it.toDto() },
        ),
    )

    /** Throws [InvalidBackupException] for anything that is not an ArriveLog backup this version can read. */
    fun decode(text: String): BackupContents {
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw InvalidBackupException("Not an ArriveLog backup: ${it.message}") }
        if (file.format != BackupFile.FORMAT) throw InvalidBackupException("Not an ArriveLog backup")
        if (file.version > BackupFile.VERSION) throw InvalidBackupException("Backup is from a newer version (${file.version})")
        return BackupContents(
            settings = file.settings.toDomain(),
            workplaces = file.workplaces.map { it.toDomain() },
            // A single unreadable day is skipped rather than failing the whole restore.
            workDays = file.workDays.mapNotNull { runCatching { it.toDomain() }.getOrNull() },
        )
    }

    private fun Workplace.toDto() = WorkplaceDto(id, name, latitude, longitude, radiusMeters, ssids.sorted(), bssids.sorted())

    private fun WorkplaceDto.toDomain() = Workplace(id, name, latitude, longitude, radiusMeters, ssids.toSet(), bssids.toSet())

    private fun WorkDay.toDto() = WorkDayDto(
        date = date.toString(),
        status = status.name,
        arrivalAt = arrivalAt?.toString(),
        arrivalSource = arrivalSource?.name,
        arrivalWorkplaceId = arrivalWorkplaceId,
        exitAt = exitAt?.toString(),
        exitSource = exitSource?.name,
        zoneId = zoneId?.id,
        note = note,
        arrivalEdited = arrivalEdited,
        exitEdited = exitEdited,
        originalArrivalAt = originalArrivalAt?.toString(),
        originalExitAt = originalExitAt?.toString(),
    )

    private fun WorkDayDto.toDomain() = WorkDay(
        date = LocalDate.parse(date),
        status = DayStatus.valueOf(status),
        arrivalAt = arrivalAt?.let(Instant::parse),
        arrivalSource = arrivalSource?.let(SignalSource::valueOf),
        arrivalWorkplaceId = arrivalWorkplaceId,
        exitAt = exitAt?.let(Instant::parse),
        exitSource = exitSource?.let(SignalSource::valueOf),
        zoneId = zoneId?.let(ZoneId::of),
        note = note,
        arrivalEdited = arrivalEdited,
        exitEdited = exitEdited,
        originalArrivalAt = originalArrivalAt?.let(Instant::parse),
        originalExitAt = originalExitAt?.let(Instant::parse),
    )
}

data class RestoreResult(val days: Int, val workplaces: Int)

@Singleton
class BackupManager @Inject constructor(
    private val settings: SettingsRepository,
    private val workplaces: WorkplaceRepository,
    private val workDays: WorkDayRepository,
    private val tx: TransactionRunner,
) {
    suspend fun export(now: Instant): String = BackupCodec.encode(
        BackupContents(
            settings = settings.current(),
            workplaces = workplaces.getAll(),
            workDays = workDays.observeRange(FIRST_DAY, LAST_DAY).first(),
        ),
        now,
    )

    /**
     * Restores settings and workplaces as they were, and every day in the file. Days that are not
     * in the file are kept, so restoring an older backup never deletes newer days.
     */
    suspend fun restore(text: String): RestoreResult {
        val contents = BackupCodec.decode(text)
        tx.inTransaction {
            val keep = contents.workplaces.map { it.id }.toSet()
            workplaces.getAll().filter { it.id !in keep }.forEach { workplaces.delete(it.id) }
            contents.workplaces.forEach { workplaces.upsert(it) }
            contents.workDays.forEach { workDays.save(it) }
        }
        settings.update { contents.settings }
        return RestoreResult(days = contents.workDays.size, workplaces = contents.workplaces.size)
    }

    private companion object {
        /** "Everything": far wider than any real log, and safe for the date converter. */
        val FIRST_DAY: LocalDate = LocalDate.of(2000, 1, 1)
        val LAST_DAY: LocalDate = LocalDate.of(2100, 12, 31)
    }
}
