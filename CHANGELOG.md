# SurrealQL JetBrains Plugin Changelog

All notable changes to the SurrealQL JetBrains plugin are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-04-30

### Added

- Integration with the
  [surrealql-language-server](https://github.com/surrealdb/surrealql-language-server)
  via LSP4IJ: diagnostics, hover, completion, go-to-definition, references,
  rename and code actions.
- Inline "▶ Run" code lens above each statement that executes the query
  against the configured SurrealDB endpoint.
- "SurrealQL Results" tool window for query output and history.
- Status-bar widget showing the active SurrealDB connection.
- Settings for the language-server version, binary override, inference
  source (workspace / database / both), and SurrealDB connection details.

## [0.1.0] - 2025-09-01

### Added

- Initial release.
- Syntax highlighting for `.surql` and `.surrealql` files via the official
  TextMate grammar from
  [surrealdb/surrealql-vsx](https://github.com/surrealdb/surrealql-vsx).
- Custom file icon for `.surql` and `.surrealql` files in the project view
  and editor tabs.
- Grammar version picker under *Settings → Tools → SurrealQL* — pick the
  latest release or pin to any published version, switched live without an
  IDE restart.
- Offline fallback grammar bundled inside the plugin JAR.

[[Unreleased]]: https://github.com/surrealdb-dev/surql-jetbrains/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/surrealdb-dev/surql-jetbrains/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/surrealdb-dev/surql-jetbrains/commits/v0.1.0
[Unreleased]: https://github.com/surrealdb-dev/surql-jetbrains/compare/v0.2.0...HEAD
