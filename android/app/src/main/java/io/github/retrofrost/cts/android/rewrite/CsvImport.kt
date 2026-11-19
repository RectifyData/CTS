package io.github.retrofrost.cts.android.rewrite

import io.github.retrofrost.cts.android.model.CtsCard
import java.util.Locale

/** RFC-style quoted fields, CRLF/LF, embedded newlines, UTF-8 BOM and TSV. */
internal object CsvImport {
    private const val MAX_ROWS = 10_001
    private const val MAX_COLUMNS = 128
    private const val MAX_CHARS = 8 * 1024 * 1024

    fun cards(input: String): List<CtsCard> {
        val text = input.removePrefix("\uFEFF")
        require(text.length <= MAX_CHARS) { "CSV exceeds 8 MiB" }
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = if ('\t' in firstLine && ',' !in firstLine) '\t' else ','
        val rows = parse(text, delimiter).filter { row -> row.any(String::isNotBlank) }
        require(rows.size >= 2) { "Include a header and at least one data row" }
        val headers = rows.first().map(::key)
        fun column(vararg aliases: String): Int =
            aliases.firstNotNullOfOrNull { candidate -> headers.indexOf(key(candidate)).takeIf { it >= 0 } } ?: -1
        val title = column("title", "name", "card title", "item")
        val value = column("badge value", "value", "highlight", "number", "badge primary")
        val label = column("badge header", "badge label", "label", "badge secondary")
        val unit = column("badge unit", "unit")
        val image = column("image", "artwork", "image url", "web url", "url", "artwork url", "web image url")
        val description = column("description", "desc", "details")
        require(listOf(title, value, label, image, description).any { it >= 0 }) {
            "No recognised columns. Try Title, Badge Value, Badge Header, Description and Image."
        }
        fun List<String>.cell(index: Int) = getOrNull(index).orEmpty().trim()
        return rows.drop(1).map { row ->
            val v = row.cell(value)
            val u = row.cell(unit)
            val card = CtsCard(
                title = row.cell(title),
                badgePrimary = listOf(v, u).filter(String::isNotBlank).joinToString(" "),
                badgeSecondary = row.cell(label),
                description = row.cell(description),
            )
            card.copy(imageSubcard = card.imageSubcard.copy(source = row.cell(image).ifBlank { null }))
        }
    }

    private fun key(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    internal fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        fun finishField() {
            require(row.size < MAX_COLUMNS) { "Too many CSV columns" }
            row.add(field.toString())
            field.setLength(0)
            closed = false
        }
        fun finishRow() {
            finishField()
            require(rows.size < MAX_ROWS) { "Too many CSV rows (maximum 10,000 cards)" }
            rows.add(row.toList())
            row.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') { field.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else field.append(c)
                continue
            }
            when (c) {
                delimiter -> finishField()
                '\n' -> finishRow()
                '\r' -> {
                    if (i < text.length && text[i] == '\n') i++
                    finishRow()
                }
                '"' -> {
                    require(field.isEmpty() && !closed) { "Unexpected quote in CSV field" }
                    quoted = true
                }
                else -> {
                    if (closed) require(c == ' ') { "Unexpected character after a quoted CSV field" }
                    else field.append(c)
                }
            }
        }
        require(!quoted) { "Unterminated quoted CSV field" }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
        return rows
    }
}
