package com.arrivelog

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.arrivelog.background.DetectionCoordinator
import com.arrivelog.di.ApplicationScope
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkplaceRepository
import com.arrivelog.notifications.Notifier
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ArriveLogApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var coordinator: DetectionCoordinator
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var workplaces: WorkplaceRepository
    @Inject lateinit var notifier: Notifier

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        notifier.createChannels() // idempotent; must exist before the first background post
        // Setup edited (workplaces, mode, schedule, ...): re-plan and re-register once the edits
        // settle. drop(1) ignores the initial value, so a process started by a receiver does nothing here.
        scope.launch {
            combine(settings.settings, workplaces.observeAll()) { s, w -> s to w }
                .distinctUntilChanged()
                .drop(1)
                .debounce(SETTINGS_DEBOUNCE_MS)
                .collect { coordinator.sync("SETTINGS_CHANGED", force = true) }
        }
    }

    private companion object {
        const val SETTINGS_DEBOUNCE_MS = 1_500L
    }
}
