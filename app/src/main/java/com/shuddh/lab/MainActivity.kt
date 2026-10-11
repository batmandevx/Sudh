package com.shuddh.lab

import android.Manifest
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.shuddh.lab.instruments.EchoScreen
import com.shuddh.lab.instruments.FloatScreen
import com.shuddh.lab.instruments.NamiScreen
import com.shuddh.lab.instruments.MagnetoScreen
import com.shuddh.lab.ui.MeshScreen
import com.shuddh.lab.ui.AssistantScreen
import com.shuddh.lab.ui.ModelsScreen
import com.shuddh.lab.instruments.WhistleScreen
import com.shuddh.lab.instruments.LensScreen
import com.shuddh.lab.instruments.NirScreen
import com.shuddh.lab.instruments.PolarScreen
import com.shuddh.lab.instruments.ScatterScreen
import com.shuddh.lab.instruments.SpectrumScreen
import com.shuddh.lab.instruments.StripScreen
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.CommunityScreen
import com.shuddh.lab.ui.GuideScreen
import com.shuddh.lab.ui.HistoryScreen
import com.shuddh.lab.ui.HomeScreen
import com.shuddh.lab.ui.InsightsScreen
import com.shuddh.lab.ui.OnboardingScreen
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ResultScreen
import com.shuddh.lab.ui.BadgesScreen
import com.shuddh.lab.ui.FoodGuideScreen
import com.shuddh.lab.ui.Screen
import com.shuddh.lab.ui.SettingsScreen
import com.shuddh.lab.ui.ShuddhTheme
import com.shuddh.lab.ui.screenIn
import com.shuddh.lab.ui.tabs

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Light theme by default; status-bar icons follow the theme.
        com.shuddh.lab.ui.UiPrefs.light = getSharedPreferences("shuddh_ui", MODE_PRIVATE).getBoolean("light", true)
        // Debug: adb shell am start ... --ez light false
        if (intent?.hasExtra("light") == true) intent.getBooleanExtra("light", true).let { l ->
            com.shuddh.lab.ui.UiPrefs.light = l; getSharedPreferences("shuddh_ui", MODE_PRIVATE).edit().putBoolean("light", l).apply()
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = com.shuddh.lab.ui.UiPrefs.light
        app = AppState(applicationContext)
        // Debug hook: --ez dumptrain true → logs Purity training samples (features only).
        if (intent?.getBooleanExtra("dumptrain", false) == true) com.shuddh.lab.core.PurityTrain.load(com.shuddh.lab.core.Prefs(this).json("purity_train")).forEach { s ->
            android.util.Log.w("PurityDump", "${s.label} " + s.f.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${String.format(java.util.Locale.US, "%.3f", it.value)}" })
        }
        // Debug hook: --ez ringinfo true → logs what the vivo light service supports.
        if (intent?.getBooleanExtra("ringinfo", false) == true) com.shuddh.lab.core.BackLight.probe().lines().forEach { android.util.Log.w("RingInfo", it) }
        // Debug hook: --es backlight "ff0000:500:0" (rgb:type:preview) → lights the vivo back ring and logs the result.
        intent?.getStringExtra("say")?.let { app.voice.speak(it, app.lang) }
        // Developer test fixture: --ez purity_fixture true → next 5 Purity runs show the scripted results, badged TEST DATA.
        if (intent?.getBooleanExtra("purity_fixture", false) == true) app.prefs.putJson("purity_fixture", org.json.JSONObject().put("left", 5))
        // Debug hook: --es gamelight "colorIdx:id:type:subtype:times"
        intent?.getStringExtra("gamelight")?.let { g -> val v = g.split(":").map { it.toIntOrNull() ?: 0 }
            com.shuddh.lab.core.BackLight.setGame(this, v.getOrElse(0) { 0 }, v.getOrElse(1) { 1001 }, v.getOrElse(2) { 1 }, v.getOrElse(3) { 0 }, v.getOrElse(4) { 0 }) }
        intent?.getStringExtra("backlight")?.let { spec ->
            val (rgb, type, prev) = (spec.split(":") + listOf("500", "0")).let { Triple(it[0], it[1], it[2]) }
            if (rgb == "off") com.shuddh.lab.core.BackLight.off(this) else {
                // spec "rrggbb:type:preview[:effect]" — effect -1 = constant colour, ≥0 = prebaked effect.
                val eff = spec.split(":").getOrNull(3)?.toIntOrNull() ?: -1
                val enc = spec.split(":").getOrNull(4)?.toIntOrNull() ?: 0
                val ok = com.shuddh.lab.core.BackLight.setRaw(this, (0xFF000000 or rgb.toLong(16)).toInt(), 0, type.toInt(), prev == "1", enc = enc, effect = eff)
                android.util.Log.w("BackLight", "available=${com.shuddh.lab.core.BackLight.available()} ok=$ok err=${com.shuddh.lab.core.BackLight.lastError}")
            }
        }
        // Debug hook: adb shell am start ... --es llmtest TOOLS:CPU  → runs one prompt and logs the result.
        intent?.getStringExtra("llmtest")?.let { spec ->
            val (r, b) = spec.split(":").let { com.shuddh.lab.core.ModelRole.valueOf(it[0]) to it.getOrElse(1) { "GPU" } }
            app.llm.setBackendPref(r, b)
            val q = intent.getStringExtra("q") ?: "has ramesh dairy failed before?"
            lifecycleScope.launch {
                val prompt = if (intent.hasExtra("facts")) com.shuddh.lab.core.Agent.chatPrompt(
                    q, com.shuddh.lab.core.ToolCall(intent.getStringExtra("tool") ?: "kitchen_score", emptyMap(), "", true), intent.getStringExtra("facts")!!,
                    runCatching { com.shuddh.lab.core.Lang.valueOf(intent.getStringExtra("lang") ?: "EN") }.getOrDefault(com.shuddh.lab.core.Lang.EN),
                ) else if (intent.getBooleanExtra("raw", false)) q else if (r == com.shuddh.lab.core.ModelRole.TOOLS) com.shuddh.lab.core.Agent.routerPrompt(q, false)
                    else com.shuddh.lab.core.LocalLlm.chatml("You are concise.", q)
                val out = runCatching { app.llm.generate(r, prompt) }.getOrElse { "ERROR ${it.message}" }
                android.util.Log.i("ShuddhLLM", "[$r/$b] ${app.llm.lastStats[r]} :: $out")
            }
        }
        // Debug hook: --es embedtest "query" → ranks fixed descriptions with the on-device sentence encoder.
        intent?.getStringExtra("embedtest")?.let { q ->
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val docs = listOf(
                    "milk, carton, packet, toned milk, best before",
                    "fruit, banana, vegetable",
                    "jar, honey, food, pure honey",
                    "person, selfie, smile",
                    "bottle, water, drink, packaged drinking water",
                )
                val t0 = System.currentTimeMillis()
                val vecs = docs.map { app.media.embed(it) }
                val mean = FloatArray(vecs[0].size) { i -> vecs.map { it[i] }.average().toFloat() }
                fun center(v: FloatArray) = FloatArray(v.size) { v[it] - mean[it] }
                val qv = center(app.media.embed(com.shuddh.lab.core.MediaIndex.expand(q)))
                val bmp = android.graphics.Bitmap.createBitmap(224, 224, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }
                android.util.Log.i("ShuddhEmbed", "image embedder dims = ${app.media.embedImage(bmp).size}")
                val ranked = docs.indices.map { docs[it] to com.shuddh.lab.core.MediaIndex.cosine(qv, center(vecs[it])) }.sortedByDescending { it.second }
                android.util.Log.i("ShuddhEmbed", "[$q] ${System.currentTimeMillis() - t0} ms :: " + ranked.joinToString(" | ") { "%.2f %s".format(it.second, it.first.substringBefore(",", it.first) + "…") })
            }
        }
        // Demo verdict for checking the result screen: --es demo_result SAFE|CAUTION|UNSAFE
        intent?.getStringExtra("demo_result")?.let { lv ->
            val level = runCatching { com.shuddh.lab.core.Level.valueOf(lv) }.getOrDefault(com.shuddh.lab.core.Level.SAFE)
            val ev = listOf(
                com.shuddh.lab.core.Evidence("OBSERVATION", "Demo: nitrate band absorbance 0.12"),
                com.shuddh.lab.core.Evidence("QUALITY", "20 frames, 1 outlier rejected", true),
                com.shuddh.lab.core.Evidence("CALIBRATION", "Fresh blank within 2 minutes", true),
                com.shuddh.lab.core.Evidence("QUALITY", "Ambient light 180 lux (dim enough)", level != com.shuddh.lab.core.Level.CAUTION),
            )
            if (app.prefs.onboarded) app.show(com.shuddh.lab.core.Outcome("Shuddh Spectrum", "demo_nitrate", com.shuddh.lab.core.Txt("Nitrate in water"),
                if (level == com.shuddh.lab.core.Level.SAFE) 12.0 else 58.0, "mg/L", level, "Demo verdict", listOf(com.shuddh.lab.core.Words.ok), ev))
        }
        // Launch straight into a screen (demo shortcuts / testing): adb shell am start ... --es screen NAMI
        intent?.getStringExtra("screen")?.let { name ->
            runCatching { Screen.valueOf(name) }.getOrNull()?.let { if (app.prefs.onboarded) app.go(it) }
        }
        setContent {
            ShuddhTheme {
                LaunchedEffect(com.shuddh.lab.ui.UiPrefs.light) {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = com.shuddh.lab.ui.UiPrefs.light
                }
                Root(app)
            }
        }
    }

    override fun onDestroy() {
        app.mesh.stop()
        app.voice.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun Root(app: AppState) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(app.prefs.onboarded) {
        if (app.prefs.onboarded) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    val screen = app.screen
    BackHandler(enabled = screen != Screen.HOME && screen != Screen.ONBOARDING) { app.back() }
    LaunchedEffect(screen) { if (screen != Screen.RESULT) app.voice.stop() }

    Box(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding()) {
        Box(Modifier.fillMaxSize()) {
            androidx.compose.runtime.key(screen) { Box(Modifier.fillMaxSize().screenIn()) {
                when (screen) {
                    Screen.ONBOARDING -> OnboardingScreen(app) { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
                    Screen.HOME -> HomeScreen(app)
                    Screen.TOOLS -> com.shuddh.lab.ui.ToolsScreen(app)
                    Screen.WAX -> com.shuddh.lab.instruments.WaxScreen(app)
                    Screen.SPECTRUM -> SpectrumScreen(app)
                    Screen.POLAR -> PolarScreen(app)
                    Screen.NIR -> NirScreen(app)
                    Screen.ECHO -> EchoScreen(app)
                    Screen.NAMI -> NamiScreen(app)
                    Screen.MAGNETO -> MagnetoScreen(app)
                    Screen.MESH -> MeshScreen(app)
                    Screen.ASSISTANT -> AssistantScreen(app)
                    Screen.WHISTLE -> WhistleScreen(app)
                    Screen.LENS -> LensScreen(app)
                    Screen.MODELS -> ModelsScreen(app)
                    Screen.MEDIA -> com.shuddh.lab.ui.MediaSearchScreen(app)
                    Screen.VIDEO -> com.shuddh.lab.ui.VideoMomentScreen(app)
                    Screen.MAP -> com.shuddh.lab.ui.MapScreen(app)
                    Screen.BADGES -> BadgesScreen(app)
                    Screen.FOODGUIDE -> FoodGuideScreen(app)
                    Screen.PANTRY -> com.shuddh.lab.ui.PantryScreen(app)
                    Screen.PULSE -> com.shuddh.lab.instruments.PulseScreen(app)
                    Screen.VISION -> com.shuddh.lab.instruments.VisionScreen(app)
                    Screen.BOIL -> com.shuddh.lab.instruments.BoilScreen(app)
                    Screen.OIL -> com.shuddh.lab.instruments.OilScreen(app)
                    Screen.PURITY -> com.shuddh.lab.instruments.PurityScreen(app)
                    Screen.DART -> com.shuddh.lab.instruments.DartScreen(app)
                    Screen.GRAIN -> com.shuddh.lab.instruments.GrainScreen(app)
                    Screen.OUTBREAK -> com.shuddh.lab.ui.OutbreakScreen(app)
                    Screen.ANAEMIA -> com.shuddh.lab.instruments.AnaemiaScreen(app)
                    Screen.MOSQUITO -> com.shuddh.lab.instruments.MosquitoScreen(app)
                    Screen.GUARDIAN -> com.shuddh.lab.ui.GuardianScreen(app)
                    Screen.EXPOSURE -> com.shuddh.lab.ui.ExposureScreen(app)
                    Screen.MILKMAN -> com.shuddh.lab.ui.MilkmanScreen(app)
                    Screen.STRIP -> StripScreen(app)
                    Screen.SCATTER -> ScatterScreen(app)
                    Screen.FLOAT -> FloatScreen(app)
                    Screen.RESULT -> ResultScreen(app)
                    Screen.HISTORY -> HistoryScreen(app)
                    Screen.INSIGHTS -> InsightsScreen(app)
                    Screen.COMMUNITY -> CommunityScreen(app)
                    Screen.SETTINGS -> SettingsScreen(app)
                    Screen.GUIDE -> GuideScreen(app)
                }
            } }
        }
        if (screen in tabs) Box(Modifier.align(Alignment.BottomCenter)) { BottomBar(app, screen) }
        Box(Modifier.align(Alignment.TopCenter)) { com.shuddh.lab.ui.IncomingSosBanner(app) }
        com.shuddh.lab.ui.GuardianAlert(app)
    }
}

@Composable
private fun BottomBar(app: AppState, current: Screen) {
    // Floating glass pill — no backdrop strip behind it.
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (Palette.light) Modifier.shadow(16.dp, RoundedCornerShape(28.dp), ambientColor = Color(0x330F172A), spotColor = Color(0x330F172A)) else Modifier)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(if (Palette.light) listOf(Color(0xF7FFFFFF), Color(0xF2F8FAFD)) else listOf(Color(0xE61A2436), Color(0xF00E1522))))
                .border(1.dp, Palette.veil(0x26), RoundedCornerShape(28.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(
                Triple(Screen.HOME, "Lab", Icons.Filled.Home),
                Triple(Screen.HISTORY, "History", Icons.Filled.List),
                Triple(Screen.INSIGHTS, "Insights", Icons.Filled.DateRange),
                Triple(Screen.COMMUNITY, "Hive", Icons.Filled.Place),
                Triple(Screen.SETTINGS, "Settings", Icons.Filled.Settings),
            ).forEach { (s, label, icon) ->
                val sel = current == s
                val w by animateFloatAsState(if (sel) 1f else 0f, tween(350), label = "nav")
                Column(
                    Modifier.weight(1f + 0.4f * w).clip(RoundedCornerShape(22.dp))
                        .background(Brush.horizontalGradient(listOf(Palette.accent.copy(alpha = w), Palette.cyan.copy(alpha = w))))
                        .clickable { app.tab(s) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, label, tint = if (sel) Palette.onAccent else Palette.muted, modifier = Modifier.size(22.dp))
                    Text(com.shuddh.lab.ui.tr(label), fontSize = 11.sp, maxLines = 1, softWrap = false, color = if (sel) Palette.onAccent else Palette.muted, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
