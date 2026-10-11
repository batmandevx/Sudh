package com.shuddh.lab.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Brand type: Unbounded (display — logo, titles, big numbers) + Manrope (everything else). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun vf(res: Int, w: Int) = androidx.compose.ui.text.font.Font(
    res, FontWeight(w), variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
)

val Display = FontFamily(vf(com.shuddh.lab.R.font.unbounded, 500), vf(com.shuddh.lab.R.font.unbounded, 600), vf(com.shuddh.lab.R.font.unbounded, 700), vf(com.shuddh.lab.R.font.unbounded, 900))
val Body = FontFamily(vf(com.shuddh.lab.R.font.manrope, 400), vf(com.shuddh.lab.R.font.manrope, 500), vf(com.shuddh.lab.R.font.manrope, 600), vf(com.shuddh.lab.R.font.manrope, 700), vf(com.shuddh.lab.R.font.manrope, 800))

/** Accent themes the user can pick in Settings (primary, secondary). */
enum class AccentTheme(val label: String, val primary: Color, val secondary: Color) {
    EMERALD("Emerald", Color(0xFF34D399), Color(0xFF22D3EE)),
    SAFFRON("Saffron", Color(0xFFFB923C), Color(0xFFFBBF24)),
    VIOLET("Violet", Color(0xFFA78BFA), Color(0xFF60A5FA)),
    ROSE("Rose", Color(0xFFFB7185), Color(0xFFF472B6)),
    OCEAN("Ocean", Color(0xFF38BDF8), Color(0xFF818CF8)),
}

/** Global UI preferences that every screen reads (state-backed, so changes recompose live). */
object UiPrefs {
    /** Current UI language — drives [tr] for titles, sections, buttons and tabs. */
    var lang by mutableStateOf(com.shuddh.lab.core.Lang.EN)
    var accent by mutableStateOf(AccentTheme.EMERALD)
    var reduceMotion by mutableStateOf(false)
    /** Light mode (default) — clean, bright, judge-friendly. */
    var light by mutableStateOf(true)
}

/** Translates an on-screen label into the current UI language (English fallback). */
fun tr(s: String): String = com.shuddh.lab.core.I18n.ui(s, UiPrefs.lang)

object Palette {
    private val L get() = UiPrefs.light
    val light get() = UiPrefs.light
    val bg get() = if (L) Color(0xFFF4F7FB) else Color(0xFF060A12)
    val surface get() = if (L) Color.White else Color(0xFF0E1522)
    val surface2 get() = if (L) Color(0xFFEEF2F8) else Color(0xFF162032)
    val glass get() = veil(0x14)
    val line get() = veil(0x22)
    val text get() = if (L) Color(0xFF0F172A) else Color(0xFFEFF4FA)
    val muted get() = if (L) Color(0xFF5B6B82) else Color(0xFF8C9AB0)
    /** Ink for thin lines / overlays: white on dark, navy on light. */
    val ink get() = if (L) Color(0xFF0F172A) else Color.White
    val accent get() = UiPrefs.accent.primary.let { if (L) darken(it) else it }
    val blue get() = if (L) Color(0xFF2563EB) else Color(0xFF60A5FA)
    val cyan get() = UiPrefs.accent.secondary.let { if (L) darken(it) else it }
    val amber get() = if (L) Color(0xFFD97706) else Color(0xFFFBBF24)
    val red get() = if (L) Color(0xFFE11D48) else Color(0xFFF43F5E)
    val violet get() = if (L) Color(0xFF7C3AED) else Color(0xFFA78BFA)

    /** Readable text colour on a solid fill of [c]. */
    fun on(c: Color): Color = if (c.luminance() > 0.42f) Color(0xFF0B1220) else Color.White
    /** Text on the accent (primary buttons, selected chips). */
    val onAccent get() = on(accent)
    /** Recessed "well" behind charts and stat rows: dark tint on dark, faint navy on light. */
    fun well(alpha: Int): Color = if (L) veil((alpha * 0.3).toInt().coerceAtLeast(0x08)) else Color(alpha shl 24)
    /** Solid card for dialogs and hero panels. */
    val card get() = if (L) Color.White else Color(0xFF0B1220)
    /** Pastel brand colours become legible mid-tones on a light background. */
    fun tint(c: Color): Color = if (L && c.luminance() > 0.55f) Color(c.red * 0.55f, c.green * 0.55f, c.blue * 0.55f, c.alpha) else c

