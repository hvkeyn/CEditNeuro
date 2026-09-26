package com.hvkeyn.ceditneuro.agent

import kotlin.math.E
import kotlin.math.PI

/** A small calculator, so a study number comes from arithmetic instead of the model's guess. */
object MathEval {
    fun eval(expression: String): Double {
        val parser = Parser(expression)
        val value = parser.expression()
        parser.skip()
        if (!parser.done()) throw IllegalArgumentException("Unexpected '${parser.rest()}'.")
        if (value.isNaN()) throw IllegalArgumentException("The result is not a number.")
        return value
    }

    fun format(value: Double): String {
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
        return "%.12g".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    }

    private class Parser(val text: String) {
        var at = 0

        /** Inside function arguments a comma separates values; outside, 3,5 is a decimal comma. */
        var argDepth = 0

        fun decimalMark(index: Int): Boolean {
            val c = text.getOrNull(index) ?: return false
            if (c == '.') return true
            return c == ',' && argDepth == 0 && text.getOrNull(index + 1)?.isDigit() == true
        }

        fun done() = at >= text.length
        fun rest() = text.substring(at).take(20)

        fun skip() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun eat(char: Char): Boolean {
            skip()
            if (at < text.length && text[at] == char) {
                at++
                return true
            }
            return false
        }

        fun expression(): Double {
            var value = term()
            while (true) {
                value = when {
                    eat('+') -> value + term()
                    eat('-') -> value - term()
                    else -> return value
                }
            }
        }

        fun term(): Double {
            var value = power()
            while (true) {
                value = when {
                    eat('*') -> value * power()
                    eat('/') -> value / power()
                    eat('%') -> value % power()
                    else -> return value
                }
            }
        }

        fun power(): Double {
            val base = unary()
            return if (eat('^')) Math.pow(base, power()) else base
        }

        fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> atom()
        }

        fun atom(): Double {
            skip()
            if (eat('(')) {
                val inside = expression()
                if (!eat(')')) throw IllegalArgumentException("Missing ')'.")
                return inside
            }
            val start = at
            if (at < text.length && (text[at].isDigit() || text[at] == '.')) {
                while (at < text.length && (text[at].isDigit() || decimalMark(at))) at++
                if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                    val mark = at
                    at++
                    if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
                    if (at < text.length && text[at].isDigit()) {
                        while (at < text.length && text[at].isDigit()) at++
                    } else {
                        at = mark
                    }
                }
                val literal = text.substring(start, at).replace(',', '.')
                return literal.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Bad number '$literal'.")
            }
            while (at < text.length && (text[at].isLetter() || text[at] == '_')) at++
            val name = text.substring(start, at).lowercase()
            if (name.isEmpty()) throw IllegalArgumentException("Expected a number at '${rest()}'.")
            when (name) {
                "pi" -> return PI
                "e" -> return E
            }
            if (!eat('(')) throw IllegalArgumentException("Unknown name '$name'.")
            argDepth++
            val args = mutableListOf(expression())
            while (eat(',') || eat(';')) args += expression()
            argDepth--
            if (!eat(')')) throw IllegalArgumentException("Missing ')' after $name.")
            val x = args[0]
            return when (name) {
                "sqrt" -> Math.sqrt(x)
                "cbrt" -> Math.cbrt(x)
                "abs" -> Math.abs(x)
                "sin" -> Math.sin(x)
                "cos" -> Math.cos(x)
                "tan" -> Math.tan(x)
                "asin" -> Math.asin(x)
                "acos" -> Math.acos(x)
                "atan" -> if (args.size > 1) Math.atan2(x, args[1]) else Math.atan(x)
                "ln" -> Math.log(x)
                "log" -> if (args.size > 1) Math.log(x) / Math.log(args[1]) else Math.log10(x)
                "log2" -> Math.log(x) / Math.log(2.0)
                "exp" -> Math.exp(x)
                "floor" -> Math.floor(x)
                "ceil" -> Math.ceil(x)
                "round" -> Math.rint(x)
                "min" -> args.min()
                "max" -> args.max()
                "deg" -> Math.toDegrees(x)
                "rad" -> Math.toRadians(x)
                "sum" -> args.sum()
                "mean", "avg" -> args.average()
                "median" -> args.sorted().let { s ->
                    if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
                }
                "stdev", "sd" -> {
                    if (args.size < 2) throw IllegalArgumentException("stdev needs at least 2 values.")
                    val mean = args.average()
                    Math.sqrt(args.sumOf { (it - mean) * (it - mean) } / (args.size - 1))
                }
                "hypot" -> Math.sqrt(args.sumOf { it * it })
                "pct" -> {
                    if (args.size != 2) throw IllegalArgumentException("pct(old, new) needs two values.")
                    (args[1] - args[0]) / args[0] * 100
                }
                else -> throw IllegalArgumentException("Unknown function '$name'.")
            }
        }
    }
}
