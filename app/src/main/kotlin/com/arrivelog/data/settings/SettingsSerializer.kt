package com.arrivelog.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.arrivelog.domain.model.AppSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

object SettingsSerializer : Serializer<SettingsDto> {

    val json = Json {
        ignoreUnknownKeys = true // newer file read by older app
        encodeDefaults = true
        explicitNulls = false
    }

    override val defaultValue: SettingsDto = AppSettings().toDto()

    override suspend fun readFrom(input: InputStream): SettingsDto = try {
        json.decodeFromString(SettingsDto.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Settings file is not valid JSON", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Settings file is not valid", e)
    }

    override suspend fun writeTo(t: SettingsDto, output: OutputStream) {
        output.write(json.encodeToString(SettingsDto.serializer(), t).encodeToByteArray())
    }
}
