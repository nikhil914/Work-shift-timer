package com.arrivelog.ui.home

import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.arrivelog.R
import com.arrivelog.domain.home.DayBadge
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.SignalSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val TIME_24H: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val DAY_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM")
internal val DAY_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d")
internal val WEEK_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

internal fun Instant.hhmm(zone: ZoneId): String = atZone(zone).format(TIME_24H)

@Composable
internal fun durationText(d: Duration): String {
    val hours = d.toHours()
    val minutes = d.toMinutesPart()
    return if (hours > 0) stringResource(R.string.duration_h_min, hours, minutes) else stringResource(R.string.duration_min, minutes)
}

@Composable
internal fun statusText(status: DayStatus): String = stringResource(
    when (status) {
        DayStatus.WORKING -> R.string.status_working
        DayStatus.DAY_OFF -> R.string.status_day_off
        DayStatus.WFH -> R.string.status_wfh
        DayStatus.LEAVE -> R.string.status_leave
        DayStatus.HOLIDAY -> R.string.status_holiday
        DayStatus.WEEKEND -> R.string.status_weekend
    },
)

@Composable
internal fun badgeText(badge: DayBadge): String = when (badge) {
    DayBadge.OnTime -> stringResource(R.string.badge_on_time)
    is DayBadge.Late -> badge.minutes.toInt().let { m -> pluralStringResource(R.plurals.badge_late, m, m) }
    DayBadge.NotYet -> stringResource(R.string.badge_not_yet)
    DayBadge.Missing -> stringResource(R.string.badge_missing)
    is DayBadge.Off -> statusText(badge.status)
    DayBadge.ExtraDay -> stringResource(R.string.badge_extra_day)
}

@Composable
internal fun BadgeChip(badge: DayBadge) {
    val scheme = MaterialTheme.colorScheme
    val (container, label) = when (badge) {
        DayBadge.OnTime -> scheme.secondaryContainer to scheme.onSecondaryContainer
        is DayBadge.Late -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        DayBadge.Missing -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    AssistChip(
        onClick = {},
        label = { Text(badgeText(badge)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = container, labelColor = label),
        border = null,
    )
}

/** Source "icon" as a glyph with a spoken label (no icon-font dependency). */
@Composable
internal fun SourceGlyph(source: SignalSource?, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    if (source == null) return
    val (glyph, label) = when (source) {
        SignalSource.GEOFENCE -> "📍" to stringResource(R.string.source_geofence)
        SignalSource.WIFI -> "📶" to stringResource(R.string.source_wifi)
        SignalSource.MANUAL -> "✋" to stringResource(R.string.source_manual)
    }
    Text(glyph, color = color, modifier = modifier.semantics { contentDescription = label })
}

@Composable
internal fun sourceName(source: SignalSource): String = stringResource(
    when (source) {
        SignalSource.GEOFENCE -> R.string.source_geofence
        SignalSource.WIFI -> R.string.source_wifi
        SignalSource.MANUAL -> R.string.source_manual
    },
)
