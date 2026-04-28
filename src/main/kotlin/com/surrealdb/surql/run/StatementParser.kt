package com.surrealdb.surql.run

/**
 * A single SurrealQL statement extracted from a document.
 *
 * [startOffset] and [endOffset] are character offsets into the original document text
 * (endOffset is exclusive — one past the last character of the statement including the `;`).
 * IntelliJ's [com.intellij.openapi.util.TextRange] uses the same exclusive-end convention.
 */
data class StatementRange(
    val startOffset: Int,
    val endOffset: Int,
    val queryText: String,
)

/**
 * Splits [text] into individual SurrealQL statements by locating `;` delimiters at depth 0
 * (i.e. not inside `()`, `[]`, `{}`, string literals, or comments).
 *
 * Handles:
 *  - Single-quoted, double-quoted, and backtick-quoted strings with backslash escapes
 *  - `--` and `#` single-line comments
 *  - `/* ... */` block comments
 *  - Nested bracket depth
 *
 * A trailing statement without a terminating `;` is also returned.
 */
fun findStatements(text: String): List<StatementRange> {
    val results = mutableListOf<StatementRange>()
    var depth = 0
    var inString: Char? = null
    var inLineComment = false
    var inBlockComment = false
    var statementStart = skipWhitespace(text, 0)
    var i = 0

    while (i < text.length) {
        val ch = text[i]
        val next = if (i + 1 < text.length) text[i + 1] else '\u0000'

        when {
            inString != null -> {
                // Backslash escapes inside single- and double-quoted strings (not backtick).
                if (ch == '\\' && inString != '`') i++
                else if (ch == inString) inString = null
            }
            inLineComment -> {
                if (ch == '\n') inLineComment = false
            }
            inBlockComment -> {
                if (ch == '*' && next == '/') {
                    inBlockComment = false
                    i++ // consume '/'
                }
            }
            ch == '-' && next == '-' -> {
                inLineComment = true
                i++ // consume second '-'
            }
            ch == '#' -> inLineComment = true
            ch == '/' && next == '*' -> {
                inBlockComment = true
                i++ // consume '*'
            }
            ch == '\'' || ch == '"' || ch == '`' -> inString = ch
            ch == '(' || ch == '[' || ch == '{' -> depth++
            ch == ')' || ch == ']' || ch == '}' -> if (depth > 0) depth--
            ch == ';' && depth == 0 -> {
                val endOffset = i + 1
                val stmtText = text.substring(statementStart, endOffset).trim()
                if (stmtText.isNotBlank() && stmtText != ";") {
                    results.add(StatementRange(statementStart, endOffset, stmtText))
                }
                statementStart = skipWhitespace(text, endOffset)
            }
        }
        i++
    }

    // Include any trailing statement that has no terminating semicolon.
    if (statementStart < text.length) {
        val trailing = text.substring(statementStart).trim()
        if (trailing.isNotBlank()) {
            results.add(StatementRange(statementStart, text.length, trailing))
        }
    }

    return results
}

private fun skipWhitespace(text: String, from: Int): Int {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    return i
}

// ---------------------------------------------------------------------------
// USE NS / USE DB extraction
// ---------------------------------------------------------------------------

/**
 * Scans [text] for `USE NS <value>` / `USE NAMESPACE <value>` and
 * `USE DB <value>` / `USE DATABASE <value>` directives (case-insensitive).
 *
 * Returns the **last** occurrence of each so that a file which re-selects
 * the namespace or database mid-way still produces a consistent value.
 * The returned strings have surrounding quotes/backticks stripped.
 *
 * This is used by the run action to supply `NS` / `DB` HTTP headers when
 * executing a single statement that may appear after file-level USE directives.
 */
fun extractUseDirectives(text: String): Pair<String?, String?> {
    // Matches: USE NS `foo`, USE NS "foo", USE NS 'foo', USE NS foo
    val nsRegex = Regex(
        """(?i)\bUSE\s+(?:NS|NAMESPACE)\s+(`[^`]+`|"[^"]+"|'[^']+'|[\w\-]+)""",
    )
    val dbRegex = Regex(
        """(?i)\bUSE\s+(?:DB|DATABASE)\s+(`[^`]+`|"[^"]+"|'[^']+'|[\w\-]+)""",
    )

    val ns = nsRegex.findAll(text).lastOrNull()
        ?.groupValues?.get(1)
        ?.trim('`', '"', '\'')
        ?.takeIf { it.isNotBlank() }

    val db = dbRegex.findAll(text).lastOrNull()
        ?.groupValues?.get(1)
        ?.trim('`', '"', '\'')
        ?.takeIf { it.isNotBlank() }

    return ns to db
}
