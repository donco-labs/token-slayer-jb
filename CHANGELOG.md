# Changelog

All notable changes to the TokenSlayer JetBrains plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`tokenslayer_find` MCP tool** — searches for a class, function, or other symbol by name
  across the whole project, backed by the IDE's own Go to Class / Go to Symbol index rather than
  a text search over files. Every hit comes back as a citable `file:line — signature`, usable
  directly with `tokenslayer_expand`. This is the tool an assistant should reach for instead of
  grep/find-in-files when it doesn't yet know which file to look in.
- **Line-cited skeletons** — every signature `tokenslayer_structural_summary` renders now carries
  its own `:line` (or `:line-range`), in the same citation format `tokenslayer_expand` already
  used. A skeleton is no longer only descriptive; it's a set of coordinates an assistant can act
  on directly, without a second lookup just to find where something is.
- **`tokenslayer_references` MCP tool** — finds every usage of a symbol project-wide ("who calls
  this"), backed by the IDE's own find-usages index rather than a text match on the name. Each
  hit is a citable `file:line — enclosing signature`. Symbol resolution (bare name, dotted
  qualification, ambiguity) is identical to `tokenslayer_expand`'s.
- **YAML support** — `tokenslayer_structural_summary` and `tokenslayer_expand` now understand
  `.yaml`/`.yml` files, via a new PSI-free block-YAML parser (no YAML language plugin needed, so
  this works in every JetBrains IDE, same as the rest of the plugin). Because a config value IS
  the content — unlike code, compaction happens along different axes: a `---`-separated manifest
  bundle collapses to one line per resource (addressable by `Kind.name`), a list of many
  same-shaped map items collapses to its first item plus a count, long scalars (certificates,
  inline scripts, base64 data) are elided to their length, and deep nesting collapses past a
  fixed depth. `tokenslayer_find` and `tokenslayer_references` remain code-only — a data key has
  no "where is this defined" or "who calls this" the way a code symbol does.

### Fixed

- **SecretsDetector missed unquoted config values.** Every credential-shaped content pattern
  (password, api_key, secret_key, access_token, …) required a quote immediately after the value —
  `password: "hunter2"` was caught, `password: hunter2` was not. YAML and `.env` files
  conventionally omit quotes on scalar values, so this was silently letting real credentials in
  `values.yaml`-style files and rendered Kubernetes Secret manifests through unexcluded. The
  generic environment-secret pattern also only accepted `=` as a separator; it now accepts `:`
  too.
- **SecretsDetector only scanned the first 5,000 characters** (~100 lines) of a file, regardless
  of the file's actual size. A secret past that point in a large file went undetected. Raised to
  200,000 characters, which comfortably covers a large multi-document manifest.

## [0.5.0]

### Added

- **`tokenslayer_expand` MCP tool** — returns the real source of a single symbol from a file.
  Previously the only tool was the skeleton, so as soon as an assistant needed one implementation
  it had to read the whole file, discarding the saving on the very file it had just economised on.
  Ambiguous or unknown symbol names come back with the list of symbols available in that file, so
  the assistant can correct itself without reading anything.
- **Realized savings** — content actually served over MCP is now recorded, and the dashboard's
  headline figure reports what was genuinely delivered to an assistant. The previous figure summed
  every file analyzed whether or not anything ever requested it, which meant there was no way to
  tell whether the tools were being called at all. Potential savings remain, as a separate stat.

### Fixed

- Dashboard action buttons (**Analyze Workspace**, **Copy Summary**, **Export Report**) are pinned
  to the bottom of the tool window instead of sitting at the end of the scrolled content, where
  they were only reachable after scrolling past every section.
- Those buttons no longer disappear one by one as the tool window is narrowed; they now wrap onto
  additional rows instead of being clipped.
- The MCP tool schema declared `enum` as a comma-joined string rather than a JSON array, which
  validating clients ignore.
- The MCP handshake reported a hardcoded `serverInfo.version` of 0.2.0 regardless of the installed
  version; it is now sourced from the build.
- Cached entries are looked up by path through an index rather than a linear scan over a copy of
  the whole cache — that scan ran per node per repaint of the project tree.
- Re-analyzing a changed file no longer leaves the previous revision's entry in the cache.

## [0.4.0]

### Changed

- Workspace analysis reports progress in the TokenSlayer tool window ("Analyzing… N / M files") and
  shows a completion notification for explicitly requested runs. The background task's status-bar
  indicator only appears in its own project window, which made it easy to miss.
- Overlapping analysis runs are coalesced, so a startup scan and a manual **Analyze Workspace** no
  longer scan the project twice.

### Fixed

- Dashboard and analysis are now scoped per project. With several workspaces open they shared a
  single cache, so every dashboard summed all of them; identical files in different projects also
  collided on their cache key, silently costing the second project its tree badge and skeleton.
- File changes are no longer analyzed into every open project's cache — only the project that
  actually owns the file.
- The Copilot MCP tool now resolves the workspace containing the requested file instead of
  picking an arbitrary open project.
- Light theme no longer renders with the dark palette (both `JBColor` variants were set to the
  dark colour). Backgrounds and body text now follow the active IDE theme, including custom ones.
- The project-tree reduction badge no longer hides the file name.
- A corrupted IntelliJ VFS cache no longer crashes workspace analysis; it reports what happened
  and points at **File → Invalidate Caches and Restart**.
- The **Cache max entries** setting is now honored (it previously had no effect); the cap applies
  per project.
- The **Auto-analyze on open** setting is now honored — the startup scan previously always ran and
  could not be turned off.

## [0.3.0]

### Added

- GitHub Copilot (MCP) settings section (**Settings → Tools → TokenSlayer**) showing the server URL and a "Copy Copilot mcp.json snippet" button for registering the server in `~/.config/github-copilot/intellij/mcp.json`.
- Stable, configurable local port for the embedded MCP server, so a Copilot registration keeps working across IDE restarts.

### Changed

- The plugin now loads in IDEs without the Java module (PyCharm, WebStorm, GoLand, Rider, …); all language support is isolated in optional modules loaded only when that language is present.
- Structural extraction now uses a recursive PSI traversal, so Python, JavaScript/TypeScript, Kotlin and Go produce real skeletons instead of empty ones.
- Inlay hints migrated to the stable declarative inlay API (from the deprecated experimental one).
- Original and skeleton token counts now use the same language-aware estimator, for an honest reduction figure.
- Removed the hardcoded `until-build` cap so the plugin stays installable on newer IDE releases.
- Release version is now derived from the pushed git tag rather than hardcoded.

### Fixed

- No longer crashes on PyCharm/WebStorm — the hard dependency on the Java module was removed.
- Language-routing bug that sent JavaScript files to the Java extractor and produced empty skeletons.
- The MCP tool's `verbosity` argument is now honored.
- MCP server startup failures no longer surface the IDE's red "Internal Error" dialog.
- Stopped writing `.github/copilot-mcp.json` into the user's project.
- Added the missing tool-window icon class that failed to resolve at load time.

## [0.2.0]

### Fixed

- Bug fixes, code style corrections, and formatting improvements.

## [0.1.0]

### Added

- Initial release: AST-driven skeleton extraction for Java, Kotlin, Python, JS/TS, Go, Rust.
- GitHub Copilot MCP server integration.
- Live dashboard tool window with token savings analytics.
- Inline inlay hints (⚡ N→M lines skeleton).
- Secrets detection and exclusion.
- Skeleton preview (diff view).
- Export savings report.

[Unreleased]: https://github.com/donco-labs/token-slayer-jb/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/donco-labs/token-slayer-jb/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/donco-labs/token-slayer-jb/releases/tag/v0.1.0
