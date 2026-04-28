# Run Query

## Overview

The Run Query feature places a clickable **▶ Run** code vision label above the first line of every SurrealQL statement in `.surql` and `.surrealql` files. Clicking the label executes that single statement against a configured SurrealDB instance and displays the result in the [Query Results](query-results.md) tool window.

```
▶ Run
SELECT * FROM user WHERE active = true;

▶ Run
DEFINE TABLE product SCHEMAFULL;
```

## Prerequisites

Configure a SurrealDB connection under **Settings → Tools → SurrealQL**:

- **Endpoint** — e.g. `ws://127.0.0.1:8000` or `http://127.0.0.1:8000`
- **Namespace** and **Database** (optional if the file declares them with `USE NS`/`USE DB`)
- **Username** / **Password** (if the instance requires authentication)

If no endpoint is configured, clicking **▶ Run** shows a balloon notification pointing to the settings page.

## Namespace and Database Resolution

SurrealDB requires an active namespace and database for most queries. The plugin resolves them in this order:

1. **File-level `USE` directives** — the file is scanned for `USE NS` / `USE NAMESPACE` and `USE DB` / `USE DATABASE` statements (case-insensitive). The last occurrence of each is used. This means a file structured like:

   ```sql
   USE NS myproject;
   USE DB production;

   ▶ Run
   SELECT * FROM user;
   ```

   will execute the `SELECT` with `myproject` as the namespace and `production` as the database, without any extra settings.

2. **Settings fallback** — if neither directive is found in the file, the **Namespace** and **Database** values from **Settings → Tools → SurrealQL** are used.

The resolved namespace and database are prepended to the HTTP request body as `USE NS \`...\`; USE DB \`...\`;` statements. This is required because SurrealDB 3.x's HTTP `/sql` endpoint does not honour standalone `NS`/`DB` request headers for session context; inline `USE` statements within the same request body are the correct mechanism.

## How It Works

### Statement Detection (`StatementParser`)

The file text is scanned with a small depth-tracking parser that correctly handles:

- Nested brackets: `()`, `[]`, `{}`
- String literals: single-quoted, double-quoted, and backtick-quoted (with backslash escape support)
- Line comments: `--` and `#`
- Block comments: `/* ... */`

A `;` at depth 0 and outside strings/comments ends a statement. Trailing statements without a terminating `;` are also returned.

### Code Vision Provider (`SurQLCodeVisionProvider`)

Implements IntelliJ Platform's `CodeVisionProvider<Boolean>` API:

- `precomputeOnUiThread` — checks on the EDT whether the editor holds a SurrealQL file.
- `computeCodeVision` — runs on a background thread, calls `findStatements()`, and produces one `ClickableTextCodeVisionEntry("▶ Run")` per statement.

The click handler:
1. Calls `extractUseDirectives()` on the live document text to resolve namespace/database.
2. Dispatches execution to a pooled background thread.
3. Calls `SurQLQueryRunner.execute()`.
4. On completion, activates the **SurrealQL Results** tool window and passes the result to `SurQLResultsPanel.addResult()` on the EDT.

### HTTP Execution (`SurQLQueryRunner`)

Sends a `POST` request to the SurrealDB HTTP `/sql` endpoint:

- **URL**: derived from the configured endpoint — `ws://` → `http://`, `wss://` → `https://`, any path stripped, `/sql` appended.
- **Body**: `USE NS \`...\`; USE DB \`...\`; <statement>` — the `USE` preamble establishes session context for the actual query within the same request.
- **Auth**: `Authorization: Basic base64(user:pass)` if credentials are configured.
- **Response**: a JSON array; the plugin surfaces the first `ERR` entry (for setup failures) or the last entry (the actual query result).

Connection timeout: 10 seconds. Request timeout: 30 seconds.
