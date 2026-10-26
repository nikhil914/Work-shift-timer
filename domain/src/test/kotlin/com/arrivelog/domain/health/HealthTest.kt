package com.arrivelog.domain.health

import com.arrivelog.domain.engine.GeofenceArming
import com.arrivelog.domain.engine.ListenerPlan
import com.arrivelog.domain.engine.PlanReason
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.setup.DeviceState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HealthTest {

    private val allGranted = DeviceState(
        fineLocation = true,
        backgroundLocation = true,
        notifications = true,
        nearbyWifi = true,
        locationEnabled = true,
        batteryUnrestricted = true,
        wifiEnabled = true,
    )
    private val arrivalPlan = ListenerPlan(GeofenceArming.ARRIVAL, true, 180_000, PlanReason.AWAITING_ARRIVAL, null)
    private val idlePlan = ListenerPlan(GeofenceArming.OFF, false, 180_000, PlanReason.NON_WORKING_DAY, null)

    private fun check(list: List<HealthCheck>, item: HealthItem) = list.first { it.item == item }

    @Test
    fun `everything granted and registered as planned is all green`() {
        val checks = HealthEvaluator.evaluate(allGranted, DetectionMode.GEOFENCE_OR_WIFI, arrivalPlan, GeofenceArming.ARRIVAL, wifiArmed = true)
        assertThat(HealthEvaluator.allGood(checks)).isTrue()
    }

    @Test
    fun `nothing registered on a day off is healthy`() {
        val checks = HealthEvaluator.evaluate(allGranted, DetectionMode.GEOFENCE_OR_WIFI, idlePlan, GeofenceArming.OFF, wifiArmed = false)
        assertThat(HealthEvaluator.allGood(checks)).isTrue()
    }

    @Test
    fun `fences missing while the window is open is red`() {
        val checks = HealthEvaluator.evaluate(allGranted, DetectionMode.GEOFENCE_OR_WIFI, arrivalPlan, GeofenceArming.OFF, wifiArmed = true)
        assertThat(check(checks, HealthItem.GEOFENCES).ok).isFalse()
    }

    @Test
    fun `nearby wifi only matters in modes that use Wi-Fi`() {
        val noNearby = allGranted.copy(nearbyWifi = false)
        val geoOnly = HealthEvaluator.evaluate(noNearby, DetectionMode.GEOFENCE_ONLY, idlePlan, GeofenceArming.OFF, false)
        assertThat(check(geoOnly, HealthItem.NEARBY_WIFI).ok).isTrue()
        assertThat(check(geoOnly, HealthItem.NEARBY_WIFI).relevant).isFalse()
        val withWifi = HealthEvaluator.evaluate(noNearby, DetectionMode.GEOFENCE_OR_WIFI, idlePlan, GeofenceArming.OFF, false)
        assertThat(check(withWifi, HealthItem.NEARBY_WIFI).ok).isFalse()
    }

    @Test
    fun `battery restriction and missing background location are red`() {
        val checks = HealthEvaluator.evaluate(
            allGranted.copy(batteryUnrestricted = false, backgroundLocation = false),
            DetectionMode.GEOFENCE_OR_WIFI, idlePlan, GeofenceArming.OFF, false,
        )
        assertThat(checks.filterNot { it.ok }.map { it.item })
            .containsExactly(HealthItem.BACKGROUND_LOCATION, HealthItem.BATTERY_UNRESTRICTED)
    }
}
