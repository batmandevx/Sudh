package com.shuddh.lab.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class GuideItem(
    val food: String, val emoji: String, val category: String, val adulterant: String, val harm: String,
    val steps: List<String>, val result: String, val instrument: Screen?, val instrumentLabel: String = "",
)

/** Home screening tests, adapted from FSSAI's "Detect Adulteration with Rapid Test" (DART) guidance. */
private val guide = listOf(
    GuideItem("Milk", "🥛", "Dairy", "Added water", "Lowers nutrition; dirty water can carry germs.",
        listOf("Put a drop of milk on a polished, slanting surface.", "Watch how it flows."),
        "Pure milk flows slowly and leaves a white trail. Watered milk runs fast with almost no trail.", Screen.FLOAT, "Float (lactometer)"),
    GuideItem("Milk", "🥛", "Dairy", "Detergent", "Stomach upset; long-term gut damage.",
        listOf("Shake 5 ml milk with 5 ml water in a bottle.", "Look at the foam after a minute."),
        "A thick, lasting lather suggests detergent. Pure milk gives only a thin froth.", Screen.SPECTRUM, "Spectrum (detergent test)"),
    GuideItem("Milk", "🥛", "Dairy", "Starch", "Used to fake thickness after adding water.",
        listOf("Boil 2–3 ml milk and let it cool.", "Add 2 drops of iodine tincture."),
        "Blue colour means starch was added.", Screen.SPECTRUM, "Spectrum (starch test)"),
    GuideItem("Ghee & butter", "🧈", "Dairy", "Mashed potato / starch", "Cheap filler sold at ghee prices.",
        listOf("Take half a spoon of ghee in a bowl.", "Add 2–3 drops of iodine tincture."),
        "Blue colour indicates starch.", null),
    GuideItem("Honey", "🍯", "Sweets", "Sugar syrup", "Blood-sugar spikes; you pay for fake honey.",
        listOf("Drop a little honey into a glass of water.", "Watch whether it stays together."),
        "Rough hint only: pure honey tends to sink as a lump; syrup dissolves quickly. Confirm with Polar.", Screen.POLAR, "Polar (optical rotation)"),
    GuideItem("Sugar", "🍬", "Sweets", "Chalk powder", "Gritty; digestive trouble.",
        listOf("Dissolve a spoon of sugar in a glass of water.", "Let it stand for a few minutes."),
        "White sediment at the bottom suggests chalk.", null),
    GuideItem("Silver foil (varq)", "✨", "Sweets", "Aluminium foil", "Aluminium is not meant to be eaten.",
        listOf("Take a small piece of the foil.", "Burn it carefully with a match."),
        "Real silver curls into a small shiny ball. Aluminium leaves dark grey ash.", null),
    GuideItem("Turmeric powder", "🟡", "Spices", "Artificial yellow colour", "Some dyes (e.g. metanil yellow) are toxic.",
        listOf("Add a teaspoon of turmeric to a glass of water.", "Do not stir; watch it settle."),
        "Natural turmeric leaves light yellow water as it settles. Strong yellow water suggests added colour.", Screen.SPECTRUM, "Spectrum"),
    GuideItem("Chilli powder", "🌶️", "Spices", "Brick powder / sand", "Grit and heavy metals.",
        listOf("Stir a spoon of chilli powder into a glass of water.", "Rub the sediment between your fingers."),
        "A gritty, sandy feel indicates brick powder or sand.", null),
    GuideItem("Black pepper", "⚫", "Spices", "Papaya seeds", "You pay pepper prices for seeds.",
        listOf("Put a few peppercorns into a glass of water or rubbing alcohol."),
        "Papaya seeds tend to float; mature pepper sinks.", null),
    GuideItem("Tea leaves", "🍵", "Beverages", "Added colour / used leaves", "Synthetic dyes; stale leaves.",
        listOf("Sprinkle some tea on a wet white tissue or filter paper.", "Wait a minute."),
        "Fast yellow, orange or red spots mean added colour.", null),
    GuideItem("Coffee powder", "☕", "Beverages", "Chicory", "Not harmful, but you pay more for less coffee.",
        listOf("Sprinkle coffee powder on the surface of a glass of water."),
        "Coffee floats; chicory sinks within seconds and leaves coloured streaks.", null),
    GuideItem("Green vegetables", "🫛", "Produce", "Malachite green dye", "A dye linked to cancer, used to make vegetables look fresh.",
        listOf("Rub the vegetable with wet white cotton."),
        "Green colour on the cotton indicates dye.", Screen.LENS, "Label Lens"),
    GuideItem("Salt", "🧂", "Grains & staples", "Missing iodine", "Iodine deficiency causes goitre and affects children's development.",
        listOf("Cut a potato in half.", "Rub salt on the cut face and add 2 drops of lemon juice."),
        "Iodised salt turns the potato blue within a minute; no blue means no iodine.", null),
    GuideItem("Drinking water", "💧", "Water", "Germs, chlorine, nitrate, turbidity", "Diarrhoea, blue-baby syndrome, long-term illness.",
        listOf("Look for cloudiness in a clear glass against light.", "Smell for strong chlorine."),
        "Use Shuddh Spectrum or Strips for chlorine, nitrate and arsenic; Hawa for cloudiness.", Screen.SPECTRUM, "Spectrum / Strips"),
    GuideItem("Steel utensils", "🍳", "Kitchen", "Non-food-grade steel", "Cheap steel can rust and leach into acidic food.",
        listOf("Bring a fridge magnet close to the utensil."),
        "Food-grade 304/316 steel is not magnetic. Strong attraction suggests cheap steel or iron.", Screen.MAGNETO, "Magneto"),
)

