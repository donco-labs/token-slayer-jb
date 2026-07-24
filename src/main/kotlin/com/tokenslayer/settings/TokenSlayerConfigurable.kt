package com.tokenslayer.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.tokenslayer.types.Verbosity
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings page displayed under Settings → Tools → TokenSlayer.
 */
class TokenSlayerConfigurable : Configurable {
    private val settings = TokenSlayerSettings.getInstance()

    private lateinit var maxFileSizeSpinner: JSpinner
    private lateinit var cacheMaxEntriesSpinner: JSpinner
    private lateinit var verbosityCombo: JComboBox<String>
    private lateinit var ignoredPathsField: JBTextField
    private lateinit var enableInlayHintsBox: JBCheckBox
    private lateinit var enableFileDecorationsBox: JBCheckBox
    private lateinit var autoAnalyzeOnOpenBox: JBCheckBox
    private lateinit var mcpPortSpinner: JSpinner

    override fun getDisplayName(): String = "TokenSlayer"

    override fun createComponent(): JComponent {
        maxFileSizeSpinner = JSpinner(SpinnerNumberModel(settings.maxFileSizeKB, 1, 10_000, 50))
        cacheMaxEntriesSpinner = JSpinner(SpinnerNumberModel(settings.cacheMaxEntries, 10, 5_000, 50))
        verbosityCombo =
            JComboBox(arrayOf("minimal", "standard", "detailed")).apply {
                selectedItem = settings.verbosity.label
            }
        ignoredPathsField = JBTextField(settings.ignoredPaths.joinToString(", "))
        enableInlayHintsBox = JBCheckBox("Show ⚡ inlay hints above classes and functions", settings.enableInlayHints)
        enableFileDecorationsBox = JBCheckBox("Show reduction badges on Project tree file nodes", settings.enableFileDecorations)
        autoAnalyzeOnOpenBox = JBCheckBox("Auto-analyze files when opened or saved", settings.autoAnalyzeOnOpen)
        mcpPortSpinner = JSpinner(SpinnerNumberModel(settings.mcpServerPort, 1024, 65_535, 1))

        val mcpServer = com.tokenslayer.copilot.TokenSlayerMcpServer.getInstance()
        val mcpUrl =
            if (mcpServer.serverPort != 0) {
                mcpServer.getServerUrl()
            } else {
                "http://localhost:${settings.mcpServerPort}/mcp (starting…)"
            }

        return panel {
            group("Analysis") {
                row("Max file size (KB):") {
                    cell(maxFileSizeSpinner)
                    comment("Files larger than this limit are skipped")
                }
                row("Cache max entries:") {
                    cell(cacheMaxEntriesSpinner)
                    comment("Maximum number of skeletons kept in memory and on disk")
                }
                row("Verbosity:") {
                    cell(verbosityCombo)
                    comment("minimal = classes+methods only | standard = + properties | detailed = + variables")
                }
                row("Ignored paths:") {
                    cell(ignoredPathsField).resizableColumn()
                    comment("Comma-separated path fragments to skip (e.g. node_modules, build, dist)")
                }
            }

            group("Editor") {
                row { cell(enableInlayHintsBox) }
                row { cell(enableFileDecorationsBox) }
                row { cell(autoAnalyzeOnOpenBox) }
            }

            group("GitHub Copilot (MCP)") {
                row("Server port:") {
                    cell(mcpPortSpinner)
                    comment("Stable local port for the embedded MCP server (restart the IDE to apply a change)")
                }
                row("Server URL:") {
                    label(mcpUrl)
                }
                row {
                    comment(
                        "To let Copilot call TokenSlayer automatically, add the snippet below to " +
                            "<code>~/.config/github-copilot/intellij/mcp.json</code>, then reload MCP servers in Copilot. " +
                            "(This is GitHub Copilot's global config file — TokenSlayer does not modify it for you.)",
                    )
                }
                row {
                    button("Copy Copilot mcp.json snippet") {
                        val snippet = com.tokenslayer.copilot.TokenSlayerMcpServer.getInstance().getCopilotConfigSnippet()
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(snippet), null)
                    }
                }
            }
        }
    }

    override fun isModified(): Boolean {
        return (maxFileSizeSpinner.value as? Int ?: settings.maxFileSizeKB) != settings.maxFileSizeKB ||
            (cacheMaxEntriesSpinner.value as? Int ?: settings.cacheMaxEntries) != settings.cacheMaxEntries ||
            (verbosityCombo.selectedItem as? String ?: settings.verbosity.label) != settings.verbosity.label ||
            ignoredPathsField.text != settings.ignoredPaths.joinToString(", ") ||
            enableInlayHintsBox.isSelected != settings.enableInlayHints ||
            enableFileDecorationsBox.isSelected != settings.enableFileDecorations ||
            autoAnalyzeOnOpenBox.isSelected != settings.autoAnalyzeOnOpen ||
            (mcpPortSpinner.value as? Int ?: settings.mcpServerPort) != settings.mcpServerPort
    }

    override fun apply() {
        settings.maxFileSizeKB = maxFileSizeSpinner.value as? Int ?: return
        settings.cacheMaxEntries = cacheMaxEntriesSpinner.value as? Int ?: return
        settings.verbosity = Verbosity.from(verbosityCombo.selectedItem as? String ?: return)
        settings.ignoredPaths = ignoredPathsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        settings.enableInlayHints = enableInlayHintsBox.isSelected
        settings.enableFileDecorations = enableFileDecorationsBox.isSelected
        settings.autoAnalyzeOnOpen = autoAnalyzeOnOpenBox.isSelected
        settings.mcpServerPort = mcpPortSpinner.value as? Int ?: settings.mcpServerPort
    }

    override fun reset() {
        maxFileSizeSpinner.value = settings.maxFileSizeKB
        cacheMaxEntriesSpinner.value = settings.cacheMaxEntries
        verbosityCombo.selectedItem = settings.verbosity.label
        ignoredPathsField.text = settings.ignoredPaths.joinToString(", ")
        enableInlayHintsBox.isSelected = settings.enableInlayHints
        enableFileDecorationsBox.isSelected = settings.enableFileDecorations
        autoAnalyzeOnOpenBox.isSelected = settings.autoAnalyzeOnOpen
        mcpPortSpinner.value = settings.mcpServerPort
    }
}
