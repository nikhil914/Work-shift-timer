package com.arrivelog.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arrivelog.R
import com.arrivelog.background.DetectionCoordinator
import com.arrivelog.di.ApplicationScope
import com.arrivelog.domain.repository.WorkDayRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Quick Settings tile "Log arrival" (spec section 8): one tap logs a manual arrival now, through the
 * same engine as everything else (first arrival wins, so tapping twice changes nothing). The tile
 * shows today's arrival once there is one. Bound by the system only while the panel is open.
 */
@AndroidEntryPoint
class LogArrivalTileService : TileService() {
    @Inject lateinit var coordinator: DetectionCoordinator
    @Inject lateinit var workDays: WorkDayRepository
    @Inject lateinit var clock: Clock

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    private var refresh: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onStopListening() {
        refresh?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // App scope, not the service's: the log must finish even if the panel closes at once.
        appScope.launch {
            coordinator.logArrival(null)
            refreshTile()
        }
    }

    private fun refreshTile() {
        refresh?.cancel()
        refresh = appScope.launch {
            val day = workDays.get(LocalDate.now(clock))
            val arrival = day?.arrivalAt?.atZone(day.zoneId ?: clock.zone)?.format(HHMM)
            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                tile.label = getString(R.string.tile_label)
                tile.state = if (arrival != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = arrival?.let { getString(R.string.tile_arrived, it) } ?: getString(R.string.tile_tap_to_log)
                }
                tile.updateTile()
            }
        }
    }

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
