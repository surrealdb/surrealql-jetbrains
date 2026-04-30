# SurrealQL JetBrains Plugin — AGENTS.md

This file gives AI agents the conventions and workflows for this repository.
Read it before making changes that touch versioning, releases, or the
`CHANGELOG.md` file.

## Tech stack

- **Language**: Kotlin (JVM 17)
- **Build**: Gradle Kotlin DSL (`build.gradle.kts`)
- **Plugin SDK**: IntelliJ Platform Gradle Plugin v2 (`org.jetbrains.intellij.platform`)
- **Changelog**: [Gradle Changelog Plugin](https://github.com/JetBrains/gradle-changelog-plugin) (`org.jetbrains.changelog`)
- **LSP integration**: [LSP4IJ](https://github.com/redhat-developer/lsp4ij) (Red Hat)

## Project layout

- `src/main/kotlin/com/surrealdb/surql/` — plugin source
  - `settings/` — persistent settings + Settings UI (`SurQLSettings`, `SurQLSettingsConfigurable`)
  - `lsp/` — language-server integration (binary download, LSP4IJ factory, startup activity)
  - `run/` — code-vision "▶ Run" lens, query runner, results tool window
  - `statusbar/` — connection status-bar widget
- `src/main/resources/`
  - `META-INF/plugin.xml` — extension registrations
  - `META-INF/surql.dic` — bundled spell-check dictionary (lowercase, one word per line)
  - `textmate/surql/` — TextMate grammar (synced via `./gradlew updateGrammar`)
  - `lsp/<os-arch>/` — gitignored, populated via `./gradlew downloadLspBinaries`
- `CHANGELOG.md` — release notes (see below)
- `build.gradle.kts` — single source of truth for `version`

## Versioning & release workflow

The plugin version lives **only** in `build.gradle.kts` (`version = "x.y.z"`).
Do not duplicate it elsewhere.

This project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html):

- **PATCH** (`0.2.0 → 0.2.1`) — bug fixes, small UX tweaks, dictionary
  additions, internal refactors with no user-visible behaviour change.
- **MINOR** (`0.2.x → 0.3.0`) — new features, new settings, new extension
  points, additive changes that remain backwards compatible.
- **MAJOR** (`0.x.y → 1.0.0`) — breaking changes to settings storage, removed
  features, or settings whose semantics changed.

### When making any user-visible change

You **must** update `CHANGELOG.md` in the same change that ships the code.
Add the entry under the `## [Unreleased]` section, in the appropriate group:

- **Added** — new features or settings
- **Changed** — changes to existing behaviour
- **Deprecated** — soon-to-be removed features
- **Removed** — features removed in this release
- **Fixed** — bug fixes
- **Security** — vulnerability fixes

Use the same prose style as existing entries: present tense, lowercase
sentences, `code` for identifiers, *italics* for menu paths, and Markdown
links for external references.

Example entry:

```markdown
## [Unreleased]

### Added

- Setting to disable the inline "▶ Run" code lens (*Settings → Tools → SurrealQL*).

### Fixed

- `SCHEMAFULL` no longer flagged as a spelling typo.
```

### When cutting a new release

1. **Bump `version`** in `build.gradle.kts` to the new `x.y.z`.
2. **Promote `[Unreleased]` to a dated section** in `CHANGELOG.md`:
   - Rename the `## [Unreleased]` heading to `## [x.y.z] - YYYY-MM-DD`.
   - Add a fresh empty `## [Unreleased]` section above it.
   - Add the new compare link at the bottom:
     ```
     [Unreleased]: .../compare/vx.y.z...HEAD
     [x.y.z]: .../compare/v<previous>...vx.y.z
     ```
   - Update the existing `[Unreleased]` link to point from the new tag.
3. **Verify** with `./gradlew patchPluginXml` and inspect
   `build/tmp/patchPluginXml/plugin.xml` — the `<change-notes>` block
   must contain the rendered HTML for the new version.
4. **Build & publish**:
   ```bash
   ./gradlew buildPlugin
   ./gradlew publishPlugin   # requires PUBLISH_TOKEN env var
   ```

The Gradle Changelog Plugin renders the section matching the current
`version` from `CHANGELOG.md` straight into the marketplace's "What's New"
tab, so keeping `CHANGELOG.md` accurate is the only step needed to update
release notes on the marketplace.

> Tip: the `./gradlew patchChangelog` task can perform step 2
> automatically. Prefer it when the diff is straightforward.

## Spell-check dictionary

When adding a new SurrealQL keyword that the IDE flags as a typo:

1. Add the word **lowercase, one per line** to
   `src/main/resources/META-INF/surql.dic`.
2. Add a `Fixed` (or `Added`) entry under `[Unreleased]` in `CHANGELOG.md`.

The dictionary is registered via `SurQLBundledDictionaryProvider` and is
case-insensitive, so a single lowercase entry covers all casings.

## Settings

Persistent settings live in `SurQLSettings.State`. When adding one:

1. Add a property to `State` with a sensible default.
2. Add a matching getter/setter on the outer `SurQLSettings` class.
3. Wire a UI control in `SurQLSettingsConfigurable` (field, `createComponent`,
   `isModified`, `apply`, `reset`, `disposeUIResources`).
4. If the setting affects the language server, restart it from `apply()`.
5. Add a `CHANGELOG.md` entry under `[Unreleased] → Added`.

## Quality checks

Before finishing a task that touches Kotlin or `plugin.xml`:

```bash
./gradlew buildPlugin       # compiles + packages
./gradlew verifyPlugin      # IntelliJ Plugin Verifier
```

Avoid running `./gradlew runIde` while another sandbox IDE is open — only one
sandbox instance can run at a time.

## Don'ts

- Don't hardcode the version string anywhere except `build.gradle.kts`.
- Don't write release notes inline in `build.gradle.kts` — they belong in
  `CHANGELOG.md`.
- Don't commit anything under `src/main/resources/lsp/` — those are
  gitignored release artifacts produced by `./gradlew downloadLspBinaries`.
- Don't bump `sinceBuild` / `untilBuild` without a clear compatibility reason.
