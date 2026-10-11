package com.shuddh.lab.instruments

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.shuddh.lab.core.Analytes
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.Words
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.StepTracker
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.Chips
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section

/** Minimum SNF / fat (%) used for the added-water estimate. FSSAI limits vary by state; these are common values. */
private enum class MilkType(val label: String, val snf: Double, val fat: Double) {
    COW("Cow", 8.5, 3.5),
    BUFFALO("Buffalo", 9.0, 6.0),
    MIXED("Mixed / toned", 8.5, 4.5),
}

@Composable
fun FloatScreen(app: AppState) {
    var type by remember { mutableStateOf(MilkType.COW) }
    var lr by remember { mutableStateOf("") }
    var temp by remember { mutableStateOf("27") }
    var fat by remember { mutableStateOf("") }

    val lrV = lr.toDoubleOrNull()
    val tV = temp.toDoubleOrNull()
    val fatV = fat.toDoubleOrNull() ?: type.fat
    // ISI lactometers are calibrated at 27 °C; add 0.2 per °C above, subtract below.
    val clr = if (lrV != null && tV != null) lrV + 0.2 * (tV - 27) else null
    // Richmond's formula: SNF % = CLR/4 + 0.22·Fat + 0.72
    val snf = clr?.let { it / 4 + 0.22 * fatV + 0.72 }
    val water = snf?.let { ((type.snf - it) / type.snf * 100).coerceAtLeast(0.0) }

    fun verdict(): Outcome {
        val ev = listOf(
            Evidence("OBSERVATION", "Lactometer reading ${fmt(lrV!!)} at ${fmt(tV!!)} °C → corrected CLR ${fmt(clr!!)}"),
            Evidence("PATTERN", "SNF = CLR/4 + 0.22×fat(${fmt(fatV)}) + 0.72 = ${fmt(snf!!)}% (Richmond)"),
            Evidence("QUALITY", if (fat.toDoubleOrNull() == null) "Fat not entered — assumed ${type.fat}% (${type.label})" else "Fat entered: ${fmt(fatV)}%", fat.toDoubleOrNull() != null),
            Evidence("QUALITY", "Lactometer reading ${fmt(lrV!!)} is in the normal 20–36 range for milk", lrV!! in 20.0..36.0),
            Evidence("CALIBRATION", "Temperature ${fmt(tV!!)} °C within the 15–35 °C correction range (±0.2 per °C from 27 °C)", tV!! in 15.0..35.0),
            Evidence("HYPOTHESIS", "Shortfall vs ${type.label} minimum SNF ${type.snf}% → ≈${fmt(water!!)}% added water", water < 3),
        )
        val (lvl, adv) = when {
            water < 3 -> Level.SAFE to listOf(Words.ok)
            water < 10 -> Level.CAUTION to listOf(Words.waterAdded, Words.retest)
            else -> Level.UNSAFE to listOf(Words.waterAdded, Words.report)
        }
        return Outcome(
            "Shuddh Float", "milk_water", Txt("Water in milk", "दूध में पानी", "ಹಾಲಿನಲ್ಲಿ ನೀರು"), water, "%", lvl,
            "SNF ${fmt(snf)}% vs ${type.snf}% minimum → ≈${fmt(water)}% added water", adv, ev,
            "FSSAI minimum SNF: cow 8.5%, buffalo 9.0% (state standards vary). Density can be masked by adding sugar/starch — run Spectrum starch test too.",
        )
    }

    ScreenFrame("Shuddh Float", "Lactometer density → added water", onBack = { app.back() }) {
        Section("Milk") {
            Chips(MilkType.entries, type, { it.label }) { type = it }
            Note("Float an ISI lactometer in milk at room temperature; read the scale at the milk surface.")
            OutlinedTextField(lr, { lr = it }, label = { Text("Lactometer reading (e.g. 28)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(temp, { temp = it }, label = { Text("Milk temperature °C") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fat, { fat = it }, label = { Text("Fat % (optional, from label or Gerber test)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (snf != null) {
            Section("Live calculation") {
                LactometerAnim(lrV ?: 28.0, water ?: 0.0)
                Note("CLR ${fmt(clr!!)} · SNF ${fmt(snf)}% · added water ≈ ${fmt(water!!)}%", if (water < 3) Palette.accent else Palette.amber)
            }
        }
        HowItWorks(listOf(
            "Milk is denser than water because of its solids-not-fat (SNF): protein, lactose and minerals.",
            "Adding water lowers density, so the lactometer floats deeper. Temperature changes density too, so the reading is corrected to 27 °C.",
            "Richmond’s formula turns density and fat into SNF; the shortfall against the legal minimum estimates added water.",
        ))
        Btn("Get verdict", Modifier.fillMaxWidth(), enabled = snf != null) { app.show(verdict()) }
        Note("Related: ${Analytes.milk.en} adulterant tests (detergent, starch, urea) are in Shuddh Spectrum.")
    }
}


/** A lactometer bobbing in a glass of milk: it floats lower as water thins the milk; the milk pales with added water. */
@androidx.compose.runtime.Composable
private fun LactometerAnim(lr: Double, water: Double) {
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "lacto")
    val bob by inf.animateFloat(-1f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600), androidx.compose.animation.core.RepeatMode.Reverse), label = "bob")
    val depth by androidx.compose.animation.core.animateFloatAsState(((36 - lr) / 16).toFloat().coerceIn(0f, 1f), androidx.compose.animation.core.tween(900), label = "depth")
    val thin by androidx.compose.animation.core.animateFloatAsState((water / 30).toFloat().coerceIn(0f, 1f), androidx.compose.animation.core.tween(900), label = "thin")
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val w = size.width; val h = size.height
        val gx = w / 2 - 70f; val gw = 140f; val top = 20f; val milkTop = h * 0.3f
        // Glass
        drawRoundRect(Palette.ink.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(gx, top), androidx.compose.ui.geometry.Size(gw, h - top - 6f), androidx.compose.ui.geometry.CornerRadius(18f))
        // Milk (bluish-white as water is added)
        val milk = Color(0xFFFFFBF0).copy(alpha = 0.9f - 0.35f * thin)
        drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(milk, Color(0xFFDDE7F5).copy(alpha = 0.7f)), milkTop, h), androidx.compose.ui.geometry.Offset(gx + 4f, milkTop + bob * 2f), androidx.compose.ui.geometry.Size(gw - 8f, h - milkTop - 12f), androidx.compose.ui.geometry.CornerRadius(14f))
        // Lactometer: bulb + stem; sinks deeper for lower readings
        val cx = w / 2
        val bulbY = milkTop + 40f + depth * 50f + bob * 3f
        drawLine(Color(0xFF94A3B8), androidx.compose.ui.geometry.Offset(cx, bulbY - 120f), androidx.compose.ui.geometry.Offset(cx, bulbY), 8f, androidx.compose.ui.graphics.StrokeCap.Round)
        drawOval(Color(0xFF64748B), androidx.compose.ui.geometry.Offset(cx - 16f, bulbY - 10f), androidx.compose.ui.geometry.Size(32f, 60f))
        for (k in 0..8) {
            val y = bulbY - 112f + k * 10f
            drawLine(Color(0xFF0F172A), androidx.compose.ui.geometry.Offset(cx - 6f, y), androidx.compose.ui.geometry.Offset(cx + (if (k % 2 == 0) 8f else 4f), y), 2f)
        }
        // Surface reading marker
        drawLine(Color(0xFF22D3EE), androidx.compose.ui.geometry.Offset(cx + 14f, milkTop), androidx.compose.ui.geometry.Offset(cx + 60f, milkTop - 20f), 3f)
        drawContext.canvas.nativeCanvas.drawText("LR ${com.shuddh.lab.core.fmt(lr)}", cx + 64f, milkTop - 16f,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 34f; isFakeBoldText = true })
    }
}
