package com.arrivelog.background

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arrivelog.domain.engine.GeofenceArming
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What is registered with the system right now. Kept separate from user settings: this is
 * internal bookkeeping, and the Health / Debug screens read it.
 */
data class ArmingState(
    /** [com.arrivelog.domain.engine.GeofenceSignature] of the registered fences; "OFF" = none. */
    val geofenceSignature: String = SIGNATURE_OFF,
    val geofenceArming: GeofenceArming = GeofenceArming.OFF,
    val wifiArmed: Boolean = false,
    val lastRegisteredAt: Instant? = null,
    val lastError: String? = null,
    val lastErrorAt: Instant? = null,
    val missedEntryCheckDate: LocalDate? = null,
    val missedEntryRenotifyDate: LocalDate? = null,
    val nextWakeAt: Instant? = null,
    val nextWakeReason: String? = null,
    val housekeepingDate: LocalDate? = null,
) {
    companion object {
        const val SIGNATURE_OFF = "OFF"

        /** Forces the next sync to register again (after an error or when the system dropped them). */
        const val SIGNATURE_INVALID = "INVALID"
    }
}

private val Context.armingDataStore: DataStore<Preferences> by preferencesDataStore(name = "arming")

@Singleton
class ArmingStateStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val store get() = context.armingDataStore

    val state: Flow<ArmingState> = store.data.map { it.toState() }

    suspend fun get(): ArmingState = state.first()

    suspend fun update(transform: (ArmingState) -> ArmingState): ArmingState {
        var result = ArmingState()
        store.edit { prefs ->
            result = transform(prefs.toState())
            prefs.write(result)
        }
        return result
    }

    private fun Preferences.toState() = ArmingState(
        geofenceSignature = this[SIG] ?: ArmingState.SIGNATURE_OFF,
        geofenceArming = this[ARMING]?.let { runCatching { GeofenceArming.valueOf(it) }.getOrNull() } ?: GeofenceArming.OFF,
        wifiArmed = this[WIFI] ?: false,
        lastRegisteredAt = this[REGISTERED_AT]?.let(Instant::ofEpochMilli),
        lastError = this[ERROR],
        lastErrorAt = this[ERROR_AT]?.let(Instant::ofEpochMilli),
        missedEntryCheckDate = this[MISSED_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        missedEntryRenotifyDate = this[RENOTIFY_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        nextWakeAt = this[NEXT_WAKE]?.let(Instant::ofEpochMilli),
        nextWakeReason = this[NEXT_REASON],
        housekeepingDate = this[HOUSEKEEPING]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: ArmingState) {
        this[SIG] = s.geofenceSignature
        this[ARMING] = s.geofenceArming.name
        this[WIFI] = s.wifiArmed
        put(REGISTERED_AT, s.lastRegisteredAt?.toEpochMilli())
        put(ERROR, s.lastError)
        put(ERROR_AT, s.lastErrorAt?.toEpochMilli())
        put(MISSED_DATE, s.missedEntryCheckDate?.toString())
        put(RENOTIFY_DATE, s.missedEntryRenotifyDate?.toString())
        put(NEXT_WAKE, s.nextWakeAt?.toEpochMilli())
        put(NEXT_REASON, s.nextWakeReason)
        put(HOUSEKEEPING, s.housekeepingDate?.toString())
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        val SIG = stringPreferencesKey("geofence_signature")
        val ARMING = stringPreferencesKey("geofence_arming")
        val WIFI = booleanPreferencesKey("wifi_armed")
        val REGISTERED_AT = longPreferencesKey("last_registered_at")
        val ERROR = stringPreferencesKey("last_error")
        val ERROR_AT = longPreferencesKey("last_error_at")
        val MISSED_DATE = stringPreferencesKey("missed_entry_check_date")
        val RENOTIFY_DATE = stringPreferencesKey("missed_entry_renotify_date")
        val NEXT_WAKE = longPreferencesKey("next_wake_at")
        val NEXT_REASON = stringPreferencesKey("next_wake_reason")
        val HOUSEKEEPING = stringPreferencesKey("housekeeping_date")
    }
}
