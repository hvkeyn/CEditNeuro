package com.hvkeyn.ceditneuro.tools

/**
 * A small line diff used for the edit previews in the chat panel. It is LCS-based, so it
 * stays accurate for normal source edits; texts beyond [MAX_LCS_LINES] fall back to a
 * summary rather than allocating a huge table on a phone.
 */
object TextDiff {

    private const val MAX_LCS_LINES = 800

    fun unified(before: String, after: String, maxOutputLines: Int = 300): String {
        if (before == after) return ""

        val a = before.lines()
        val b = after.lines()
        if (a.size > MAX_LCS_LINES || b.size > MAX_LCS_LINES) {
            return "(large change: ${a.size} -> ${b.size} lines)"
        }

        val table = lcsTable(a, b)
        val ops = ArrayList<Pair<Char, String>>()
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] -> {
                    ops.add(' ' to a[i - 1]); i--; j--
                }
                j > 0 && (i == 0 || table[i][j - 1] >= table[i - 1][j]) -> {
                    ops.add('+' to b[j - 1]); j--
                }
                else -> {
                    ops.add('-' to a[i - 1]); i--
                }
            }
        }
        ops.reverse()

        val rendered = StringBuilder()
        var emitted = 0
        for ((sign, line) in ops) {
            if (emitted >= maxOutputLines) {
                rendered.append("... (diff truncated)\n")
                break
            }
            rendered.append(sign).append(line).append('\n')
            emitted++
        }
        return rendered.toString()
    }

    private fun lcsTable(a: List<String>, b: List<String>): Array<IntArray> {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) {
            for (j in b.indices) {
                table[i + 1][j + 1] = if (a[i] == b[j]) {
                    table[i][j] + 1
                } else {
                    maxOf(table[i][j + 1], table[i + 1][j])
                }
            }
        }
        return table
    }
}
