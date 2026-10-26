package com.arrivelog.ui.setup.workplaces

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arrivelog.R
import com.arrivelog.domain.model.Workplace
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.rememberFineLocationRequest
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Abu Dhabi: default camera until a location is chosen. */
private val DEFAULT_CENTER = LatLng(24.4539, 54.3773)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkplaceEditScreen(
    onDone: () -> Unit,
    viewModel: WorkplaceEditViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val cameraTarget by viewModel.cameraTarget.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val msgLocation = stringResource(R.string.editor_location_unavailable)
    val msgSearchUnavailable = stringResource(R.string.editor_search_unavailable)
    val msgNoResults = stringResource(R.string.editor_search_none)
    val msgTooMany = stringResource(R.string.workplaces_max, Workplace.MAX_WORKPLACES)
    val msgPrecise = stringResource(R.string.editor_precise_needed)
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                EditorEvent.Saved -> onDone()
                EditorEvent.LocationUnavailable -> snackbar.showSnackbar(msgLocation)
                EditorEvent.SearchUnavailable -> snackbar.showSnackbar(msgSearchUnavailable)
                EditorEvent.NoSearchResults -> snackbar.showSnackbar(msgNoResults)
                EditorEvent.TooMany -> snackbar.showSnackbar(msgTooMany)
            }
        }
    }

    val requestLocation = rememberFineLocationRequest { granted ->
        if (granted) viewModel.useCurrentLocation() else scope.launch { snackbar.showSnackbar(msgPrecise) }
    }
    val onCurrentLocation: () -> Unit = {
        if (viewModel.deviceStatus.granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            viewModel.useCurrentLocation()
        } else {
            requestLocation()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (viewModel.isNew) R.string.editor_title_new else R.string.editor_title_edit)) },
                navigationIcon = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            SearchBar(busy = busy, results = results, onSearch = viewModel::search, onPick = viewModel::pick, onDismiss = viewModel::clearResults)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (viewModel.deviceStatus.hasMapsKey) {
                    PickerMap(
                        lat = draft.latitude,
                        lng = draft.longitude,
                        radius = draft.radiusMeters,
                        cameraTarget = cameraTarget,
                        onMoved = viewModel::setLocationFromMap,
                    )
                } else {
                    NoMapsKeyFallback(draft = draft, onCoordinates = viewModel::setCoordinatesText)
                }
            }

            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = viewModel::setName,
                        label = { Text(stringResource(R.string.editor_name)) },
                        placeholder = { Text(stringResource(R.string.editor_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.editor_radius, draft.radiusMeters), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = draft.radiusMeters.toFloat(),
                        onValueChange = { viewModel.setRadius((it / 10f).toInt() * 10) },
                        valueRange = Workplace.MIN_RADIUS_M.toFloat()..Workplace.MAX_RADIUS_M.toFloat(),
                        steps = (Workplace.MAX_RADIUS_M - Workplace.MIN_RADIUS_M) / 10 - 1,
                    )
                    Text(stringResource(R.string.editor_radius_hint), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onCurrentLocation, enabled = !busy) {
                            Text(stringResource(R.string.editor_use_current))
                        }
                        Box(Modifier.weight(1f))
                        Button(onClick = { viewModel.save() }, enabled = draft.canSave) { Text(stringResource(R.string.action_save)) }
                    }
                    if (!draft.hasLocation) {
                        Text(stringResource(R.string.editor_pick_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    busy: Boolean,
    results: List<com.arrivelog.platform.PlaceResult>,
    onSearch: (String) -> Unit,
    onPick: (com.arrivelog.platform.PlaceResult) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.editor_search)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            trailingIcon = {
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { onSearch(query) }) { Text(stringResource(R.string.action_search)) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = results.isNotEmpty(), onDismissRequest = onDismiss) {
            results.forEach { place ->
                DropdownMenuItem(text = { Text(place.label, maxLines = 2) }, onClick = { onPick(place) })
            }
        }
    }
}

@Composable
private fun PickerMap(
    lat: Double?,
    lng: Double?,
    radius: Int,
    cameraTarget: Int,
    onMoved: (Double, Double) -> Unit,
) {
    val start = if (lat != null && lng != null) LatLng(lat, lng) else DEFAULT_CENTER
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(start, if (lat != null) 16f else 11f) }
    val marker = remember { MarkerState(start) }

    // External moves (search result, GPS, loaded workplace): move pin and camera.
    LaunchedEffect(cameraTarget) {
        if (lat != null && lng != null) {
            val target = LatLng(lat, lng)
            marker.position = target
            camera.animate(CameraUpdateFactory.newLatLngZoom(target, 16f))
        }
    }
    // Pin dragged or map tapped: report back.
    LaunchedEffect(marker) {
        // drop(1): the initial (possibly default) position is not a user choice.
        snapshotFlow { marker.position }.drop(1).collect { p -> onMoved(p.latitude, p.longitude) }
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = camera,
        properties = MapProperties(isMyLocationEnabled = false),
        uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = true, mapToolbarEnabled = false),
        onMapClick = { marker.position = it },
    ) {
        Marker(state = marker, draggable = true)
        Circle(
            center = marker.position,
            radius = radius.toDouble(),
            strokeColor = Color(0xFF00696E),
            strokeWidth = 4f,
            fillColor = Color(0x3300696E),
        )
    }
}

@Composable
private fun NoMapsKeyFallback(draft: WorkplaceDraft, onCoordinates: (String, String) -> Unit) {
    var lat by rememberSaveable(draft.latitude) { mutableStateOf(draft.latitude?.toString().orEmpty()) }
    var lng by rememberSaveable(draft.longitude) { mutableStateOf(draft.longitude?.toString().orEmpty()) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Text(stringResource(R.string.editor_no_maps_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.editor_no_maps_body), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = lat,
            onValueChange = { lat = it; onCoordinates(lat, lng) },
            label = { Text(stringResource(R.string.editor_latitude)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = lng,
            onValueChange = { lng = it; onCoordinates(lat, lng) },
            label = { Text(stringResource(R.string.editor_longitude)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
