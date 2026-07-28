package com.tokenslayer.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Regression cover for the dashboard footer losing buttons as the tool window narrowed.
 *
 * Plain FlowLayout always reports a single-row preferred size, so a container that honours it
 * clipped the components that had wrapped onto later rows — they disappeared one by one. These
 * tests pin the property that actually matters: the reported height grows to accommodate every
 * child instead of the surplus being dropped.
 *
 * Fixed child sizes are used rather than real JButtons so the assertions don't depend on the
 * platform's font metrics.
 */
class WrapLayoutTest {
    private val childWidth = 100
    private val childHeight = 20
    private val hgap = 8
    private val vgap = 4

    private fun child(): JComponent =
        JPanel().apply {
            preferredSize = Dimension(childWidth, childHeight)
            minimumSize = Dimension(childWidth, childHeight)
        }

    private fun panel(
        children: Int,
        width: Int,
    ): JPanel {
        val p = JPanel(WrapLayout(FlowLayout.LEFT, hgap, vgap))
        repeat(children) { p.add(child()) }
        // Height is irrelevant to the calculation; width is what drives wrapping.
        p.setSize(width, 500)
        return p
    }

    /** Rows implied by a reported height, given uniform child heights. */
    private fun rowsIn(height: Int): Int {
        val usable = height - vgap * 2
        return (usable + vgap) / (childHeight + vgap)
    }

    @Test fun `lays out on one row when there is room for all children`() {
        val p = panel(children = 3, width = 1000)
        assertEquals(1, rowsIn(p.preferredSize.height), "three narrow children should fit one row")
    }

    @Test fun `wraps onto a second row when width fits only two children`() {
        // Two children plus the gap need 208px; a 250px container cannot take a third.
        val p = panel(children = 3, width = 250)
        assertEquals(2, rowsIn(p.preferredSize.height), "third child should wrap, not vanish")
    }

    @Test fun `stacks every child when width fits only one`() {
        val p = panel(children = 3, width = 130)
        assertEquals(3, rowsIn(p.preferredSize.height), "all three should still be accounted for")
    }

    @Test fun `reported height grows monotonically as width shrinks`() {
        val wide = panel(children = 3, width = 1000).preferredSize.height
        val medium = panel(children = 3, width = 250).preferredSize.height
        val narrow = panel(children = 3, width = 130).preferredSize.height
        assertTrue(wide < medium, "narrowing from 1000 to 250 should add a row (was $wide, $medium)")
        assertTrue(medium < narrow, "narrowing from 250 to 130 should add a row (was $medium, $narrow)")
    }

    @Test fun `a single child never wraps onto an extra row`() {
        val p = panel(children = 1, width = 20)
        assertEquals(1, rowsIn(p.preferredSize.height), "one child cannot wrap against itself")
    }

    @Test fun `treats an unmeasured container as unbounded`() {
        // Before the first layout pass width is 0. Stacking everything then would make the
        // footer claim the whole tool window on first paint.
        val p = JPanel(WrapLayout(FlowLayout.LEFT, hgap, vgap))
        repeat(3) { p.add(child()) }
        assertEquals(1, rowsIn(p.preferredSize.height), "unmeasured container should assume one row")
    }

    @Test fun `ignores invisible children`() {
        val p = JPanel(WrapLayout(FlowLayout.LEFT, hgap, vgap))
        repeat(3) { p.add(child()) }
        p.getComponent(2).isVisible = false
        p.setSize(250, 500)
        assertEquals(1, rowsIn(p.preferredSize.height), "two visible children fit one row at 250px")
    }
}