    /** Translucent overlay: white-alpha on dark, navy-alpha on light (so outlines and fills stay visible). */
    fun veil(alpha: Int): Color = if (L) Color(((alpha * 0.9).toInt().coerceIn(0, 255) shl 24) or 0x0F172A) else Color((alpha shl 24) or 0xFFFFFF)

    private fun darken(c: Color) = Color(c.red * 0.72f, c.green * 0.72f, c.blue * 0.72f, c.alpha)
}

@Composable
fun ShuddhTheme(content: @Composable () -> Unit) {
    val base = androidx.compose.material3.Typography()
    val typo = androidx.compose.material3.Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Display), displayMedium = base.displayMedium.copy(fontFamily = Display),
        displaySmall = base.displaySmall.copy(fontFamily = Display), headlineLarge = base.headlineLarge.copy(fontFamily = Display),
        headlineMedium = base.headlineMedium.copy(fontFamily = Display), headlineSmall = base.headlineSmall.copy(fontFamily = Display),
        titleLarge = base.titleLarge.copy(fontFamily = Body), titleMedium = base.titleMedium.copy(fontFamily = Body), titleSmall = base.titleSmall.copy(fontFamily = Body),
        bodyLarge = base.bodyLarge.copy(fontFamily = Body), bodyMedium = base.bodyMedium.copy(fontFamily = Body), bodySmall = base.bodySmall.copy(fontFamily = Body),
        labelLarge = base.labelLarge.copy(fontFamily = Body), labelMedium = base.labelMedium.copy(fontFamily = Body), labelSmall = base.labelSmall.copy(fontFamily = Body),
    )
    MaterialTheme(
        typography = typo,
        colorScheme = if (UiPrefs.light) androidx.compose.material3.lightColorScheme(
            primary = Palette.accent, onPrimary = Palette.onAccent, secondary = Palette.cyan, onSecondary = Palette.on(Palette.cyan),
            background = Palette.bg, surface = Palette.surface, onSurface = Palette.text, onSurfaceVariant = Palette.muted,
            onBackground = Palette.text, surfaceVariant = Palette.surface2, outline = Palette.line, surfaceContainerHigh = Color.White,
            surfaceContainer = Color.White, surfaceContainerHighest = Palette.surface2,
        ) else darkColorScheme(
            primary = Palette.accent, onPrimary = Palette.onAccent, secondary = Palette.cyan,
            background = Palette.bg, surface = Palette.surface, onSurface = Palette.text,
            onBackground = Palette.text, surfaceVariant = Palette.surface2, outline = Palette.line,
        ),
        content = content,
    )
}

/** Status of optional online features and airplane mode. */
@Composable
fun OfflineHud() {
    val ctx = LocalContext.current
    var airplane by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            web = ctx.getSharedPreferences("shuddh", android.content.Context.MODE_PRIVATE).getBoolean("onlineAssistant", false)
            delay(1500)
        }
    }
    val online = remember { ctx.getSharedPreferences("shuddh", android.content.Context.MODE_PRIVATE).getBoolean("onlineMap", false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HudPill((when {
            web && online -> "Web + map enabled · AI runs locally"
            web -> "Web search enabled · AI runs locally"
            online -> "Online map enabled · AI runs locally"
            else -> "Web + map off · AI runs locally"
        }) + if (airplane) " · ✈ on" else "", true)
    }
}

@Composable
private fun HudPill(text: String, good: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.glass)
            .border(1.dp, Palette.line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (good) LiveDot() else Box(Modifier.size(7.dp).background(Palette.muted, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 10.sp, color = Palette.muted, maxLines = 1)
    }
}

