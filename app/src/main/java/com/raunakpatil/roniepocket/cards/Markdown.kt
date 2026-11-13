package com.raunakpatil.roniepocket.cards

/**
 * A tiny, streaming-friendly markdown parser for Ronie's replies. It turns the model's natural
 * structure into blocks the UI renders as cards (Ronie Cards v0). It's deliberately forgiving: the
 * text may be mid-stream, so an unfinished last line is still parsed sensibly.
 */
sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val text: String, val level: Int) : Block
    data class Bullets(val items: List<String>) : Block
    data class Steps(val items: List<String>) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data class Code(val text: String) : Block
    data class Expense(val json: String) : Block
}

object Markdown {
    private val bullet = Regex("""^\s*[-*•]\s+(.*)$""")
    private val numbered = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
    private val heading = Regex("""^\s*(#{1,4})\s+(.*)$""")
    private val tableRow = Regex("""^\s*\|(.*)\|?\s*$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = text.replace("\r", "").split('\n')
        var i = 0
        val para = StringBuilder()
        fun flushPara() {
            if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flushPara()
                    val tag = line.trimStart().removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    if (tag.equals("expense", ignoreCase = true)) {
                        out += Block.Expense(code.toString().trim())
                    } else {
                        out += Block.Code(code.toString().trimEnd())
                    }
                }
                heading.matches(line) -> {
                    flushPara()
                    val m = heading.find(line)!!
                    out += Block.Heading(m.groupValues[2].trim(), m.groupValues[1].length)
                }
                tableRow.matches(line) && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flushPara()
                    val header = cells(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && tableRow.matches(lines[i])) { rows += cells(lines[i]); i++ }
                    out += Block.Table(header, rows)
                    continue
                }
                bullet.matches(line) -> {
                    flushPara()
                    val items = mutableListOf<String>()
                    while (i < lines.size && (bullet.matches(lines[i]) || continuation(lines, i, items))) {
                        val m = bullet.find(lines[i])
                        if (m != null) items += m.groupValues[1].trim() else items[items.lastIndex] += " " + lines[i].trim()
                        i++
                    }
                    out += Block.Bullets(items)
                    continue
                }
                numbered.matches(line) -> {
                    flushPara()
                    val items = mutableListOf<String>()
                    while (i < lines.size && (numbered.matches(lines[i]) || continuation(lines, i, items))) {
                        val m = numbered.find(lines[i])
                        if (m != null) items += m.groupValues[2].trim() else items[items.lastIndex] += " " + lines[i].trim()
                        i++
                    }
                    out += Block.Steps(items)
                    continue
                }
                line.isBlank() -> flushPara()
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
            }
            i++
        }
        flushPara()
        return out
    }

    /** An indented line right after a list item belongs to that item. */
    private fun continuation(lines: List<String>, i: Int, items: List<String>) =
        items.isNotEmpty() && lines[i].startsWith("  ") && lines[i].isNotBlank()

    private fun cells(line: String) = line.trim().trim('|').split('|').map { it.trim() }
}
