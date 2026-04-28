# SurrealQL — JetBrains Plugin

JetBrains IDE integration for [SurrealQL](https://surrealdb.com/docs/surrealql) (`.surql` / `.surrealql` files), compatible with IntelliJ IDEA, PyCharm, WebStorm, GoLand, RustRover, DataGrip, and any other IntelliJ Platform IDE on build **243 (2024.3) or newer**.

## Features

| Feature | Description | Docs |
|---------|-------------|------|
| Syntax highlighting | TextMate grammar from [surrealdb/surrealql-vsx](https://github.com/surrealdb/surrealql-vsx) with live version switching | [docs/grammar.md](docs/grammar.md) |
| Language server | Diagnostics, hover, completions, go-to-definition, references, rename, code actions via `surrealql-language-server` | [docs/language-server.md](docs/language-server.md) |
| Run Query lens | Clickable **▶ Run** code vision label above every statement; executes against a live SurrealDB instance | [docs/run-query.md](docs/run-query.md) |
| Query Results panel | Tool window showing results as a table or JSON with per-run history | [docs/query-results.md](docs/query-results.md) |
| Connection status bar | Status bar widget displaying the active SurrealDB endpoint and namespace/database | [docs/connection-status.md](docs/connection-status.md) |

## Quick Start

1. Install the plugin from the JetBrains Marketplace.
2. Open **Settings → Tools → SurrealQL** and fill in your SurrealDB connection details (endpoint, namespace, database, credentials).
3. Open a `.surql` file — syntax highlighting and language-server intelligence activate automatically.
4. Click **▶ Run** above any statement to execute it and view results in the **SurrealQL Results** tool window.

## Settings

All plugin settings live under **Settings → Tools → SurrealQL**:

- **Grammar version** — pin to a specific release or track the latest automatically.
- **Language server** — enable/disable, pin a version, or point to a custom binary.
- **Inference source** — choose whether schema inference uses local workspace files, a live SurrealDB database, or both.
- **SurrealDB connection** — endpoint URL, namespace, database, username, password, and active auth context. Used for live metadata, schema inference, and the Run Query feature.

## Building from Source

Requirements: JDK 17+, Gradle (use the included `./gradlew` wrapper).

```sh
# Build the distributable ZIP
./gradlew buildPlugin
# Output: build/distributions/surql-jetbrains-<version>.zip

# Run the plugin in a sandboxed IDE instance
./gradlew runIde

# Refresh the bundled fallback grammar from upstream main
./gradlew updateGrammar

# Download LSP binaries for all platforms before publishing a release
./gradlew downloadLspBinaries
```

## Repository Layout

```
src/main/kotlin/com/surrealdb/surql/
├── SurQLGrammarService.kt          # Grammar version management and caching
├── SurQLTextMateBundleProvider.kt  # TextMate bundle entry point
├── SurQLFileIconProvider.kt        # .surql/.surrealql file icon
├── lsp/
│   ├── SurQLLanguageServerFactory.kt   # LSP4IJ wiring and init options
│   ├── SurQLLanguageServerService.kt   # Binary download, caching, lifecycle
│   └── SurQLLspStartupActivity.kt      # Pre-warm binary on project open
├── run/
│   ├── StatementParser.kt          # Depth-tracking statement boundary parser
│   ├── SurQLQueryRunner.kt         # HTTP client for SurrealDB /sql endpoint
│   ├── SurQLCodeVisionProvider.kt  # ▶ Run code vision lens provider
│   ├── SurQLResultsPanel.kt        # Results tool window UI panel
│   └── SurQLResultsToolWindowFactory.kt
├── settings/
│   ├── SurQLSettings.kt            # Persistent application-level settings
│   ├── SurQLSettingsConfigurable.kt # Settings UI page
│   └── SurQLSettingsListener.kt    # Message bus topic for settings changes
└── statusbar/
    ├── SurQLStatusBarWidget.kt     # Connection status bar widget
    └── SurQLStatusBarWidgetFactory.kt

src/main/resources/
├── META-INF/plugin.xml             # Extension point registrations
├── textmate/surql/                 # Bundled fallback TextMate grammar
└── icons/surql.svg                 # File icon

docs/
├── grammar.md          # Syntax highlighting and grammar version management
├── run-query.md        # Run Query lens — usage, statement detection, context
├── query-results.md    # Query Results tool window — table/JSON view, history
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