@Composable
fun FoodGuideScreen(app: AppState) {
    var q by remember { mutableStateOf("") }
    val cats = listOf("All") + guide.map { it.category }.distinct()
    var cat by remember { mutableStateOf("All") }
    var open by remember { mutableStateOf<GuideItem?>(null) }
    val shown = guide.filter { (cat == "All" || it.category == cat) && (q.isBlank() || "${it.food} ${it.adulterant}".contains(q.trim(), true)) }
    ScreenFrame("Adulteration Guide", "${guide.size} home tests · adapted from FSSAI DART", onBack = { app.back() }) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Palette.glass).border(1.dp, Palette.line, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔎", fontSize = 15.sp)
            BasicTextField(q, { q = it }, singleLine = true, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                textStyle = TextStyle(color = Palette.text, fontSize = 15.sp, fontFamily = Body), cursorBrush = SolidColor(Palette.cyan),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search a food… milk, turmeric, honey", color = Palette.muted, fontSize = 15.sp); inner() } })
        }
        Chips(cats, cat, { it }) { cat = it }
        shown.forEachIndexed { i, g ->
            val isOpen = open == g
            val arrow by animateFloatAsState(if (isOpen) 90f else 0f, label = "arrow")
            Column(
                Modifier.fillMaxWidth().enter(i.coerceAtMost(6)).clip(RoundedCornerShape(20.dp))
                    .background(Brush.horizontalGradient(listOf(if (isOpen) Palette.accent.copy(alpha = 0.10f) else Color(0x12FFFFFF), Color(0x0AFFFFFF))))
                    .border(1.dp, if (isOpen) Palette.accent.copy(alpha = 0.4f) else Palette.line, RoundedCornerShape(20.dp))
                    .clickable { open = if (isOpen) null else g }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Palette.glass), contentAlignment = Alignment.Center) { Text(g.emoji, fontSize = 22.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.food, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Check for: ${g.adulterant}", color = Palette.amber, fontSize = 12.sp)
                    }
                    Text("›", color = Palette.muted, fontSize = 22.sp, modifier = Modifier.rotate(arrow))
                }
                AnimatedVisibility(isOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Why it matters: ${g.harm}", color = Palette.muted, fontSize = 13.sp)
                        g.steps.forEachIndexed { k, st ->
                            Row {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(Palette.accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                    Text("${k + 1}", color = Palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(st, color = Palette.text, fontSize = 13.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.cyan.copy(alpha = 0.10f)).padding(10.dp)) {
                            Text("👁  ${g.result}", color = Palette.text, fontSize = 13.sp)
                        }
                        g.instrument?.let { s -> Btn("Measure with ${g.instrumentLabel}", Modifier.fillMaxWidth()) { app.go(s) } }
                    }
                }
            }
        }
        Note("Home tests are quick screening hints, not lab proof. For complaints, use a Shuddh verdict PDF and an accredited lab.")
    }
}
