package com.arrivelog.ui.nav

import kotlinx.serialization.Serializable

/**
 * Setup screens take [wizard]: true during first-run setup (button says "Next" and moves on),
 * false when opened from Settings (button says "Done" and goes back). Same screens either way.
 */
@Serializable data object HomeRoute
@Serializable data object SettingsRoute
@Serializable data object DebugLogRoute
@Serializable data object DevToolsRoute
@Serializable data class WorkplacesRoute(val wizard: Boolean)
@Serializable data class WorkplaceEditRoute(val id: Long = 0L)
@Serializable data class WifiRoute(val wizard: Boolean)
@Serializable data class ModeRoute(val wizard: Boolean)
@Serializable data class ScheduleRoute(val wizard: Boolean)
@Serializable data class ProfileEditRoute(val id: String = "")
@Serializable data class PermissionsRoute(val wizard: Boolean)
@Serializable data class TestNowRoute(val wizard: Boolean)

/** Wizard order (spec section 1). */
enum class SetupStep { WORKPLACES, WIFI, MODE, SCHEDULE, PERMISSIONS, TEST }

fun SetupStep.route(wizard: Boolean): Any = when (this) {
    SetupStep.WORKPLACES -> WorkplacesRoute(wizard)
    SetupStep.WIFI -> WifiRoute(wizard)
    SetupStep.MODE -> ModeRoute(wizard)
    SetupStep.SCHEDULE -> ScheduleRoute(wizard)
    SetupStep.PERMISSIONS -> PermissionsRoute(wizard)
    SetupStep.TEST -> TestNowRoute(wizard)
}

fun SetupStep.next(): SetupStep? = SetupStep.entries.getOrNull(ordinal + 1)
