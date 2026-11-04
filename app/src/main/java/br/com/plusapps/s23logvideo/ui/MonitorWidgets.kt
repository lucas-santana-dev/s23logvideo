package br.com.plusapps.s23logvideo.ui

import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.plusapps.s23logvideo.color.LogCurve
import br.com.plusapps.s23logvideo.gl.Scopes
import kotlin.math.ln
import kotlin.math.sqrt

/** Waveform (luma × posição horizontal) e histograma do sinal gravado, com a linha do cinza 18%. */
@Composable
fun ScopesView(s: Scopes, isLog: Boolean, modifier: Modifier = Modifier) {
    val gray = if (isLog) LogCurve.GRAY_OUT.toFloat() else 0.409f
    Row(
        modifier.background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(width = 192.dp, height = 96.dp)) {
            val cw = size.width / Scopes.COLS
            val bh = size.height / Scopes.BINS
            val maxCount = Scopes.ROWS.toFloat()
            for (col in 0 until Scopes.COLS) {
                for (bin in 0 until Scopes.BINS) {
                    val n = s.waveform[col * Scopes.BINS + bin]
                    if (n == 0) continue
                    val a = (sqrt(n / maxCount) * 1.6f).coerceIn(0.15f, 1f)
                    drawRect(
                        Color(0.6f, 1f, 0.6f, a),
                        topLeft = Offset(col * cw, size.height - (bin + 1) * bh), size = Size(cw, bh),
                    )
                }
            }
            val gy = size.height * (1 - gray)
            drawLine(Color(0x88FFFFFF), Offset(0f, gy), Offset(size.width, gy), 1f)
            drawLine(Color(0x88FF5252), Offset(0f, size.height * 0.05f), Offset(size.width, size.height * 0.05f), 1f)
        }
        Canvas(Modifier.size(width = 96.dp, height = 96.dp)) {
            val maxH = (s.histogram.maxOrNull() ?: 1).coerceAtLeast(1)
            val bw = size.width / Scopes.BINS
            for (bin in 0 until Scopes.BINS) {
                // Escala log para as classes pequenas não sumirem.
                val h = (ln(1f + s.histogram[bin]) / ln(1f + maxH)) * size.height
                drawRect(Color(0xCCFFFFFF), topLeft = Offset(bin * bw, size.height - h), size = Size(bw, h))
            }
            val gx = size.width * gray
            drawLine(Color(0x8800E676), Offset(gx, 0f), Offset(gx, size.height), 1f)
        }
    }
}

/** VU vertical: -60..0 dBFS, verde/amarelo/vermelho, com marca em -12 dBFS e aviso de clip. */
@Composable
fun AudioVu(db: Float, clipped: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(10.dp).height(90.dp).background(Color(0x66000000))) {
            val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
            val h = size.height * frac
            val color = when {
                db > -3f -> Color(0xFFFF1744)
                db > -12f -> Color(0xFFFFEA00)
                else -> Color(0xFF00E676)
            }
            drawRect(color, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
            val y12 = size.height * (12f / 60f)
            drawLine(Color.White, Offset(0f, y12), Offset(size.width, y12), 1f)
        }
        Text(
            if (db <= -99f) "-∞" else "%.0f".format(db),
            color = if (clipped) Color(0xFFFF1744) else Color.White, fontSize = 10.sp,
        )
        if (clipped) Text("CLIP", color = Color(0xFFFF1744), fontSize = 10.sp)
    }
}

fun thermalLabel(status: Int): String? = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> null
    PowerManager.THERMAL_STATUS_LIGHT -> "térmico: leve"
    PowerManager.THERMAL_STATUS_MODERATE -> "térmico: moderado"
    PowerManager.THERMAL_STATUS_SEVERE -> "térmico: ALTO"
    PowerManager.THERMAL_STATUS_CRITICAL -> "térmico: CRÍTICO"
    else -> "térmico: EMERGÊNCIA"
}