/** Gradient headline text. */
@Composable
fun GradientTitle(text: String, size: Int = 30) {
    Text(text, style = TextStyle(brush = SpectrumBrush, fontSize = size.sp, fontWeight = FontWeight.Black, fontFamily = Display, letterSpacing = (-1).sp))
}

@Composable
fun ScreenFrame(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Aurora(intensity = 0.55f)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp).clip(CircleShape).background(Palette.glass)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.text)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.padding(start = if (onBack == null) 8.dp else 0.dp)) {
                    Text(tr(title), fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text, letterSpacing = (-0.3).sp, maxLines = 1)
                    if (subtitle != null) Text(tr(subtitle), fontSize = 11.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.padding(horizontal = 16.dp)) { OfflineHud() }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                Spacer(Modifier.height(110.dp)) // clears the floating tab bar
            }
        }
    }
}

/** Frosted glass card with a soft gradient hairline. */
@Composable
fun Glass(modifier: Modifier = Modifier, glow: Color? = null, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val g = glow ?: if (UiPrefs.light) Color(0xFFCBD5E1) else Color.White
    val border = Brush.linearGradient(listOf(g.copy(alpha = if (UiPrefs.light) 0.55f else 0.45f), Palette.ink.copy(alpha = 0.04f), g.copy(alpha = 0.18f)))
    Column(
        modifier.fillMaxWidth()
            .then(if (UiPrefs.light) Modifier.shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = g.copy(alpha = 0.25f), spotColor = g.copy(alpha = 0.25f)) else Modifier)
            .clip(RoundedCornerShape(22.dp))
            .background(if (UiPrefs.light) Brush.verticalGradient(listOf(Color.White, Color(0xFFF8FAFD))) else Brush.verticalGradient(listOf(Palette.veil(0x1A), Palette.veil(0x0A))))
            .border(1.dp, border, RoundedCornerShape(22.dp)).padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Glass(Modifier.enter()) {
        if (title != null) Text(tr(title), fontWeight = FontWeight.SemiBold, fontFamily = Display, color = Palette.text, fontSize = 14.sp, letterSpacing = 0.2.sp)
        content()
    }
}

/** Primary buttons are spectrum-gradient pills with a press-scale; secondary are glass outlines. */
@Composable
fun Btn(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    val base = modifier.scale(scale).clip(shape).alpha(if (enabled) 1f else 0.4f)
    val styled = if (primary) {
        base.background(Brush.horizontalGradient(listOf(Palette.accent, Palette.cyan)))
    } else {
        base.background(Palette.glass).border(1.dp, Palette.line, shape)
    }
    Box(
        styled.clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), enabled = enabled) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onClick()
        }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            tr(text), color = if (primary) Palette.onAccent else Palette.text, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BtnRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
fun Note(text: String, color: Color = Palette.muted) = Text(tr(text), fontSize = 13.sp, color = color, lineHeight = 18.sp)

@Composable
fun Mono(text: String, color: Color = Palette.text) =
    Text(text, fontSize = 12.sp, color = color, fontFamily = FontFamily.Monospace)

@Composable
fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected, onClick = { onSelect(o) }, label = { Text(tr(label(o)), maxLines = 1) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.accent, selectedLabelColor = Palette.onAccent,
                    labelColor = Palette.text, containerColor = Palette.glass,
                ),
                border = FilterChipDefaults.filterChipBorder(true, o == selected, borderColor = Palette.line),
            )
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Note(label)
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Palette.cyan, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.line),
        )
    }
}

data class Series(val xs: FloatArray, val ys: FloatArray, val color: Color, val dots: Boolean = false, val fill: Boolean = false)

/**
 * Line / scatter chart with optional gradient area fill. With [rainbow], the first series is
 * filled with the colour of each wavelength — the x axis must be in nm.
 */
