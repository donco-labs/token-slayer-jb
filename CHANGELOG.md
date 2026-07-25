# Changelog

All notable changes to the TokenSlayer JetBrains plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
