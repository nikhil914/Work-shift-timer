package com.arrivelog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.ui.debug.DebugLogScreen
import com.arrivelog.ui.nav.DebugLogRoute
import com.arrivelog.ui.nav.HomeRoute
import com.arrivelog.ui.nav.ModeRoute
import com.arrivelog.ui.nav.PermissionsRoute
import com.arrivelog.ui.nav.ProfileEditRoute
import com.arrivelog.ui.nav.ScheduleRoute
import com.arrivelog.ui.nav.SettingsRoute
import com.arrivelog.ui.nav.SimulatorRoute
import com.arrivelog.ui.nav.SetupStep
import com.arrivelog.ui.nav.TestNowRoute
import com.arrivelog.ui.nav.WifiRoute
import com.arrivelog.ui.nav.WorkplaceEditRoute
import com.arrivelog.ui.nav.WorkplacesRoute
import com.arrivelog.ui.nav.next
import com.arrivelog.ui.nav.route
import com.arrivelog.ui.settings.SettingsScreen
import com.arrivelog.ui.setup.mode.ModeScreen
import com.arrivelog.ui.setup.permissions.PermissionsScreen
import com.arrivelog.ui.setup.schedule.ProfileEditScreen
import com.arrivelog.ui.setup.schedule.ScheduleScreen
import com.arrivelog.ui.setup.test.TestNowScreen
import com.arrivelog.ui.setup.wifi.WifiSetupScreen
import com.arrivelog.ui.setup.workplaces.WorkplaceEditScreen
import com.arrivelog.ui.setup.workplaces.WorkplacesScreen
import com.arrivelog.ui.home.EditRequest
import com.arrivelog.ui.home.HomeScreen
import com.arrivelog.ui.shell.ShellScreen
import com.arrivelog.ui.theme.ArriveLogTheme
import com.arrivelog.background.DetectionCoordinator
import com.arrivelog.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Single activity. First launch goes through the setup wizard; afterwards Home. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var coordinator: DetectionCoordinator

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    /** Edit-sheet requests from notification taps; Home consumes them. */
    private val editRequests = MutableStateFlow<EditRequest?>(null)

    /** Opening the app re-checks registrations and settles exits (cheap; no re-registration if unchanged). */
    override fun onResume() {
        super.onResume()
        appScope.launch { coordinator.sync("APP_OPEN") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh launch carries the request; after rotation it was already handled.
        if (savedInstanceState == null) editRequests.value = EditRequest.from(intent)
        enableEdgeToEdge()
        setContent {
            ArriveLogTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost(editRequests = editRequests, onEditRequestHandled = { editRequests.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        EditRequest.from(intent)?.let { editRequests.value = it }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    /** Read once: the start destination must not flip while the wizard itself completes setup. */
    val setupCompleted: StateFlow<Boolean?> = flow { emit(settings.settings.first().setupCompleted) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
private fun AppNavHost(
    editRequests: StateFlow<EditRequest?>,
    onEditRequestHandled: () -> Unit,
    main: MainViewModel = hiltViewModel(),
) {
    val setupCompleted by main.setupCompleted.collectAsStateWithLifecycle()
    val done = setupCompleted ?: run {
        Box(Modifier.fillMaxSize())
        return
    }
    val nav = rememberNavController()
    val start: Any = if (done) HomeRoute else WorkplacesRoute(wizard = true)

    // A notification tapped while another screen is open: go back to Home, which opens the sheet.
    val pending by editRequests.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        if (pending != null && done && nav.currentDestination?.hasRoute<HomeRoute>() == false) {
            nav.popBackStack<HomeRoute>(inclusive = false)
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable<HomeRoute> {
            HomeScreen(
                onSettings = { nav.navigate(SettingsRoute) },
                editRequests = editRequests,
                onEditRequestHandled = onEditRequestHandled,
            )
        }
        composable<SimulatorRoute> { ShellScreen(onBack = { nav.popBackStack() }) }
        composable<SettingsRoute> {
            SettingsScreen(
                onOpen = { nav.navigate(it.route(wizard = false)) },
                onDebug = { nav.navigate(DebugLogRoute) },
                onSimulator = { nav.navigate(SimulatorRoute) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<DebugLogRoute> { DebugLogScreen(onBack = { nav.popBackStack() }) }
        composable<WorkplacesRoute> { entry ->
            val wizard = entry.toRoute<WorkplacesRoute>().wizard
            WorkplacesScreen(
                wizard = wizard,
                onEdit = { nav.navigate(WorkplaceEditRoute(it)) },
                onNext = { nav.advance(SetupStep.WORKPLACES, wizard) },
            )
        }
        composable<WorkplaceEditRoute> { WorkplaceEditScreen(onDone = { nav.popBackStack() }) }
        composable<WifiRoute> { entry ->
            val wizard = entry.toRoute<WifiRoute>().wizard
            WifiSetupScreen(wizard = wizard, onNext = { nav.advance(SetupStep.WIFI, wizard) })
        }
        composable<ModeRoute> { entry ->
            val wizard = entry.toRoute<ModeRoute>().wizard
            ModeScreen(wizard = wizard, onNext = { nav.advance(SetupStep.MODE, wizard) })
        }
        composable<ScheduleRoute> { entry ->
            val wizard = entry.toRoute<ScheduleRoute>().wizard
            ScheduleScreen(
                wizard = wizard,
                onEditProfile = { nav.navigate(ProfileEditRoute(it)) },
                onNext = { nav.advance(SetupStep.SCHEDULE, wizard) },
            )
        }
        composable<ProfileEditRoute> { ProfileEditScreen(onDone = { nav.popBackStack() }) }
        composable<PermissionsRoute> { entry ->
            val wizard = entry.toRoute<PermissionsRoute>().wizard
            PermissionsScreen(wizard = wizard, onNext = { nav.advance(SetupStep.PERMISSIONS, wizard) })
        }
        composable<TestNowRoute> { entry ->
            val wizard = entry.toRoute<TestNowRoute>().wizard
            TestNowScreen(
                wizard = wizard,
                onFinished = {
                    if (wizard) {
                        nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                },
            )
        }
    }
}

/** Wizard: go to the next step (Back still returns to earlier ones). From Settings: just go back. */
private fun NavController.advance(current: SetupStep, wizard: Boolean) {
    val next = current.next()
    if (wizard && next != null) navigate(next.route(wizard = true)) else popBackStack()
}
