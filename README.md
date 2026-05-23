# TokenSlayer for JetBrains

<p align="center">
  <img src="images/icon.png" width="350" alt="TokenSlayer Logo"/>
</p>

> ⚡ Slash LLM token usage by **40–95%** with AST-driven code skeletons — for IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, and more.

**A JetBrains port of the original [TokenSlayer VS Code Extension](https://github.com/ajvikram/TokenSlayer) created by [Ajay Vikram](https://github.com/ajvikram).**

[![CI](https://github.com/donco-labs/token-slayer-jb/actions/workflows/ci.yml/badge.svg)](https://github.com/donco-labs/token-slayer-jb/actions/workflows/ci.yml)

TokenSlayer eliminates the "orientation tax" AI coding assistants pay when reading raw source files. Instead of sending 1,200 lines of code (~5,000 tokens) to GitHub Copilot, it generates a compact 8-line structural skeleton (~200 tokens) — a **96% token reduction**.

```
Without TokenSlayer:  1,200 lines of raw code → 5,000 tokens consumed
With TokenSlayer:     8-line structural skeleton → 200 tokens consumed (96% reduction)
```

## 🔧 GitHub Copilot Integration

TokenSlayer registers an embedded **MCP server** via the `com.github.copilot` extension point. Copilot can invoke the `tokenslayer_structural_summary` tool autonomously — the JetBrains equivalent of VS Code's `#tokenslayer-structural-summary`.

```
User:  How is authentication structured in this codebase?
Copilot → calls tokenslayer_structural_summary
       → receives compact skeleton
       → answers using 200 tokens instead of 5,000
```

## 📊 Features

- **🔧 Copilot MCP Tool** — `tokenslayer_structural_summary` auto-invoked by Copilot Chat
- **📊 Live Dashboard** — token savings counter, language breakdown, top savers, cache stats
- **⚡ Inline Inlay Hints** — `⚡ ~119 lines → ~14 lines skeleton` above each class/function
- **📂 Project Tree Badges** — color-coded reduction percentages on file nodes
- **🛡️ Secrets Detection** — auto-excludes files with AWS keys, GitHub tokens, passwords, etc.
- **👁️ Skeleton Preview** — side-by-side diff view of original vs skeleton
- **📋 Export Report** — Markdown savings report for the workspace
- **🌐 Languages** — Java, Kotlin, Python, JS, TypeScript, Go, Rust

## ⌨️ Commands (Tools menu / right-click)

| Command | Description |
|---------|-------------|
| Analyze Workspace | Scan all supported files and build skeleton cache |
| Analyze Current File | Analyze the active editor file |
| Preview Skeleton | Side-by-side diff: original vs skeleton |
| Copy Skeleton Summary | Copy skeleton to clipboard for Copilot Chat |
| Export Savings Report | Write `tokenslayer-report.md` to project root |
| Clear Cache | Wipe all cached skeletons |

## ⚙️ Settings

**Settings → Tools → TokenSlayer**

| Setting | Default | Description |
|---------|---------|-------------|
| Max file size (KB) | 500 | Files larger than this are skipped |
| Cache max entries | 500 | LRU cache size |
| Verbosity | standard | `minimal` / `standard` / `detailed` |
| Ignored paths | node_modules, build… | Comma-separated path fragments to skip |
| Enable inlay hints | true | ⚡ hints above classes/functions |
| Enable file decorations | true | Reduction badges on Project tree |

## 🏗️ Architecture

```
┌────────────────────────────────────────────────────┐
│                  IntelliJ Plugin Host               │
├────────────────────────────────────────────────────┤
│                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────┐ │
│  │  MCP Server  │  │  Dashboard   │  │  Inlay   │ │
│  │  (Copilot)   │  │  (Swing TW)  │  │  Hints   │ │
│  └──────┬───────┘  └──────┬───────┘  └────┬─────┘ │
│         │                 │               │        │
│  ┌──────▼─────────────────▼───────────────▼──────┐ │
│  │              TokenSlayerService                │ │
│  ├────────────────────────────────────────────────┤ │
│  │  ┌─────────────┐  ┌──────────────┐  ┌───────┐ │ │
│  │  │  PSI Symbol │  │   Skeleton   │  │Secrets│ │ │
│  │  │  Extractor  │  │   Builder    │  │Detect │ │ │
│  │  └──────┬──────┘  └──────┬───────┘  └───┬───┘ │ │
│  │         │                │              │      │ │
│  │  ┌──────▼────────────────▼──────────────▼────┐ │ │
│  │  │              Compactors                   │ │ │
│  │  │  Java │ Kotlin │ Python │ JS/TS │ Go │ Rust│ │ │
│  │  └───────────────────────┬───────────────────┘ │ │
│  │                          │                      │ │
│  │  ┌───────────────────────▼───────────────────┐  │ │
│  │  │       LRU Cache (PersistentStateComponent) │  │ │
│  │  └───────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────┘ │
│                                                    │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────┐  │
│  │  File Tree  │  │   Skeleton   │  │  Status  │  │
│  │  Decorator  │  │   Preview    │  │   Bar    │  │
│  └─────────────┘  └──────────────┘  └──────────┘  │
└────────────────────────────────────────────────────┘
```

## 🛠️ Development

### Requirements
- JDK 17+
- IntelliJ IDEA (for plugin development)

### Build & Run

```bash
# Clone
git clone https://github.com/donco-labs/token-slayer-jb.git
cd token-slayer-jb

# Run in dev IDE (launches IntelliJ with plugin loaded)
./gradlew runIde

# Run tests
./gradlew test

# Build distributable ZIP
./gradlew buildPlugin

# Verify plugin compatibility
./gradlew verifyPlugin
```

### Install from ZIP
1. Build: `./gradlew buildPlugin`
2. In JetBrains IDE: **Settings → Plugins → ⚙️ → Install Plugin from Disk…**
3. Select `build/distributions/token-slayer-jb-*.zip`
4. Restart IDE

### Release

```bash
# Tag a release — GitHub Actions will automatically:
# 1. Run all CI checks + Plugin Verifier
# 2. Build the plugin ZIP
# 3. Create a GitHub Release with the ZIP attached
git tag v0.2.0
git push origin v0.2.0
```

## 📝 License

MIT — JetBrains port of [TokenSlayer](https://github.com/ajvikram/TokenSlayer) by Ajay Vikram
