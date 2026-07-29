package com.tokenslayer.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * A [FlowLayout] that reports a preferred size accounting for wrapped rows.
 *
 * Plain FlowLayout *positions* components on multiple rows, but its preferred size is always
 * computed as a single row. In a container that honours that preferred size — such as the
 * dashboard's footer inside a narrow tool window — the extra rows fall outside the allotted
 * height and the surplus buttons simply vanish one by one as the window narrows. Overriding the
 * size calculation to measure the wrapped layout keeps every component reachable at any width.
 */
class WrapLayout(
    align: Int = LEFT,
    hgap: Int = 5,
    vgap: Int = 5,
) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension = layoutSize(target, preferred = false).also { it.width -= (hgap + 1) }

    private fun layoutSize(
        target: Container,
        preferred: Boolean,
    ): Dimension {
        synchronized(target.treeLock) {
            // Before the first layout pass the width is 0; treat that as unbounded so the
            // initial preferred size matches plain FlowLayout instead of stacking everything.
            val targetWidth = if (target.size.width > 0) target.size.width else Int.MAX_VALUE
            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
            val maxWidth = targetWidth - horizontalInsetsAndGap

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (i in 0 until target.componentCount) {
                val m: Component = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize

                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    addRow(dim, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
            addRow(dim, rowWidth, rowHeight)

            dim.width += horizontalInsetsAndGap
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }

    private fun addRow(
        dim: Dimension,
        rowWidth: Int,
        rowHeight: Int,
    ) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) dim.height += vgap
        dim.height += rowHeight
    }
}
