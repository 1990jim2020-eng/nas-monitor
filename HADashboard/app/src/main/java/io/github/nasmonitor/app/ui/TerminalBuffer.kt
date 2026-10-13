package io.github.nasmonitor.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Small stateful VT100/xterm screen buffer for interactive SSH programs. */
class TerminalBuffer(columns: Int = 80, rows: Int = 24) {
    private data class Cell(var char: Char = ' ', var fg: Int = 7, var bg: Int = 0, var bold: Boolean = false)
    private var cols = columns.coerceAtLeast(20)
    private var lines = rows.coerceAtLeast(8)
    private var cells = Array(lines) { Array(cols) { Cell() } }
    private var x = 0
    private var y = 0
    private var savedX = 0
    private var savedY = 0
    private var fg = 7
    private var bg = 0
    private var bold = false
    private var state = State.TEXT
    private val csi = StringBuilder()
    private val osc = StringBuilder()

    private enum class State { TEXT, ESC, CSI, OSC, OSC_ESC }

    fun resize(columns: Int, rows: Int) {
        val newCols = columns.coerceAtLeast(20)
        val newRows = rows.coerceAtLeast(8)
        if (newCols == cols && newRows == lines) return
        val next = Array(newRows) { Array(newCols) { Cell() } }
        for (row in 0 until minOf(lines, newRows)) for (col in 0 until minOf(cols, newCols)) {
            next[row][col] = cells[row][col].copy()
        }
        cols = newCols; lines = newRows; cells = next
        x = x.coerceIn(0, cols - 1); y = y.coerceIn(0, lines - 1)
    }

    fun feed(text: String) { text.forEach(::feedChar) }

    private fun feedChar(ch: Char) {
        when (state) {
            State.TEXT -> when (ch) {
                '\u001B' -> state = State.ESC
                '\r' -> x = 0
                '\n' -> lineFeed()
                '\b' -> x = (x - 1).coerceAtLeast(0)
                '\t' -> repeat(8 - (x % 8)) { put(' ') }
                else -> if (ch >= ' ') put(ch)
            }
            State.ESC -> when (ch) {
                '[' -> { csi.clear(); state = State.CSI }
                ']' -> { osc.clear(); state = State.OSC }
                '7' -> { savedX = x; savedY = y; state = State.TEXT }
                '8' -> { x = savedX; y = savedY; state = State.TEXT }
                'D' -> { lineFeed(); state = State.TEXT }
                'M' -> { scrollDown(); state = State.TEXT }
                'c' -> { reset(); state = State.TEXT }
                else -> state = State.TEXT
            }
            State.CSI -> {
                if (ch in '@'..'~') { executeCsi(ch, csi.toString()); state = State.TEXT }
                else csi.append(ch)
            }
            State.OSC -> when (ch) {
                '\u0007' -> state = State.TEXT
                '\u001B' -> state = State.OSC_ESC
                else -> osc.append(ch)
            }
            State.OSC_ESC -> { state = if (ch == '\\') State.TEXT else State.OSC }
        }
    }

    private fun params(raw: String): List<Int> = raw.trimStart('?', '>', '!').split(';').map { it.toIntOrNull() ?: 0 }
    private fun n(p: List<Int>, index: Int = 0, fallback: Int = 1) = p.getOrNull(index)?.takeIf { it != 0 } ?: fallback

