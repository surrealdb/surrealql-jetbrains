# surql-jetbrains

JetBrains IDE integration for SurrealQL (`.surql` / `.surrealql` files).

## Features

- Syntax highlighting via a TextMate grammar sourced from [surrealdb/surrealql-vsx](https://github.com/surrealdb/surrealql-vsx)
- Grammar version picker: always stay on the latest release or pin to any older version

## Grammar version management

On startup the plugin fetches the list of published grammar versions from
[GitHub Releases](https://github.com/surrealdb/surrealql-vsx/releases) and automatically
downloads and activates the latest one.

To pin to an older version (or roll back when a regression is introduced):

1. Open **Settings → Tools → SurrealQL**
2. Select the desired version from the **Version** dropdown
3. Click **Apply** — the grammar is swapped out immediately without restarting the IDE

If the IDE is offline at startup, the grammar bundled inside the plugin JAR is used as a
fallback and the dropdown shows only *"Latest (bundled)"*.

## Building from Source

Requirements: JDK 17+, Gradle (or use `./gradlew`).

```sh
./gradlew buildPlugin
```

The distributable ZIP is produced at `build/distributions/surql-jetbrains-<version>.zip`.

To run the plugin in a sandboxed IDE instance:

```sh
./gradlew runIde
```

### Refreshing the bundled fallback grammar

The plugin ships a copy of the grammar inside the JAR so it works offline. Before cutting a
new plugin release, update this copy from the upstream `main` branch:

```sh
./gradlew updateGrammar
```

Commit the changes in `src/main/resources/textmate/surql/` as part of the release commit.

## Architecture

```
.surql file opened
  └── SurQLTextMateBundleProvider.getBundles()
        ├── SurQLGrammarService.getCurrentPath()   → downloaded version from GitHub
        │     └── cached at $SYSTEM/surql-grammar-cache/{tag}/
        └── fallback: built-in grammar from plugin JAR
              → TextMate syntax coloring applied to editor
```

### Other publisher-page fields

- **Documentation URL**: <https://surrealdb.com/docs/reference/query-language>
- **Source URL**: <https://github.com/surrealdb-dev/surql-jetbrains>
- **Bug tracker URL**: <https://github.com/surrealdb-dev/surql-jetbrains/issues>
- **License**: Apache-2.0

> **Note:** the `description` HTML in [`build.gradle.kts`](build.gradle.kts) is what gets
> uploaded with each new plugin version, but if you have manually edited the description on
> the publisher page the marketplace will keep its manual override and not auto-replace it
> on the next upload. Re-paste the generated description into the publisher page if you
> want HTML edits to take effect.

## License

Apache-2.0 — see [LICENSE](LICENSE).
