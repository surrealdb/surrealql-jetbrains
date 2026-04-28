# Syntax Highlighting and Grammar Version Management

## Overview

The SurrealQL plugin provides syntax highlighting for `.surql` and `.surrealql` files using a [TextMate grammar](https://macromates.com/manual/en/language_grammars) sourced from the official [surrealdb/surrealql-vsx](https://github.com/surrealdb/surrealql-vsx) repository.

The grammar is versioned independently of the plugin itself. The plugin fetches published grammar releases from GitHub at startup and can switch to any version without restarting the IDE.

## Usage

### Automatic update (default)

By default the plugin always activates the **latest published grammar release**. On startup it fetches the release list from GitHub, downloads the grammar if it is not already cached locally, and activates it. Subsequent restarts reuse the cached copy unless a newer release is available.

### Pinning a version

1. Open **Settings → Tools → SurrealQL**.
2. In the **Grammar** group, open the **Version** dropdown.
3. Select any published release tag (e.g. `v0.3.1`).
4. Click **Apply** — the grammar swaps out immediately; no IDE restart is required.

To return to automatic tracking select **Latest (automatically updated)** from the top of the list.

### Offline fallback

If the IDE has no network access at startup, the plugin falls back to the grammar copy bundled inside the plugin JAR. The version dropdown shows **Latest (bundled)** in that case.

## How It Works

```
IDE startup
  └── SurQLTextMateBundleProvider.getBundles()
        └── SurQLGrammarService.getCurrentPath()
              ├── cached version on disk?  →  use it
              ├── network available?       →  download latest / pinned version
              │     cached at: $IDE_SYSTEM_DIR/surql-grammar-cache/{tag}/
              └── fallback                →  extract bundled copy from JAR
```

**`SurQLGrammarService`** is an application-level service that:
- Fetches available release tags from the GitHub Releases API (`surrealdb/surrealql-vsx`).
- Downloads grammar files into a platform-specific cache directory managed by the IDE.
- Exposes the resolved grammar path to `SurQLTextMateBundleProvider`.

**`SurQLTextMateBundleProvider`** implements the IntelliJ Platform `TextMateBundleProvider` extension point. The IDE calls `getBundles()` whenever TextMate bundles are reloaded (including after **Apply** in settings).

## Refreshing the Bundled Copy

The plugin JAR ships a copy of the grammar so it works fully offline. Before cutting a new plugin release, update this copy from upstream:

```sh
./gradlew updateGrammar
```

This downloads `surrealql.tmLanguage.json` and `language-configuration.json` from the `main` branch of `surrealdb/surrealql-vsx` into `src/main/resources/textmate/surql/`. Commit those changes as part of the release commit.
