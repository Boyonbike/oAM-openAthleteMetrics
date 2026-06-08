package com.athletedata.openAthleteMetrics.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta

@Composable
fun PillSelector(
    tabs: List<String>,
    selectedIndex: Int,
    continuousIndex: Float = selectedIndex.toFloat(),
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabWidths = remember(tabs.size) { Array(tabs.size) { mutableStateOf(0f) } }
    val pillColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f))
            .padding(2.dp)
            .drawBehind {
                val anim = continuousIndex.coerceIn(0f, (tabs.lastIndex).toFloat())
                val lo = anim.toInt().coerceIn(0, tabs.lastIndex)
                val hi = (lo + 1).coerceAtMost(tabs.lastIndex)
                val frac = (anim - lo).coerceIn(0f, 1f)

                val leftOfLo = (0 until lo).sumOf { tabWidths[it].value.toDouble() }.toFloat()
                val leftOfHi = (0 until hi).sumOf { tabWidths[it].value.toDouble() }.toFloat()
                val pillLeft = lerp(leftOfLo, leftOfHi, frac)
                val pillWidth = lerp(tabWidths[lo].value, tabWidths[hi].value, frac)

                drawRoundRect(
                    color = pillColor,
                    topLeft = Offset(pillLeft, 0f),
                    size = Size(pillWidth, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val textColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary
                              else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(150),
                label = "pill_tab_color_$index",
            )
            Box(
                modifier = Modifier
                    .onSizeChanged { tabWidths[index].value = it.width.toFloat() }
                    .clickable(enabled = !isSelected) { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = TypographyMeta,
                    color = textColor,
                )
            }
        }
    }
}
