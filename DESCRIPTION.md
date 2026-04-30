**SurrealQL** language support for JetBrains IDEs — brings first-class editing
of `.surql` and `.surrealql` files to IntelliJ IDEA, PyCharm, WebStorm, GoLand,
RustRover, DataGrip, and any other IntelliJ-based IDE.

### Features

- Syntax highlighting via the official TextMate grammar from
  [surrealdb/surrealql-vsx](https://github.com/surrealdb/surrealql-vsx)
- Diagnostics, hover, completion, go-to-definition, references, rename and
  code actions via the
  [surrealql-language-server](https://github.com/surrealdb/surrealql-language-server)
  (LSP4IJ)
- Custom file icon for `.surql` and `.surrealql` files in the project view
  and editor tabs
- Grammar version picker — always stay on the latest release or pin to any
  older version, switched live without an IDE restart
- Inline "▶ Run" code lens to execute statements against a configured
  SurrealDB endpoint, with results in a dedicated tool window
- Status-bar widget showing the active SurrealDB connection
- Offline fallback: the latest grammar at build time is bundled inside the
  plugin so highlighting works even without a network connection
- Settings page under *Settings → Tools → SurrealQL*

### Compatibility

Works in any IntelliJ Platform IDE on build `243` (2024.3) or newer.

### Links

- [SurrealDB](https://surrealdb.com)
- [SurrealQL documentation](https://surrealdb.com/docs/surrealdb/surrealql)
- [GitHub repository](https://github.com/surrealdb-dev/surql-jetbrains)
- [Issue tracker](https://github.com/surrealdb-dev/surql-jetbrains/issues)
