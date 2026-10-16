package com.hvkeyn.ceditneuro.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hvkeyn.ceditneuro.chat.ChartSheet

@Composable
fun ChartDrawing(text: String) {
    val sheet = remember(text) { ChartSheet.parse(text) }
    if (sheet == null) {
        CodeFallback(text)
        return
    }
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        Color(0xFF2A9D8F),
        Color(0xFFE9C46A),
        Color(0xFFE76F51),
        Color(0xFF6D597A),
        Color(0xFF457B9D),
    )
    val ink = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val plotHeight = when (sheet.type) {
        "spark" -> 28.dp
        "bars" -> (sheet.points * 22).coerceIn(72, 360).dp
        else -> 180.dp
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (sheet.title.isNotEmpty()) {
            Text(text = sheet.title, style = MaterialTheme.typography.labelLarge, color = ink)
        }
        if (sheet.type == "spark") {
            Text(
                text = ChartSheet.spark(sheet.columns.first()),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = colors.first(),
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
            val low = sheet.columns.minOf { it.min() }
            val high = sheet.columns.maxOf { it.max() }
            val span = (high - low).takeIf { it > 0.0 } ?: 1.0
            fun yOf(value: Double): Float {
                val t = ((value - low) / span).toFloat()
                return size.height - t * size.height
            }
            when (sheet.type) {
                "bars" -> {
                    val row = size.height / sheet.points
                    val maxAbs = sheet.columns.maxOf { column -> column.maxOf { kotlin.math.abs(it) } }
                        .takeIf { it > 0.0 } ?: 1.0
                    sheet.columns.first().forEachIndexed { index, value ->
                        val width = (kotlin.math.abs(value) / maxAbs).toFloat() * size.width * 0.72f
                        drawRoundRect(
                            color = colors[index % colors.size],
                            topLeft = Offset(size.width * 0.22f, index * row + row * 0.2f),
                            size = Size(width, row * 0.6f),
                            cornerRadius = CornerRadius(6f, 6f),
                        )
                    }
                }
                "columns" -> {
                    val group = size.width / sheet.points
                    val bar = group / (sheet.columns.size + 0.6f)
                    sheet.columns.forEachIndexed { series, column ->
                        column.forEachIndexed { index, value ->
                            val top = yOf(value.coerceAtLeast(low))
                            val bottom = yOf(low)
                            drawRoundRect(
                                color = colors[series % colors.size],
                                topLeft = Offset(index * group + series * bar + bar * 0.15f, top),
                                size = Size(bar * 0.8f, (bottom - top).coerceAtLeast(2f)),
                                cornerRadius = CornerRadius(4f, 4f),
                            )
                        }
                    }
                }
                "heatmap" -> {
                    val cellW = size.width / sheet.points
                    val cellH = size.height / sheet.columns.size
                    sheet.columns.forEachIndexed { series, column ->
                        column.forEachIndexed { index, value ->
                            val t = ((value - low) / span).toFloat().coerceIn(0f, 1f)
                            drawRect(
                                color = colors.first().copy(alpha = 0.15f + 0.85f * t),
                                topLeft = Offset(index * cellW, series * cellH),
                                size = Size(cellW - 2f, cellH - 2f),
                            )
                        }
                    }
                }
                else -> {
                    drawLine(grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
                    sheet.columns.forEachIndexed { series, column ->
                        val path = Path()
                        column.forEachIndexed { index, value ->
                            val x = if (column.size == 1) size.width / 2f
                            else index / (column.size - 1).toFloat() * size.width
                            val y = yOf(value)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = colors[series % colors.size],
                            style = Stroke(width = 4f, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
        val caption = buildString {
            if (sheet.yLabel.isNotEmpty()) append(sheet.yLabel)
            if (sheet.series.size > 1) {
                if (isNotEmpty()) append(" · ")
                append(sheet.series.joinToString(", "))
            }
            if (sheet.xLabel.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(sheet.xLabel)
            }
        }
        if (sheet.xLabels.isNotEmpty() && sheet.type != "spark") {
            val labels = if (sheet.xLabels.size <= 6) sheet.xLabels
            else listOf(sheet.xLabels.first(), "…", sheet.xLabels.last())
            Text(
                text = labels.joinToString("   "),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = ink.copy(alpha = 0.7f),
            )
        }
        if (caption.isNotEmpty()) {
            Text(text = caption, style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun CodeFallback(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
