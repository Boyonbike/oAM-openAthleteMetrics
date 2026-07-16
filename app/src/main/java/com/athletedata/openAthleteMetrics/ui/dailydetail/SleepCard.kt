package com.athletedata.openAthleteMetrics.ui.dailydetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val HOUR_LABEL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Smaller than [TypographyMeta] (12sp) — a scoped exception to the app's 3-style
 * typography system for the hypnogram's single dense axis row (onset + hourly ticks + wake),
 * which needs to fit more labels on one line than any other text in this card.
 */
private val HypnogramLabelStyle = TypographyMeta.copy(fontSize = 10.sp)

/**
 * Slightly bigger and bold — a scoped exception to the app's 3-style typography system for
 * the card's duration/time-range headline, which should stand out more than [TypographyTitle].
 */
private val SleepHeadlineStyle = TypographyTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)

// ── Sleep tile ────────────────────────────────────────────────────────────────

@Composable
internal fun SleepTile(
    state: DailyDetailUiState.Success,
    isExpanded: Boolean,
    openReadingTables: Set<String>,
    onToggle: () -> Unit,
    onToggleReadings: (String) -> Unit,
) {
    val data = state.sleep
    CategoryTile(
        title = "Sleep",
        isExpanded = isExpanded,
        onToggle = onToggle,
        collapsedSummary = {
            if (data == null) {
                EmptyStateText("No sleep data")
            } else {
                val stageParts = buildList {
                    data.deepMinutes?.let { add("D ${formatDurationShort(it)}") }
                    data.lightMinutes?.let { add("L ${formatDurationShort(it)}") }
                    data.remMinutes?.let { add("R ${formatDurationShort(it)}") }
                }
                Text(
                    text = if (stageParts.isEmpty()) data.formattedDuration
                           else "${data.formattedDuration} · ${stageParts.joinToString(" ")}",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        expandedContent = {
            if (data == null) {
                EmptyStateText("No sleep data")
            } else {
                SleepDurationSection(data)
                SleepDurationHistorySection(data)
                val hasStages = data.deepMinutes != null || data.lightMinutes != null ||
                    data.remMinutes != null || data.awakeMinutes != null
                if (hasStages) {
                    StageGrid(data)
                }
                if (data.hypnogramSegments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Hypnogram", style = TypographyTitle)
                        Hypnogram(
                            segments = data.hypnogramSegments,
                            onsetTimeLabel = data.onsetTimeLabel,
                            wakeTimeLabel = data.wakeTimeLabel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HypnogramLegend()
                    }
                }
            }
        },
    )
}

// ── Duration row ──────────────────────────────────────────────────────────────

@Composable
private fun SleepDurationSection(data: SleepData) {
    val timeRangeLabel = if (data.onsetTimeLabel != null && data.wakeTimeLabel != null) {
        "${data.onsetTimeLabel} → ${data.wakeTimeLabel}"
    } else null

    val avg = data.averages
    val avgDurationText = avg.avgTotalMinutes?.let { "Avg ${formatDurationShort(it)}" }
    val avgTimeRangeText = if (avg.avgOnsetTime != null && avg.avgWakeTime != null) {
        "${avg.avgOnsetTime.format(HOUR_LABEL_FMT)} → ${avg.avgWakeTime.format(HOUR_LABEL_FMT)}"
    } else null

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SleepStatTile(
            value = data.formattedDuration,
            avgText = avgDurationText,
            modifier = Modifier.weight(1f),
        )
        SleepStatTile(
            value = timeRangeLabel,
            avgText = avgTimeRangeText,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SleepStatTile(
    value: String?,
    avgText: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (value == null) {
            EmptyStateText("No data")
        } else {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, style = SleepHeadlineStyle)
                    avgText?.let {
                        Text(it, style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── Duration history ──────────────────────────────────────────────────────────

@Composable
private fun SleepDurationHistorySection(data: SleepData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Sleep Duration History", style = TypographyTitle)
        if (data.durationHistory.size >= 2) {
            ReadingsLineChart(
                readings = data.durationHistory,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        } else {
            EmptyStateText("Not enough history")
        }
    }
}

// ── Stage grid ────────────────────────────────────────────────────────────────

private val STAGE_COLORS = mapOf(
    SleepStage.DEEP  to Color(0xFF1A237E),
    SleepStage.LIGHT to Color(0xFF5C6BC0),
    SleepStage.REM   to Color(0xFF9C27B0),
    SleepStage.AWAKE to Color(0xFFB0BEC5),
)

@Composable
private fun StageGrid(data: SleepData) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sleep Stages", style = TypographyTitle)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StageBox(
                    color = STAGE_COLORS.getValue(SleepStage.DEEP), label = "Deep",
                    minutes = data.deepMinutes, pct = data.deepPct,
                    avgMinutes = data.averages.avgDeepMinutes, avgPct = data.averages.avgDeepPct,
                    modifier = Modifier.weight(1f),
                )
                StageBox(
                    color = STAGE_COLORS.getValue(SleepStage.LIGHT), label = "Light",
                    minutes = data.lightMinutes, pct = data.lightPct,
                    avgMinutes = data.averages.avgLightMinutes, avgPct = data.averages.avgLightPct,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StageBox(
                    color = STAGE_COLORS.getValue(SleepStage.REM), label = "REM",
                    minutes = data.remMinutes, pct = data.remPct,
                    avgMinutes = data.averages.avgRemMinutes, avgPct = data.averages.avgRemPct,
                    modifier = Modifier.weight(1f),
                )
                StageBox(
                    color = STAGE_COLORS.getValue(SleepStage.AWAKE), label = "Awake",
                    minutes = data.awakeMinutes, pct = data.awakePct,
                    avgMinutes = data.averages.avgAwakeMinutes, avgPct = data.averages.avgAwakePct,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StageBox(
    color: Color,
    label: String,
    minutes: Int?,
    pct: Double?,
    avgMinutes: Int?,
    avgPct: Double?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(color, shape = MaterialTheme.shapes.extraSmall),
            )
            Text(label, style = TypographyTitle)
        }
        if (minutes == null) {
            EmptyStateText("No data")
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatDurationShort(minutes), style = TypographyMeta)
                        avgMinutes?.let {
                            Text(
                                "Avg ${formatDurationShort(it)}",
                                style = TypographyMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        pct?.let { Text("%.0f%%".format(it), style = TypographyMeta) }
                        avgPct?.let {
                            Text(
                                "%.0f%% avg".format(it),
                                style = TypographyMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Hypnogram ─────────────────────────────────────────────────────────────────

/**
 * Drops the hourly tick immediately after the onset anchor (frac `0f`) and/or immediately
 * before the wake anchor (frac `1f`) if it would sit close enough to overlap that anchor's
 * label — onset and wake themselves always render. Only checks the single nearest tick at
 * each end, since hourly ticks are always ≥1 hour apart and only ever crowd the two fixed
 * anchor points in practice.
 */
private fun dropCollidingEdgeTicks(
    labels: List<Pair<Float, String>>,
    widthPx: Float,
    labelWidthPx: Int,
): List<Pair<Float, String>> {
    val onset = labels.firstOrNull { it.first == 0f }
    val wake = labels.firstOrNull { it.first == 1f }
    if (onset == null && wake == null) return labels

    val minGapPx = labelWidthPx * 1.1f
    val ticks = labels.filter { it !== onset && it !== wake }.sortedBy { it.first }.toMutableList()

    if (onset != null && ticks.isNotEmpty() && ticks.first().first * widthPx < minGapPx) {
        ticks.removeAt(0)
    }
    if (wake != null && ticks.isNotEmpty() && widthPx - ticks.last().first * widthPx < minGapPx) {
        ticks.removeAt(ticks.lastIndex)
    }

    return buildList {
        onset?.let { add(it) }
        addAll(ticks)
        wake?.let { add(it) }
    }
}

private fun hourlyTicks(startMs: Long, endMs: Long, zone: ZoneId): List<ZonedDateTime> {
    val endInstant = Instant.ofEpochMilli(endMs)
    var tick = Instant.ofEpochMilli(startMs).atZone(zone).withMinute(0).withSecond(0).withNano(0)
    if (tick.toInstant().toEpochMilli() < startMs) tick = tick.plusHours(1)
    return buildList {
        while (!tick.toInstant().isAfter(endInstant)) {
            add(tick)
            tick = tick.plusHours(1)
        }
    }
}

@Composable
private fun Hypnogram(
    segments: List<HypnogramSegment>,
    onsetTimeLabel: String?,
    wakeTimeLabel: String?,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    val startMs = segments.minOf { it.startMs }
    val endMs = segments.maxOf { it.endMs }
    val totalMs = (endMs - startMs).toFloat()
    if (totalMs <= 0f) return

    val zone = remember { ZoneId.systemDefault() }
    val ticks = remember(startMs, endMs) { hourlyTicks(startMs, endMs, zone) }
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelWidthPx = remember(textMeasurer) {
        textMeasurer.measure("00:00", style = HypnogramLabelStyle).size.width
    }

    // Bottom-axis labels: onset at fraction 0, each on-the-hour tick, wake at fraction 1 —
    // all on the same line, per the request to drop the separate row above the bar.
    val bottomLabels = remember(startMs, endMs, ticks, onsetTimeLabel, wakeTimeLabel) {
        buildList {
            onsetTimeLabel?.let { add(0f to it) }
            ticks.forEach { t ->
                val frac = (t.toInstant().toEpochMilli() - startMs) / totalMs
                add(frac to t.format(HOUR_LABEL_FMT))
            }
            wakeTimeLabel?.let { add(1f to it) }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(40.dp)) {
            segments.forEach { seg ->
                val startFrac = (seg.startMs - startMs) / totalMs
                val widthFrac = (seg.endMs - seg.startMs) / totalMs
                drawRect(
                    color = STAGE_COLORS[seg.stage] ?: Color.Gray,
                    topLeft = Offset(startFrac * size.width, 0f),
                    size = Size(widthFrac * size.width, size.height),
                )
            }
            ticks.forEach { t ->
                val frac = (t.toInstant().toEpochMilli() - startMs) / totalMs
                drawLine(
                    color = tickColor,
                    start = Offset(frac * size.width, 0f),
                    end = Offset(frac * size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val visibleLabels = remember(bottomLabels, widthPx, labelWidthPx) {
                dropCollidingEdgeTicks(bottomLabels, widthPx, labelWidthPx)
            }
            visibleLabels.forEach { (frac, label) ->
                Text(
                    text = label,
                    style = HypnogramLabelStyle,
                    color = labelColor,
                    modifier = Modifier.offset {
                        IntOffset((frac * widthPx - labelWidthPx / 2f).roundToInt(), 0)
                    },
                )
            }
        }
    }
}

@Composable
private fun HypnogramLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        listOf(
            SleepStage.DEEP  to "Deep",
            SleepStage.LIGHT to "Light",
            SleepStage.REM   to "REM",
            SleepStage.AWAKE to "Awake",
        ).forEach { (stage, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(STAGE_COLORS[stage] ?: Color.Gray, shape = MaterialTheme.shapes.extraSmall),
                )
                Text(label, style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
