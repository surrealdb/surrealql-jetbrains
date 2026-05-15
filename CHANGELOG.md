# SurrealQL JetBrains Plugin Changelog

All notable changes to the SurrealQL JetBrains plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.1](https://github.com/surrealdb-dev/surql-jetbrains/compare/v0.2.0...v0.2.1) - 2026-05-15

### Added

- "Open in Surrealist" main-toolbar action that hands the active
`.surql`/`.surrealql` file off to the Surrealist desktop app via a
`surrealist://` deep link, with a Download CTA when Surrealist isn't
installed.
- Surrealist connection picker under *Settings → Tools → SurrealQL* — pick
from the connections Surrealist persists in its `config.json` (cloud
connections excluded). The choice drives both the language server and
"Open in Surrealist".
- Custom spell-check dictionary so SurrealQL keywords (e.g. `SCHEMAFULL`,
`SCHEMALESS`, `NAMESPACE`) are no longer flagged as typos.
- Setting under *Settings → Tools → SurrealQL* to toggle the inline
"▶ Run" code lens.

### Changed

- Replaced the manual SurrealDB endpoint / namespace / database / credentials
fields in settings with the Surrealist connection picker.

### Fixed

- Removed the duplicate thumbnail image from the marketplace description.
- Fixed the description text not wrapping correctly on the plugin page.

## [0.2.0](https://github.com/surrealdb-dev/surql-jetbrains/compare/v0.1.0...v0.2.0) - 2026-04-29

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

## [0.1.0](https://github.com/surrealdb-dev/surql-jetbrains/releases/tag/v0.1.0) - 2026-04-28

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