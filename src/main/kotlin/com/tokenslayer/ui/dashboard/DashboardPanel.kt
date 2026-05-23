package com.tokenslayer.ui.dashboard

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.tokenslayer.actions.ExportReportAction
import com.tokenslayer.services.TokenSlayerService
import com.tokenslayer.types.WorkspaceStats
import com.tokenslayer.utils.TokenEstimator
import java.awt.*
import java.awt.event.ActionEvent
import java.util.concurrent.TimeUnit
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Main dashboard panel — the JetBrains equivalent of VS Code's webview sidebar.
 * Shows token savings stats, language breakdown, top savers, and excluded files.
 * Auto-refreshes every 5 seconds.
 */
class DashboardPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val tsService = TokenSlayerService.getInstance()

    // ── Stat labels ───────────────────────────────────────────────────────────
    private val heroLabel = JBLabel("0", SwingConstants.CENTER)
    private val reductionLabel = JBLabel("0%")
    private val filesLabel = JBLabel("0")
    private val cacheHitLabel = JBLabel("0%")
    private val cachedEntriesLabel = JBLabel("0")
    private val excludedCountLabel = JBLabel("0")

    // ── Content panels ────────────────────────────────────────────────────────
    private val langPanel = JPanel()
    private val topSaversPanel = JPanel()
    private val recentPanel = JPanel()
    private val secretsPanel = JPanel()
    private var refreshTask: java.util.concurrent.ScheduledFuture<*>? = null

    init {
        background = JBColor(Color(0x1E1E2E), Color(0x1E1E2E))
        border = EmptyBorder(8, 8, 8, 8)
        buildUI()
        startAutoRefresh()
        refresh()
    }

    private fun buildUI() {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        content.add(buildHeroSection())
        content.add(Box.createVerticalStrut(12))
        content.add(buildStatsGrid())
        content.add(Box.createVerticalStrut(12))
        content.add(buildSection("📊 Language Breakdown", langPanel))
        content.add(Box.createVerticalStrut(8))
        content.add(buildSection("🏆 Top Savers", topSaversPanel))
        content.add(Box.createVerticalStrut(8))
        content.add(buildSection("🕐 Recent Activity", recentPanel))
        content.add(Box.createVerticalStrut(8))
        content.add(buildSection("🛡️ Excluded Files (Secrets)", secretsPanel))
        content.add(Box.createVerticalStrut(12))
        content.add(buildActionButtons())

        add(
            JBScrollPane(content).apply {
                border = null
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )
    }

    // ── Hero section ──────────────────────────────────────────────────────────

    private fun buildHeroSection(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        heroLabel.apply {
            font = Font("JetBrains Mono", Font.BOLD, 36)
            foreground = JBColor(Color(0x89DCEB), Color(0x89DCEB))
        }

        val titleLabel =
            JBLabel("⚡ Tokens Slayed", SwingConstants.CENTER).apply {
                font = Font(font.name, Font.PLAIN, 11)
                foreground = JBColor(Color(0xBAC2DE), Color(0xBAC2DE))
            }

        val inner = JPanel(GridLayout(2, 1))
        inner.isOpaque = false
        inner.add(heroLabel)
        inner.add(titleLabel)
        panel.add(inner, BorderLayout.CENTER)
        return panel
    }

    // ── Stats grid ────────────────────────────────────────────────────────────

    private fun buildStatsGrid(): JPanel {
        val grid = JPanel(GridLayout(2, 3, 8, 8))
        grid.isOpaque = false

        grid.add(statCard("Reduction", reductionLabel, Color(0xA6E3A1)))
        grid.add(statCard("Files", filesLabel, Color(0x89DCEB)))
        grid.add(statCard("Cache Hit", cacheHitLabel, Color(0xF9E2AF)))
        grid.add(statCard("Cached", cachedEntriesLabel, Color(0xCBA6F7)))
        grid.add(statCard("Excluded", excludedCountLabel, Color(0xF38BA8)))
        grid.add(
            statCard(
                "MCP Server",
                JBLabel("●").apply {
                    foreground = Color(0xA6E3A1)
                },
                Color(0xA6E3A1),
            ),
        )

        return grid
    }

    private fun statCard(
        title: String,
        valueLabel: JComponent,
        accent: Color,
    ): JPanel {
        val card = JPanel(BorderLayout(0, 2))
        card.border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(0x313244), Color(0x313244))),
                EmptyBorder(8, 8, 8, 8),
            )
        card.background = JBColor(Color(0x181825), Color(0x181825))

        val titleLbl =
            JBLabel(title).apply {
                font = Font(font.name, Font.PLAIN, 10)
                foreground = JBColor(Color(0xBAC2DE), Color(0xBAC2DE))
            }
        if (valueLabel is JBLabel) {
            valueLabel.font = Font("JetBrains Mono", Font.BOLD, 16)
            valueLabel.foreground = JBColor(accent, accent)
        }

        card.add(titleLbl, BorderLayout.NORTH)
        card.add(valueLabel, BorderLayout.CENTER)
        return card
    }

    // ── Section builder ───────────────────────────────────────────────────────

    private fun buildSection(
        title: String,
        contentPanel: JPanel,
    ): JPanel {
        val wrapper = JPanel(BorderLayout(0, 4))
        wrapper.isOpaque = false

        val titleLbl =
            JBLabel(title).apply {
                font = Font(font.name, Font.BOLD, 12)
                foreground = JBColor(Color(0xCDD6F4), Color(0xCDD6F4))
                border = EmptyBorder(0, 0, 4, 0)
            }

        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false

        wrapper.add(titleLbl, BorderLayout.NORTH)
        wrapper.add(contentPanel, BorderLayout.CENTER)
        return wrapper
    }

    // ── Action buttons ────────────────────────────────────────────────────────

    private fun buildActionButtons(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        panel.isOpaque = false

        val analyzeBtn =
            JButton("🔄 Analyze Workspace").apply {
                addActionListener { _: ActionEvent ->
                    com.tokenslayer.services.ProjectAnalyzerService.getInstance(project).analyzeAll { refresh() }
                }
            }

        val copyBtn =
            JButton("📋 Copy Summary").apply {
                addActionListener { _: ActionEvent ->
                    val stats = tsService.computeStats()
                    val report = buildQuickSummary(stats)
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(java.awt.datatransfer.StringSelection(report), null)
                }
            }

        val exportBtn =
            JButton("📄 Export Report").apply {
                addActionListener { _: ActionEvent ->
                    com.tokenslayer.actions.ExportReportAction.doExport(project)
                }
            }

        panel.add(analyzeBtn)
        panel.add(copyBtn)
        panel.add(exportBtn)
        return panel
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    private fun startAutoRefresh() {
        refreshTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                if (project.isDisposed) {
                    refreshTask?.cancel(false)
                    return@scheduleWithFixedDelay
                }
                SwingUtilities.invokeLater { refresh() }
            },
            5,
            5,
            TimeUnit.SECONDS,
        )
    }

    private fun refresh() {
        val stats = tsService.computeStats()
        updateHero(stats)
        updateStats(stats)
        updateLanguages(stats)
        updateTopSavers(stats)
        updateRecent(stats)
        updateSecrets(stats)
        revalidate()
        repaint()
    }

    private fun updateHero(stats: WorkspaceStats) {
        heroLabel.text = TokenEstimator.format(stats.totalTokensSaved)
    }

    private fun updateStats(stats: WorkspaceStats) {
        reductionLabel.text = "${stats.reductionPct}%"
        filesLabel.text = stats.filesAnalyzed.toString()
        cacheHitLabel.text = "${stats.cacheHitRate}%"
        cachedEntriesLabel.text = stats.filesAnalyzed.toString()
        excludedCountLabel.text = stats.excludedFiles.toString()
    }

    private fun updateLanguages(stats: WorkspaceStats) {
        langPanel.removeAll()
        if (stats.languageBreakdown.isEmpty()) {
            langPanel.add(dimLabel("No data yet"))
            return
        }
        val langIcons =
            mapOf(
                "java" to "☕",
                "kotlin" to "🅺",
                "python" to "🐍",
                "typescript" to "🔷",
                "javascript" to "🟨",
                "go" to "🔵",
                "rust" to "🦀",
            )
        stats.languageBreakdown.values.sortedByDescending { it.tokensSaved }.forEach { ls ->
            val icon = langIcons[ls.language.lowercase()] ?: "📄"
            val row = JPanel(BorderLayout(8, 0))
            row.isOpaque = false
            row.add(
                JBLabel("$icon ${ls.language}  ${ls.files}f  ${ls.reductionPct}%").apply {
                    font = Font("JetBrains Mono", Font.PLAIN, 11)
                    foreground = JBColor(Color(0xCDD6F4), Color(0xCDD6F4))
                },
                BorderLayout.WEST,
            )
            val bar =
                JProgressBar(0, 100).apply {
                    value = ls.reductionPct
                    isStringPainted = false
                    background = Color(0x313244)
                    foreground = Color(0x89DCEB)
                    preferredSize = Dimension(80, 8)
                }
            row.add(bar, BorderLayout.EAST)
            row.maximumSize = Dimension(Int.MAX_VALUE, 20)
            langPanel.add(row)
            langPanel.add(Box.createVerticalStrut(4))
        }
    }

    private fun updateTopSavers(stats: WorkspaceStats) {
        topSaversPanel.removeAll()
        val medals = listOf("🥇", "🥈", "🥉", "4.", "5.")
        if (stats.topSavers.isEmpty()) {
            topSaversPanel.add(dimLabel("No data yet"))
            return
        }
        stats.topSavers.take(5).forEachIndexed { idx, entry ->
            val medal = medals.getOrElse(idx) { "${idx + 1}." }
            val name = entry.filePath.split("/", "\\").last()
            topSaversPanel.add(
                JBLabel("$medal $name  −${TokenEstimator.format(entry.tokensSaved)} tok  ${entry.reductionPct}%").apply {
                    font = Font("JetBrains Mono", Font.PLAIN, 11)
                    foreground = JBColor(Color(0xCDD6F4), Color(0xCDD6F4))
                    maximumSize = Dimension(Int.MAX_VALUE, 18)
                },
            )
            topSaversPanel.add(Box.createVerticalStrut(3))
        }
    }

    private fun updateRecent(stats: WorkspaceStats) {
        recentPanel.removeAll()
        if (stats.recentActivity.isEmpty()) {
            recentPanel.add(dimLabel("No recent activity"))
            return
        }
        stats.recentActivity.take(8).forEach { entry ->
            val name = entry.filePath.split("/", "\\").last()
            val badge =
                when {
                    entry.reductionPct >= 70 -> "🟢 ${entry.reductionPct}%"
                    entry.reductionPct >= 40 -> "🟡 ${entry.reductionPct}%"
                    else -> "⚪ ${entry.reductionPct}%"
                }
            recentPanel.add(
                JBLabel("$badge  $name").apply {
                    font = Font("JetBrains Mono", Font.PLAIN, 11)
                    foreground = JBColor(Color(0xBAC2DE), Color(0xBAC2DE))
                    maximumSize = Dimension(Int.MAX_VALUE, 18)
                },
            )
            recentPanel.add(Box.createVerticalStrut(3))
        }
    }

    private fun updateSecrets(stats: WorkspaceStats) {
        secretsPanel.removeAll()
        if (stats.excludedFilesList.isEmpty()) {
            secretsPanel.add(dimLabel("✅ No secrets detected"))
            return
        }
        stats.excludedFilesList.forEach { ef ->
            val sev =
                when (ef.severity) {
                    com.tokenslayer.types.SecretsScanResult.Severity.HIGH -> "🔴 HIGH"
                    com.tokenslayer.types.SecretsScanResult.Severity.MEDIUM -> "🟡 MED"
                    else -> "🟢 LOW"
                }
            val name = ef.filePath.split("/", "\\").last()
            secretsPanel.add(
                JBLabel("$sev  $name").apply {
                    font = Font("JetBrains Mono", Font.PLAIN, 11)
                    foreground = JBColor(Color(0xF38BA8), Color(0xF38BA8))
                    maximumSize = Dimension(Int.MAX_VALUE, 18)
                },
            )
            secretsPanel.add(Box.createVerticalStrut(3))
        }
    }

    private fun dimLabel(text: String) =
        JBLabel(text).apply {
            font = Font(font.name, Font.ITALIC, 11)
            foreground = JBColor(Color(0x6C7086), Color(0x6C7086))
        }

    private fun buildQuickSummary(stats: WorkspaceStats): String =
        buildString {
            appendLine("# ⚡ TokenSlayer Summary")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Tokens Saved | ${TokenEstimator.format(stats.totalTokensSaved)} |")
            appendLine("| Reduction | ${stats.reductionPct}% |")
            appendLine("| Files Analyzed | ${stats.filesAnalyzed} |")
            appendLine("| Cache Hit Rate | ${stats.cacheHitRate}% |")
        }
}
