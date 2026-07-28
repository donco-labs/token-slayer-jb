package com.tokenslayer.ui.dashboard

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.tokenslayer.actions.ExportReportAction
import com.tokenslayer.services.TokenSlayerService
import com.tokenslayer.types.WorkspaceStats
import com.tokenslayer.ui.TokenSlayerColors
import com.tokenslayer.ui.WrapLayout
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
    private val tsService = TokenSlayerService.getInstance(project)

    // ── Stat labels ───────────────────────────────────────────────────────────
    private val heroLabel = JBLabel("0", SwingConstants.CENTER)
    private val reductionLabel = JBLabel("0%")
    private val filesLabel = JBLabel("0")
    private val cacheHitLabel = JBLabel("0%")
    private val cachedEntriesLabel = JBLabel("0")
    private val excludedCountLabel = JBLabel("0")

    /**
     * Live analysis status. A Task.Backgroundable only reports into the status-bar widget of its
     * own project frame, so a startup scan was invisible if that window wasn't focused. Mirroring
     * the state here puts it where the user is already looking.
     */
    private val statusLabel = JBLabel("", SwingConstants.CENTER)

    // ── Content panels ────────────────────────────────────────────────────────
    private val langPanel = JPanel()
    private val topSaversPanel = JPanel()
    private val recentPanel = JPanel()
    private val secretsPanel = JPanel()
    private var refreshTask: java.util.concurrent.ScheduledFuture<*>? = null

    init {
        background = TokenSlayerColors.panelBackground
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

        add(
            JBScrollPane(content).apply {
                border = null
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )

        // Pinned footer. These were previously the last item inside the scrolled content, so on
        // any project with a few sections' worth of data they were only reachable by scrolling
        // to the very bottom. Only the stats scroll now; the actions stay put.
        add(buildActionButtons(), BorderLayout.SOUTH)
    }

    // ── Hero section ──────────────────────────────────────────────────────────

    private fun buildHeroSection(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        heroLabel.apply {
            font = Font("JetBrains Mono", Font.BOLD, 36)
            foreground = TokenSlayerColors.SKY
        }

        val titleLabel =
            JBLabel("⚡ Tokens Slayed", SwingConstants.CENTER).apply {
                font = Font(font.name, Font.PLAIN, 11)
                foreground = TokenSlayerColors.subtext
            }

        statusLabel.apply {
            font = Font(font.name, Font.PLAIN, 11)
            foreground = TokenSlayerColors.YELLOW
            isVisible = false
        }

        val inner = JPanel(GridLayout(3, 1))
        inner.isOpaque = false
        inner.add(heroLabel)
        inner.add(titleLabel)
        inner.add(statusLabel)
        panel.add(inner, BorderLayout.CENTER)
        return panel
    }

    // ── Stats grid ────────────────────────────────────────────────────────────

    private fun buildStatsGrid(): JPanel {
        val grid = JPanel(GridLayout(2, 3, 8, 8))
        grid.isOpaque = false

        grid.add(statCard("Reduction", reductionLabel, TokenSlayerColors.GREEN))
        grid.add(statCard("Files", filesLabel, TokenSlayerColors.SKY))
        grid.add(statCard("Cache Hit", cacheHitLabel, TokenSlayerColors.YELLOW))
        grid.add(statCard("Cached", cachedEntriesLabel, TokenSlayerColors.MAUVE))
        grid.add(statCard("Excluded", excludedCountLabel, TokenSlayerColors.RED))
        grid.add(
            statCard(
                "MCP Server",
                JBLabel("●").apply {
                    foreground = TokenSlayerColors.GREEN
                },
                TokenSlayerColors.GREEN,
            ),
        )

        return grid
    }

    private fun statCard(
        title: String,
        valueLabel: JComponent,
        accent: JBColor,
    ): JPanel {
        val card = JPanel(BorderLayout(0, 2))
        card.border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TokenSlayerColors.border),
                EmptyBorder(8, 8, 8, 8),
            )
        card.background = TokenSlayerColors.cardBackground

        val titleLbl =
            JBLabel(title).apply {
                font = Font(font.name, Font.PLAIN, 10)
                foreground = TokenSlayerColors.subtext
            }
        if (valueLabel is JBLabel) {
            valueLabel.font = Font("JetBrains Mono", Font.BOLD, 16)
            valueLabel.foreground = accent
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
                foreground = TokenSlayerColors.text
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
        // WrapLayout, not FlowLayout: FlowLayout reports a single-row preferred size, so in a
        // narrow tool window the buttons that didn't fit were clipped away one at a time.
        // Wrapping keeps all three reachable at any width.
        val panel = JPanel(WrapLayout(FlowLayout.LEFT, 8, 4))
        panel.isOpaque = false
        panel.border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, TokenSlayerColors.border),
                EmptyBorder(8, 0, 0, 0),
            )

        val analyzeBtn =
            JButton("🔄 Analyze Workspace").apply {
                addActionListener { _: ActionEvent ->
                    com.tokenslayer.services.ProjectAnalyzerService.getInstance(project)
                        .analyzeAll(notifyOnComplete = true) { refresh() }
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
        refreshTask =
            AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
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
        updateAnalysisStatus()
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

    private fun updateAnalysisStatus() {
        val analyzer = com.tokenslayer.services.ProjectAnalyzerService.getInstance(project)
        if (!analyzer.isAnalyzing) {
            statusLabel.isVisible = false
            return
        }
        val total = analyzer.progressTotal
        statusLabel.text =
            if (total > 0) {
                "⏳ Analyzing… ${analyzer.progressProcessed} / $total files"
            } else {
                "⏳ Scanning project files…"
            }
        statusLabel.isVisible = true
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
                    foreground = TokenSlayerColors.text
                },
                BorderLayout.WEST,
            )
            val bar =
                JProgressBar(0, 100).apply {
                    value = ls.reductionPct
                    isStringPainted = false
                    background = TokenSlayerColors.track
                    foreground = TokenSlayerColors.SKY
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
                    foreground = TokenSlayerColors.text
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
                    foreground = TokenSlayerColors.subtext
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
                    foreground = TokenSlayerColors.RED
                    maximumSize = Dimension(Int.MAX_VALUE, 18)
                },
            )
            secretsPanel.add(Box.createVerticalStrut(3))
        }
    }

    private fun dimLabel(text: String) =
        JBLabel(text).apply {
            font = Font(font.name, Font.ITALIC, 11)
            foreground = TokenSlayerColors.dim
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
