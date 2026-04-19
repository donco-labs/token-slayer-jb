package com.tokenslayer.settings

import com.intellij.openapi.components.*
import com.tokenslayer.types.Verbosity

/**
 * Application-level persistent settings for TokenSlayer.
 * Stored in the IDE's config directory (not roamed).
 */
@Service(Service.Level.APP)
@State(
    name = "TokenSlayerSettings",
    storages = [Storage("tokenslayer.xml", roamingType = RoamingType.DISABLED)],
)
class TokenSlayerSettings : PersistentStateComponent<TokenSlayerSettings.State> {
    data class State(
        var maxFileSizeKB: Int = 500,
        var cacheMaxEntries: Int = 500,
        var verbosityLevel: String = Verbosity.STANDARD.label,
        var ignoredPaths: MutableList<String> =
            mutableListOf(
                "node_modules",
                ".git",
                "build",
                "dist",
                "out",
                ".gradle",
                "target",
                ".idea",
                ".vscode",
                "__pycache__",
            ),
        var enableInlayHints: Boolean = true,
        var enableFileDecorations: Boolean = true,
        var autoAnalyzeOnOpen: Boolean = true,
    )

    private var state = State()

    var maxFileSizeKB: Int
        get() = state.maxFileSizeKB
        set(value) {
            state.maxFileSizeKB = value.coerceIn(1, 10_000)
        }

    var cacheMaxEntries: Int
        get() = state.cacheMaxEntries
        set(value) {
            state.cacheMaxEntries = value.coerceIn(10, 5_000)
        }

    var verbosity: Verbosity
        get() = Verbosity.from(state.verbosityLevel)
        set(value) {
            state.verbosityLevel = value.label
        }

    var ignoredPaths: List<String>
        get() = state.ignoredPaths
        set(value) {
            state.ignoredPaths = value.toMutableList()
        }

    var enableInlayHints: Boolean
        get() = state.enableInlayHints
        set(value) {
            state.enableInlayHints = value
        }

    var enableFileDecorations: Boolean
        get() = state.enableFileDecorations
        set(value) {
            state.enableFileDecorations = value
        }

    var autoAnalyzeOnOpen: Boolean
        get() = state.autoAnalyzeOnOpen
        set(value) {
            state.autoAnalyzeOnOpen = value
        }

    override fun getState(): State = state.copy(ignoredPaths = state.ignoredPaths.toMutableList())

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): TokenSlayerSettings = service()
    }
}
