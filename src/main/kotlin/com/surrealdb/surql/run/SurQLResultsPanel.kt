package com.surrealdb.surql.run

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private const val MAX_HISTORY = 50

/**
 * Panel displayed inside the "SurrealQL Results" tool window.
 *
 * Layout: a horizontal [JBSplitter] with a query history list on the left (~28 %) and a
 * result view on the right. The result view shows:
 *   - A [JBTable] when the server returned an array of records.
 *   - A [JBTextArea] with the raw JSON or error message otherwise.
 *
 * [addResult] must be called on the EDT.
 */
class SurQLResultsPanel : JPanel(BorderLayout()) {

    private data class HistoryEntry(
        val result: QueryResult,
        val timestamp: String = LocalTime.now().format(TIME_FMT),
    )

    private val historyModel = DefaultListModel<HistoryEntry>()
    private val historyList = JBList(historyModel)
    private val resultPanel = JPanel(BorderLayout())
    private var currentRawJson: String = ""

    init {
        val splitter = JBSplitter(false, 0.28f)

        // --- History list ---
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.cellRenderer = object : ColoredListCellRenderer<HistoryEntry>() {
            override fun customizeCellRenderer(
                list: JList<out HistoryEntry>,
                value: HistoryEntry?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                value ?: return
                val firstLine = value.result.query.lineSequence().firstOrNull()?.trim() ?: ""
                val label = if (firstLine.length > 46) "${firstLine.take(43)}…" else firstLine
                append(label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${value.timestamp}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        historyList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val entry = historyList.selectedValue ?: return@addListSelectionListener
                showEntry(entry)
            }
        }

        splitter.firstComponent = JBScrollPane(historyList)
        splitter.secondComponent = resultPanel

        // --- Toolbar ---
        val toolbar = JToolBar().apply {
            isFloatable = false
            add(JButton("Copy as JSON").apply {
                toolTipText = "Copy the result JSON to the clipboard"
                addActionListener {
                    if (currentRawJson.isNotBlank()) {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(currentRawJson), null)
                    }
                }
            })
            addSeparator()
            add(JButton("Clear").apply {
                toolTipText = "Clear query history"
                addActionListener {
                    historyModel.clear()
                    resultPanel.removeAll()
                    currentRawJson = ""
                    showPlaceholder()
                }
            })
        }

        add(toolbar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        showPlaceholder()
    }

    /** Adds [result] to the history and immediately shows it. Must be called on the EDT. */
    fun addResult(result: QueryResult) {
        val entry = HistoryEntry(result)
        historyModel.insertElementAt(entry, 0)
        while (historyModel.size > MAX_HISTORY) {
            historyModel.removeElementAt(historyModel.size - 1)
        }
        historyList.selectedIndex = 0
        showEntry(entry)
    }

    // --- Private helpers ---

    private fun showPlaceholder() {
        resultPanel.removeAll()
        resultPanel.add(
            JBLabel("Run a query using the ▶ Run button in the editor.", JBLabel.CENTER),
            BorderLayout.CENTER,
        )
        resultPanel.revalidate()
        resultPanel.repaint()
    }

    private fun showEntry(entry: HistoryEntry) {
        resultPanel.removeAll()
        val result = entry.result
        currentRawJson = result.rawJson

        // Status bar
        val statusText = buildString {
            append(result.status)
            if (result.time.isNotBlank()) append("  ·  ${result.time}")
            if (result.rows.isNotEmpty()) append("  ·  ${result.rows.size} row(s)")
        }
        val statusLabel = JBLabel(statusText)
        statusLabel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        if (result.status == "ERR") statusLabel.foreground = JBColor.RED
        resultPanel.add(statusLabel, BorderLayout.NORTH)

        // Content
        val content = when {
            result.error != null -> buildErrorView(result.error)
            result.rows.isNotEmpty() -> buildTableView(result.rows)
            else -> buildJsonView(result.rawJson)
        }
        resultPanel.add(JBScrollPane(content), BorderLayout.CENTER)
        resultPanel.revalidate()
        resultPanel.repaint()
    }

    private fun buildErrorView(error: String): JBTextArea = JBTextArea(error).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColor.RED
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    private fun buildTableView(rows: List<Map<String, Any?>>): JBTable {
        val keys = rows.flatMap { it.keys }.distinct()
        val model = DefaultTableModel(keys.toTypedArray(), 0).apply {
            rows.forEach { row ->
                addRow(keys.map { row[it]?.toString() ?: "" }.toTypedArray())
            }
        }
        return JBTable(model).apply {
            isStriped = true
            autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS
        }
    }

    private fun buildJsonView(json: String): JBTextArea = JBTextArea(
        if (json.isNotBlank()) json else "(no results)",
    ).apply {
        isEditable = false
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }
}