@Composable
fun LineChart(
    series: List<Series>,
    modifier: Modifier = Modifier.fillMaxWidth().height(190.dp),
    xMin: Float? = null,
    xMax: Float? = null,
    yMin: Float? = null,
    yMax: Float? = null,
    xLabel: String = "",
    markers: List<Pair<Float, Color>> = emptyList(),
    rainbow: Boolean = false,
) {
    val all = series.filter { it.xs.isNotEmpty() }
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(Palette.well(0x66)).border(1.dp, Palette.line, RoundedCornerShape(16.dp))) {
        if (all.isEmpty()) return@Canvas
        val x0 = xMin ?: all.minOf { it.xs.min() }
        val x1 = (xMax ?: all.maxOf { it.xs.max() }).let { if (it == x0) x0 + 1 else it }
        var y0 = yMin ?: all.minOf { it.ys.min() }
        var y1 = yMax ?: all.maxOf { it.ys.max() }
        if (y1 - y0 < 1e-6f) { y0 -= 1; y1 += 1 }
        val padL = 10f; val padB = 30f; val padT = 10f
        val w = size.width - padL * 2; val h = size.height - padB - padT
        fun px(x: Float) = padL + (x - x0) / (x1 - x0) * w
        fun py(y: Float) = padT + h - (y.coerceIn(y0, y1) - y0) / (y1 - y0) * h

        for (k in 0..4) {
            val y = padT + h * k / 4
            drawLine(Palette.ink.copy(alpha = 0.06f), Offset(padL, y), Offset(padL + w, y), 1f)
        }
        markers.forEach { (x, c) ->
            if (x in x0..x1) drawLine(c, Offset(px(x), padT), Offset(px(x), padT + h), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        all.forEachIndexed { si, s ->
            if (s.dots) {
                for (i in s.xs.indices) drawCircle(s.color, 5f, Offset(px(s.xs[i]), py(s.ys[i])))
                return@forEachIndexed
            }
            val line = Path()
            for (i in s.xs.indices) {
                val p = Offset(px(s.xs[i]), py(s.ys[i]))
                if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
            }
            if ((rainbow && si == 0) || s.fill) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(px(s.xs.last()), padT + h); lineTo(px(s.xs.first()), padT + h); close()
                }
                val brush = if (rainbow && si == 0) {
                    val stops = (0..20).map { k ->
                        val nm = x0 + (x1 - x0) * k / 20f
                        k / 20f to nmColor(nm).copy(alpha = 0.85f)
                    }.toTypedArray()
                    Brush.horizontalGradient(*stops, startX = padL, endX = padL + w)
                } else {
                    Brush.verticalGradient(listOf(s.color.copy(alpha = 0.45f), Color.Transparent), startY = padT, endY = padT + h)
                }
                drawPath(area, brush)
            }
            drawPath(line, s.color, style = Stroke(3.5f, cap = StrokeCap.Round))
        }
        val paint = android.graphics.Paint().apply { color = Palette.muted.toArgb(); textSize = 24f; isAntiAlias = true }
        drawContext.canvas.nativeCanvas.apply {
            drawText(String.format("%.0f", x0), padL, size.height - 6f, paint)
            val right = String.format("%.0f %s", x1, xLabel)
            drawText(right, padL + w - paint.measureText(right), size.height - 6f, paint)
            val mid = String.format("%.0f", (x0 + x1) / 2)
            drawText(mid, padL + w / 2 - paint.measureText(mid) / 2, size.height - 6f, paint)
        }
    }
}

