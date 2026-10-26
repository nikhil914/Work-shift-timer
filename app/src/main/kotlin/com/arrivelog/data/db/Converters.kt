package com.arrivelog.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/**
 * Dates are stored as ISO-8601 text ("2026-10-26") so they sort and range-query correctly in SQL.
 * Instants are epoch milliseconds. SSID sets are JSON because an SSID may contain any character.
 */
class Converters {
    @TypeConverter
    fun localDateToText(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun textToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun stringSetToJson(value: Set<String>?): String? = value?.let { json.encodeToString(it.sorted()) }

    @TypeConverter
    fun jsonToStringSet(value: String?): Set<String>? = value?.let { json.decodeFromString<List<String>>(it).toSet() }

    @TypeConverter
    fun longSetToJson(value: Set<Long>?): String? = value?.let { json.encodeToString(it.sorted()) }

    @TypeConverter
    fun jsonToLongSet(value: String?): Set<Long>? = value?.let { json.decodeFromString<List<Long>>(it).toSet() }

    private companion object {
        val json = Json
    }
}
