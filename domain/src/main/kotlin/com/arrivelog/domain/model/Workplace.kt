package com.arrivelog.domain.model

data class Workplace(
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_M,
    /** Normalised SSIDs (no surrounding quotes). */
    val ssids: Set<String> = emptySet(),
    /** Lower-case BSSIDs, only used when "also match BSSID" is on. */
    val bssids: Set<String> = emptySet(),
) {
    companion object {
        /**
         * The spec's slider says 100–500 m but section 5b sets a 150 m minimum for battery and
         * reliability (Play services geofences below ~150 m produce frequent false transitions).
         * The 5b minimum wins; see README "Spec deviations".
         */
        const val MIN_RADIUS_M = 150
        const val MAX_RADIUS_M = 500
        const val DEFAULT_RADIUS_M = 200
        const val MAX_WORKPLACES = 3

        fun clampRadius(meters: Int): Int = meters.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
    }
}
