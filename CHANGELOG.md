# Changelog

All notable changes to the TokenSlayer JetBrains plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
