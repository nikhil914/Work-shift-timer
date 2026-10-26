package com.arrivelog.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class Fix(val latitude: Double, val longitude: Double, val accuracyM: Float?)

data class PlaceResult(val label: String, val latitude: Double, val longitude: Double)

sealed interface SearchResult {
    data class Found(val places: List<PlaceResult>) : SearchResult
    data object NoResults : SearchResult
    data object Unavailable : SearchResult // no Geocoder backend on this device / offline
}

@Singleton
class LocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceStatus: DeviceStatus,
) {
    /**
     * One user-initiated fix for "Use my current location" / "Test now". A single request with a
     * timeout, never continuous updates (section 5b).
     */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(timeoutMs: Long = 30_000): Fix? {
        if (!deviceStatus.granted(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancel = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(timeoutMs)
            .setMaxUpdateAgeMillis(60_000)
            .build()
        return try {
            withTimeoutOrNull(timeoutMs + 2_000) {
                client.getCurrentLocation(request, cancel.token).await()
            }?.let { Fix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) }
        } catch (_: SecurityException) {
            null
        } finally {
            cancel.cancel()
        }
    }

    /** Address search with the platform Geocoder: free, no Places API key or billing. */
    suspend fun search(query: String, max: Int = 5): SearchResult {
        if (query.isBlank()) return SearchResult.NoResults
        if (!Geocoder.isPresent()) return SearchResult.Unavailable
        val geocoder = Geocoder(context)
        val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(
                    query,
                    max,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, max)
                } catch (_: IOException) {
                    null
                }
            }
        }
        if (addresses == null) return SearchResult.Unavailable
        val places = addresses.filter { it.hasLatitude() && it.hasLongitude() }.map { a ->
            val label = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ")
                .ifBlank { a.featureName ?: query }
            PlaceResult(label, a.latitude, a.longitude)
        }
        return if (places.isEmpty()) SearchResult.NoResults else SearchResult.Found(places)
    }
}
