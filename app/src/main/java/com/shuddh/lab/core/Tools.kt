package com.shuddh.lab.core

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Exact arithmetic for the agent — small LLMs are unreliable at maths, so numbers are computed
 * here and the model only phrases the answer. Supports + − × ÷ ^ %, parentheses, sqrt, and
 * spoken forms ("15 percent of 240", "12 times 8", "half of 30").
 */
object Calc {
    fun normalise(q: String): String {
        var s = q.lowercase()
            .replace(Regex("""(what is|what's|calculate|compute|solve|how much is|kitna hai|equals?|=|\?)"""), " ")
            .replace("×", "*").replace(Regex("""(?<=\d)\s*x\s*(?=\d)"""), " * ").replace("÷", "/").replace("−", "-")
            .replace(Regex("""\bmultiplied by\b|\btimes\b|\binto\b"""), " * ")
            .replace(Regex("""\bdivided by\b|\bover\b"""), " / ")
            .replace(Regex("""\bplus\b|\band\b"""), " + ")
            .replace(Regex("""\bminus\b"""), " - ")
            .replace(Regex("""\bsquared\b"""), " ^ 2 ").replace(Regex("""\bcubed\b"""), " ^ 3 ")
            .replace(Regex("""\bto the power of\b|\bpower\b"""), " ^ ")
            .replace(Regex("""\bsquare root of\b|\bsqrt\b"""), " sqrt ")
        // "15 percent of 240" / "15% of 240" → (15/100*240); "half of 30" → (0.5*30)
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s*(?:%|percent|per cent)\s*of\s*"""), "($1/100) * ")
        s = s.replace(Regex("""\bhalf of\b"""), "0.5 * ").replace(Regex("""\bquarter of\b"""), "0.25 * ")
        s = s.replace(Regex("""(\d)\s*%"""), "$1/100")
        return s.replace(Regex("""[^0-9.+\-*/^()a-z ]"""), " ").replace(Regex("""\b(?!sqrt\b)[a-z]+\b"""), " ").trim()
    }

    /** True if the text is basically an arithmetic question. */
    fun looksLikeMath(q: String): Boolean {
        val n = normalise(q)
        return Regex("""\d""").containsMatchIn(n) && Regex("""[+\-*/^]|sqrt""").containsMatchIn(n) && eval(q) != null
    }

    fun eval(q: String): Double? = runCatching {
        val p = Parser(normalise(q).replace(" ", ""))
        val v = p.expr()
        if (p.i != p.s.length) null else v.takeIf { it.isFinite() }
    }.getOrNull()

    fun pretty(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.6f".format(v).trimEnd('0').trimEnd('.')

    private class Parser(val s: String) {
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        fun expr(): Double { var v = term(); while (peek() == '+' || peek() == '-') { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }; return v }
        fun term(): Double { var v = power(); while (peek() == '*' || peek() == '/') { val op = s[i++]; val r = power(); v = if (op == '*') v * r else v / r }; return v }
        fun power(): Double { val b = unary(); return if (peek() == '^') { i++; b.pow(power()) } else b }
        fun unary(): Double = when {
            peek() == '-' -> { i++; -unary() }
            peek() == '+' -> { i++; unary() }
            s.startsWith("sqrt", i) -> { i += 4; sqrt(unary()) }
            else -> atom()
        }
        fun atom(): Double {
            if (peek() == '(') { i++; val v = expr(); require(peek() == ')'); i++; return v }
            val st = i
            while (peek().isDigit() || peek() == '.') i++
            require(i > st)
            return s.substring(st, i).toDouble()
        }
    }
}

/** Kitchen and everyday unit conversion — exact factors, no model involved. */
object Units {
    private data class U(val names: List<String>, val dim: String, val factor: Double)

    private val units = listOf(
        U(listOf("kg", "kilogram", "kilograms", "kilo", "kilos"), "mass", 1000.0),
        U(listOf("g", "gram", "grams", "gm", "gms"), "mass", 1.0),
        U(listOf("mg", "milligram", "milligrams"), "mass", 0.001),
        U(listOf("lb", "lbs", "pound", "pounds"), "mass", 453.592),
        U(listOf("oz", "ounce", "ounces"), "mass", 28.3495),
        U(listOf("l", "litre", "liter", "litres", "liters"), "vol", 1000.0),
        U(listOf("ml", "millilitre", "milliliter", "millilitres", "milliliters"), "vol", 1.0),
        U(listOf("cup", "cups"), "vol", 240.0),
        U(listOf("tbsp", "tablespoon", "tablespoons"), "vol", 15.0),
        U(listOf("tsp", "teaspoon", "teaspoons"), "vol", 5.0),
        U(listOf("gallon", "gallons"), "vol", 3785.41),
        U(listOf("km", "kilometre", "kilometer", "kilometres", "kilometers"), "len", 1000.0),
        U(listOf("m", "metre", "meter", "metres", "meters"), "len", 1.0),
        U(listOf("cm", "centimetre", "centimeter", "centimetres", "centimeters"), "len", 0.01),
        U(listOf("mm", "millimetre", "millimeter"), "len", 0.001),
        U(listOf("mile", "miles", "mi"), "len", 1609.34),
        U(listOf("ft", "foot", "feet"), "len", 0.3048),
        U(listOf("inch", "inches", "in"), "len", 0.0254),
    )

    private fun find(w: String) = units.firstOrNull { w in it.names }

    /** "convert 2 cups to ml", "350 f in c", "5 km to miles" → answer sentence, or null. */
    fun convert(q: String): String? {
        val t = q.lowercase().replace("°", " ").replace("degrees", " ").replace("degree", " ")
        val m = Regex("""(-?\d+(?:\.\d+)?)\s*([a-z]+)\s+(?:to|in|into)\s+([a-z]+)""").find(t) ?: return null
        val v = m.groupValues[1].toDouble(); val a = m.groupValues[2]; val b = m.groupValues[3]
        fun temp(x: String) = when (x) { "c", "celsius", "centigrade" -> "C"; "f", "fahrenheit" -> "F"; "k", "kelvin" -> "K"; else -> null }
        val ta = temp(a); val tb = temp(b)
        if (ta != null && tb != null) {
            val c = when (ta) { "C" -> v; "F" -> (v - 32) * 5 / 9; else -> v - 273.15 }
            val out = when (tb) { "C" -> c; "F" -> c * 9 / 5 + 32; else -> c + 273.15 }
            return "${Calc.pretty(v)} °$ta = ${Calc.pretty(Math.round(out * 10) / 10.0)} °$tb"
        }
        val ua = find(a) ?: return null; val ub = find(b) ?: return null
        if (ua.dim != ub.dim) return "Can't convert ${ua.names[0]} (${ua.dim}) to ${ub.names[0]} (${ub.dim}) without the food's density."
        val out = v * ua.factor / ub.factor
        val r = if (kotlin.math.abs(out) >= 100) Math.round(out * 10) / 10.0 else Math.round(out * 1000) / 1000.0
        return "${Calc.pretty(v)} ${ua.names[0]} = ${Calc.pretty(r)} ${ub.names[0]}"
    }
}

/**
 * Splits a compound request into ordered sub-tasks: "find a dal recipe and then set a timer for
 * 20 minutes" → two steps the agent runs one after the other.
 */
object Planner {
    private val verbs = "set|open|turn|switch|send|make|remind|find|start|add|call|dial|convert|calculate|tell|show|search|download|count|create|schedule|note|write|launch|give|play|translate"

    fun split(q: String): List<String> {
        val parts = q.split(Regex("""\s*(?:,?\s*and then\s+|,?\s*then\s+|;\s*|\s+and also\s+|,?\s+and\s+(?=(?:$verbs)\b)|,?\s+after that\s+)""", RegexOption.IGNORE_CASE))
            .map { it.trim().trim(',', '.') }.filter { it.length > 2 }
        return if (parts.size in 2..4) parts else listOf(q)
    }
}