    private fun executeCsi(command: Char, raw: String) {
        val p = params(raw)
        when (command) {
            'A' -> y = (y - n(p)).coerceAtLeast(0)
            'B', 'e' -> y = (y + n(p)).coerceAtMost(lines - 1)
            'C', 'a' -> x = (x + n(p)).coerceAtMost(cols - 1)
            'D' -> x = (x - n(p)).coerceAtLeast(0)
            'E' -> { y = (y + n(p)).coerceAtMost(lines - 1); x = 0 }
            'F' -> { y = (y - n(p)).coerceAtLeast(0); x = 0 }
            'G', '`' -> x = (n(p) - 1).coerceIn(0, cols - 1)
            'd' -> y = (n(p) - 1).coerceIn(0, lines - 1)
            'H', 'f' -> { y = (n(p, 0) - 1).coerceIn(0, lines - 1); x = (n(p, 1) - 1).coerceIn(0, cols - 1) }
            'J' -> eraseDisplay(p.firstOrNull() ?: 0)
            'K' -> eraseLine(p.firstOrNull() ?: 0)
            'm' -> applySgr(if (p.isEmpty()) listOf(0) else p)
            'S' -> repeat(n(p)) { scrollUp() }
            'T' -> repeat(n(p)) { scrollDown() }
            's' -> { savedX = x; savedY = y }
            'u' -> { x = savedX; y = savedY }
            'h', 'l' -> Unit // private terminal modes, including bracketed paste
        }
    }

    private fun applySgr(p: List<Int>) {
        var i = 0
        while (i < p.size) {
            when (val code = p[i]) {
                0 -> { fg = 7; bg = 0; bold = false }
                1 -> bold = true
                22 -> bold = false
                39 -> fg = 7
                49 -> bg = 0
                in 30..37 -> fg = code - 30
                in 40..47 -> bg = code - 40
                in 90..97 -> fg = code - 90 + 8
                in 100..107 -> bg = code - 100 + 8
                38, 48 -> if (p.getOrNull(i + 1) == 5 && p.getOrNull(i + 2) != null) {
                    if (code == 38) fg = p[i + 2] else bg = p[i + 2]
                    i += 2
                }
            }
            i++
        }
    }

    private fun put(ch: Char) {
        cells[y][x] = Cell(ch, fg, bg, bold)
        x++
        if (x >= cols) { x = 0; lineFeed() }
    }
    private fun lineFeed() { if (y == lines - 1) scrollUp() else y++ }
    private fun scrollUp() { for (row in 0 until lines - 1) cells[row] = cells[row + 1]; cells[lines - 1] = Array(cols) { Cell() } }
    private fun scrollDown() { for (row in lines - 1 downTo 1) cells[row] = cells[row - 1]; cells[0] = Array(cols) { Cell() } }
    private fun eraseDisplay(mode: Int) { when (mode) { 2, 3 -> cells = Array(lines) { Array(cols) { Cell() } }; 0 -> { eraseLine(0); for (row in y + 1 until lines) clearRow(row) }; 1 -> { for (row in 0 until y) clearRow(row); eraseLine(1) } } }
    private fun eraseLine(mode: Int) { when (mode) { 0 -> for (col in x until cols) cells[y][col] = Cell(); 1 -> for (col in 0..x) cells[y][col] = Cell(); 2 -> clearRow(y) } }
    private fun clearRow(row: Int) { for (col in 0 until cols) cells[row][col] = Cell() }
    private fun reset() { cells = Array(lines) { Array(cols) { Cell() } }; x = 0; y = 0; fg = 7; bg = 0; bold = false }

    fun render(): List<AnnotatedString> = cells.map { row ->
        buildAnnotatedString {
            var start = 0
            while (start < cols) {
                val cell = row[start]; var end = start + 1
                while (end < cols && row[end].fg == cell.fg && row[end].bg == cell.bg && row[end].bold == cell.bold) end++
                pushStyle(SpanStyle(color = color(cell.fg), background = color(cell.bg), fontWeight = if (cell.bold) androidx.compose.ui.text.font.FontWeight.Bold else null))
                append(row.sliceArray(start until end).joinToString("") { it.char.toString() })
                pop(); start = end
            }
        }
    }

    private fun color(index: Int): Color {
        val palette = arrayOf(0xFF151A22, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFE5EAF2, 0xFF5C6370, 0xFFFF7B86, 0xFFB6E3A8, 0xFFFFD580, 0xFF8CC8FF, 0xFFE2A8FF, 0xFF83E0E5, 0xFFFFFFFF)
        return Color(palette[index.coerceIn(0, 15)])
    }
}
