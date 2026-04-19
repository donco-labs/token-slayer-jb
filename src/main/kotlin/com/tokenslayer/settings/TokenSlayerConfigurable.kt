package com.tokenslayer.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.tokenslayer.types.Verbosity
import javax.swing.JComponent
import javax.swing.JComboBox
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

    override fun getDisplayName(): String = "TokenSlayer"

    override fun createComponent(): JComponent {
        maxFileSizeSpinner = JSpinner(SpinnerNumberModel(settings.maxFileSizeKB, 1, 10_000, 50))
        cacheMaxEntriesSpinner = JSpinner(SpinnerNumberModel(settings.cacheMaxEntries, 10, 5_000, 50))
        verbosityCombo = JComboBox(arrayOf("minimal", "standard", "detailed")).apply {
            selectedItem = settings.verbosity.label
        }
        ignoredPathsField = JBTextField(settings.ignoredPaths.joinToString(", "))
        enableInlayHintsBox = JBCheckBox("Show ⚡ inlay hints above classes and functions", settings.enableInlayHints)
        enableFileDecorationsBox = JBCheckBox("Show reduction badges on Project tree file nodes", settings.enableFileDecorations)
        autoAnalyzeOnOpenBox = JBCheckBox("Auto-analyze files when opened or saved", settings.autoAnalyzeOnOpen)

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
        }
    }

    override fun isModified(): Boolean {
        return maxFileSizeSpinner.value as Int != settings.maxFileSizeKB ||
            cacheMaxEntriesSpinner.value as Int != settings.cacheMaxEntries ||
            verbosityCombo.selectedItem as String != settings.verbosity.label ||
            ignoredPathsField.text != settings.ignoredPaths.joinToString(", ") ||
            enableInlayHintsBox.isSelected != settings.enableInlayHints ||
            enableFileDecorationsBox.isSelected != settings.enableFileDecorations ||
            autoAnalyzeOnOpenBox.isSelected != settings.autoAnalyzeOnOpen
    }

    override fun apply() {
        settings.maxFileSizeKB = maxFileSizeSpinner.value as Int
        settings.cacheMaxEntries = cacheMaxEntriesSpinner.value as Int
        settings.verbosity = Verbosity.from(verbosityCombo.selectedItem as String)
        settings.ignoredPaths = ignoredPathsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        settings.enableInlayHints = enableInlayHintsBox.isSelected
        settings.enableFileDecorations = enableFileDecorationsBox.isSelected
        settings.autoAnalyzeOnOpen = autoAnalyzeOnOpenBox.isSelected
    }

    override fun reset() {
        maxFileSizeSpinner.value = settings.maxFileSizeKB
        cacheMaxEntriesSpinner.value = settings.cacheMaxEntries
        verbosityCombo.selectedItem = settings.verbosity.label
        ignoredPathsField.text = settings.ignoredPaths.joinToString(", ")
        enableInlayHintsBox.isSelected = settings.enableInlayHints
        enableFileDecorationsBox.isSelected = settings.enableFileDecorations
        autoAnalyzeOnOpenBox.isSelected = settings.autoAnalyzeOnOpen
    }
}
