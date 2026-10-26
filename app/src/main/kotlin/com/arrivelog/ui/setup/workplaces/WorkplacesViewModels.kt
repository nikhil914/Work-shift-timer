package com.arrivelog.ui.setup.workplaces

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.arrivelog.domain.geo.GeoMath
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.repository.WorkplaceRepository
import com.arrivelog.platform.DeviceStatus
import com.arrivelog.platform.LocationSource
import com.arrivelog.platform.PlaceResult
import com.arrivelog.platform.SearchResult
import com.arrivelog.ui.nav.WorkplaceEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkplacesViewModel @Inject constructor(
    private val repo: WorkplaceRepository,
) : ViewModel() {
    val workplaces: StateFlow<List<Workplace>?> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

data class WorkplaceDraft(
    val id: Long = 0,
    val name: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = Workplace.DEFAULT_RADIUS_M,
    val ssids: Set<String> = emptySet(),
    val bssids: Set<String> = emptySet(),
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null && GeoMath.isValidCoordinate(latitude, longitude)
    val canSave: Boolean get() = name.isNotBlank() && hasLocation
}

sealed interface EditorEvent {
    data object Saved : EditorEvent
    data object LocationUnavailable : EditorEvent
    data object SearchUnavailable : EditorEvent
    data object NoSearchResults : EditorEvent
    data object TooMany : EditorEvent
}

@HiltViewModel
class WorkplaceEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: WorkplaceRepository,
    private val locationSource: LocationSource,
    val deviceStatus: DeviceStatus,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<WorkplaceEditRoute>().id

    private val _draft = MutableStateFlow(WorkplaceDraft())
    val draft: StateFlow<WorkplaceDraft> = _draft.asStateFlow()

    /** Bumped whenever the location changes from outside the map (search / GPS) so the camera follows. */
    private val _cameraTarget = MutableStateFlow(0)
    val cameraTarget: StateFlow<Int> = _cameraTarget.asStateFlow()

    private val _results = MutableStateFlow<List<PlaceResult>>(emptyList())
    val results: StateFlow<List<PlaceResult>> = _results.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    private var searchJob: Job? = null
    private var existingCount = 0

    init {
        viewModelScope.launch {
            val all = repo.getAll()
            existingCount = all.size
            all.firstOrNull { it.id == id }?.let { w ->
                _draft.value = WorkplaceDraft(w.id, w.name, w.latitude, w.longitude, w.radiusMeters, w.ssids, w.bssids)
                _cameraTarget.update { it + 1 }
            }
        }
    }

    val isNew: Boolean get() = id == 0L

    fun setName(name: String) = _draft.update { it.copy(name = name.take(MAX_NAME)) }

    fun setRadius(meters: Int) = _draft.update { it.copy(radiusMeters = Workplace.clampRadius(meters)) }

    /** From a map tap or pin drag: the map already shows it, so the camera is not moved. */
    fun setLocationFromMap(lat: Double, lng: Double) = _draft.update { it.copy(latitude = lat, longitude = lng) }

    private fun moveTo(lat: Double, lng: Double) {
        _draft.update { it.copy(latitude = lat, longitude = lng) }
        _cameraTarget.update { it + 1 }
    }

    fun useCurrentLocation() = viewModelScope.launch {
        _busy.value = true
        val fix = locationSource.currentLocation()
        _busy.value = false
        if (fix == null) _events.tryEmit(EditorEvent.LocationUnavailable) else moveTo(fix.latitude, fix.longitude)
    }

    fun search(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _busy.value = true
            when (val r = locationSource.search(query)) {
                is SearchResult.Found -> _results.value = r.places
                SearchResult.NoResults -> {
                    _results.value = emptyList()
                    _events.tryEmit(EditorEvent.NoSearchResults)
                }
                SearchResult.Unavailable -> {
                    _results.value = emptyList()
                    _events.tryEmit(EditorEvent.SearchUnavailable)
                }
            }
            _busy.value = false
        }
    }

    fun clearResults() {
        _results.value = emptyList()
    }

    fun pick(place: PlaceResult) {
        _results.value = emptyList()
        if (_draft.value.name.isBlank()) setName(place.label.substringBefore(',').trim())
        moveTo(place.latitude, place.longitude)
    }

    /** Manual entry when no Maps key is configured. */
    fun setCoordinatesText(lat: String, lng: String) {
        val la = lat.trim().toDoubleOrNull()
        val lo = lng.trim().toDoubleOrNull()
        _draft.update { it.copy(latitude = la, longitude = lo) }
    }

    fun save() = viewModelScope.launch {
        val d = _draft.value
        if (!d.canSave) return@launch
        if (isNew && existingCount >= Workplace.MAX_WORKPLACES) {
            _events.tryEmit(EditorEvent.TooMany)
            return@launch
        }
        repo.upsert(
            Workplace(
                id = d.id,
                name = d.name.trim(),
                latitude = d.latitude ?: return@launch,
                longitude = d.longitude ?: return@launch,
                radiusMeters = d.radiusMeters,
                ssids = d.ssids,
                bssids = d.bssids,
            ),
        )
        _events.tryEmit(EditorEvent.Saved)
    }

    private companion object {
        const val MAX_NAME = 40
    }
}
