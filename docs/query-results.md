# Query Results Tool Window

## Overview

The **SurrealQL Results** tool window is anchored to the bottom panel and displays the output of queries executed via the [Run Query](run-query.md) feature. It maintains a history of up to 50 runs within the current IDE session.

Open it manually via **View → Tool Windows → SurrealQL Results**, or let it open automatically when a query is run.

## Layout

```
┌─────────────────────────────────────────────────────────────────────┐
│  [Copy as JSON]  [Clear]                                   Toolbar  │
├──────────────────────┬──────────────────────────────────────────────┤
│  Query History       │  Result View                                  │
│  ──────────────────  │  ─────────────────────────────────────────── │
│  SELECT * FROM use…  │  OK  ·  37.5µs  ·  3 row(s)                 │
│    12:04:31          │                                               │
│  UPDATE page CONTE…  │  id        name     email                    │
│    12:04:28          │  user:1    Alice    alice@example.com        │
│                      │  user:2    Bob      bob@example.com          │
│                      │  user:3    Carol    carol@example.com        │
└──────────────────────┴──────────────────────────────────────────────┘
```

### History list (left, ~28 %)

Each entry shows the first line of the executed statement and the time the query ran. Clicking an entry re-displays that result in the right panel without re-executing the query.

### Result view (right, ~72 %)

A status bar at the top of the result view shows:

```
<status>  ·  <server-reported execution time>  ·  <row count>
```

The main content area adapts to the result type:

| Result type | Display |
|-------------|---------|
| Array of records | `JBTable` — columns derived from the union of all keys across all rows; nested objects and arrays are shown as compact JSON strings |
| Error (`status: ERR`) | Red text showing the server error message |
| Non-array result | Monospaced text area with pretty-printed JSON |

### Toolbar

| Button | Action |
|--------|--------|
| **Copy as JSON** | Copies the raw `result` JSON to the system clipboard |
| **Clear** | Clears all history entries and resets the result view |

## Result Selection Logic

When a query includes preamble `USE NS`/`USE DB` statements (added automatically by the Run Query feature), the HTTP response array contains entries for those statements as well as the actual query. The plugin applies the following selection logic:

1. If **any** entry has `status: ERR`, show the first such entry — this surfaces setup failures (e.g. unknown namespace or database) immediately.
2. Otherwise show the **last** entry, which corresponds to the actual user query.

## History Limit

The history is capped at **50 entries** per IDE session. When the limit is reached, the oldest entry is dropped. History is not persisted across IDE restarts.

## Implementation Notes

`SurQLResultsPanel` is a plain `JPanel(BorderLayout)` composed of:

- `JBSplitter(false, 0.28f)` — horizontal split between history and result
- `JBList<HistoryEntry>` with a `ColoredListCellRenderer` — history list
- `JBTable` / `JBTextArea` — result content, recreated on each selection change

`SurQLResultsToolWindowFactory` is the `ToolWindowFactory` registered in `plugin.xml`. The tool window is created lazily on first access; `ToolWindow.activate { ... }` ensures the content is initialised before the panel is accessed from the Run Query click handler.