/** Wavelength (nm) to an approximate display colour, for drawing spectra in their own colours. */
fun nmColor(nm: Float): Color {
    val (r, g, b) = when {
        nm < 440 -> Triple((440 - nm) / 60, 0f, 1f)
        nm < 490 -> Triple(0f, (nm - 440) / 50, 1f)
        nm < 510 -> Triple(0f, 1f, (510 - nm) / 20)
        nm < 580 -> Triple((nm - 510) / 70, 1f, 0f)
        nm < 645 -> Triple(1f, (645 - nm) / 65, 0f)
        else -> Triple(1f, 0f, 0f)
    }
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

fun Context.toastLong(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

/** Progress through an instrument's procedure: animated segments, done = green, next = glowing amber. */
@Composable
fun StepTracker(steps: List<Pair<String, Boolean>>) {
    val next = steps.indexOfFirst { !it.second }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, (label, done) ->
            val fill by animateFloatAsState(if (done) 1f else if (i == next) 0.35f else 0f, tween(600), label = "step")
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.line)) {
                    Box(
                        Modifier.fillMaxWidth(fill).height(5.dp).clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(if (done) listOf(Palette.accent, Palette.cyan) else listOf(Palette.amber, Palette.amber))),
                    )
                }
                Text(
                    (if (done) "✓ " else "${i + 1} · ") + tr(label), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (done || i == next) Palette.text else Palette.muted, modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

/** Collapsible plain-language explanation of the physics — doubles as School Lab Mode. */
@Composable
fun HowItWorks(lines: List<String>) {
    var open by remember { mutableStateOf(false) }
    Glass(Modifier.clickable { open = !open }, glow = Palette.blue, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔬", fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text("How it works — the science", color = Palette.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (open) "−" else "+", color = Palette.blue, fontSize = 20.sp)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEachIndexed { i, l ->
                    Row {
                        Text("${i + 1}", color = Palette.cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(20.dp))
                        Text(l, color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        tr(text), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Animated verdict ring: sweeps in, coloured by level, with a glyph in the middle. */
@Composable
fun LevelRing(level: com.shuddh.lab.core.Level, size: androidx.compose.ui.unit.Dp = 120.dp) {
    val color = Color(level.argb)
    val sweep = remember(level) { Animatable(0f) }
    LaunchedEffect(level) { sweep.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val glyph = when (level) {
        com.shuddh.lab.core.Level.SAFE -> "✓"
        com.shuddh.lab.core.Level.CAUTION -> "!"
        com.shuddh.lab.core.Level.UNSAFE -> "✕"
        com.shuddh.lab.core.Level.INCONCLUSIVE -> "?"
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f * sweep.value), Color.Transparent)), this.size.minDimension / 1.7f)
            drawArc(color.copy(alpha = 0.15f), 0f, 360f, false, style = Stroke(14f))
            drawArc(color, -90f, 360f * sweep.value, false, style = Stroke(14f, cap = StrokeCap.Round))
        }
        Text(glyph, color = color, fontSize = (size.value / 2.4f).sp, fontWeight = FontWeight.Black, modifier = Modifier.alpha(sweep.value))
    }
}

@Composable
fun StatTile(value: String, label: String, color: Color = Palette.text, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Palette.well(0x33)).border(1.dp, Palette.line, RoundedCornerShape(16.dp)).padding(12.dp),
    ) {
        val n = value.toIntOrNull()
        // Shrink long values so tiles never wrap mid-word.
        val fs = when { value.length <= 3 -> 22.sp; value.length <= 5 -> 17.sp; else -> 14.sp }
        if (n != null) CountUp(n) { Text(it, color = color, fontSize = fs, fontWeight = FontWeight.Black, fontFamily = Display, maxLines = 1, softWrap = false) }
        else Text(value, color = color, fontSize = fs, fontWeight = FontWeight.Black, fontFamily = Display, maxLines = 1, softWrap = false)
        Text(label, color = Palette.muted, fontSize = 11.sp, maxLines = 1)
    }
}

/** Horizontal stacked bar for safe / caution / unsafe counts, growing in on first show. */
@Composable
fun TallyBar(safe: Int, caution: Int, unsafe: Int) {
    val total = (safe + caution + unsafe).coerceAtLeast(1)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Palette.line)) {
        Row(Modifier.fillMaxWidth(grow.value.coerceAtLeast(0.001f)).height(10.dp)) {
            if (safe > 0) Box(Modifier.weight(safe.toFloat() / total).fillMaxSize().background(Palette.accent))
            if (caution > 0) Box(Modifier.weight(caution.toFloat() / total).fillMaxSize().background(Palette.amber))
            if (unsafe > 0) Box(Modifier.weight(unsafe.toFloat() / total).fillMaxSize().background(Palette.red))
        }
    }
}
