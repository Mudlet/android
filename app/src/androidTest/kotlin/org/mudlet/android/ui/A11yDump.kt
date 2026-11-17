package org.mudlet.android.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Diagnostics only: a failed accessibility check is thrown again with the nodes at and around what it named. */
class A11yDump(private val compose: () -> ComposeTestRule) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } catch (e: Throwable) {
                val message = generateSequence(e) { it.cause }.mapNotNull { it.message }.firstOrNull { "accessibility result" in it } ?: throw e
                val dump = try {
                    dump(message)
                } catch (t: Throwable) {
                    "no dump: $t"
                }
                throw AssertionError("DUMP $dump || ${message.replace('\n', ' ').take(500)}", e)
            }
        }
    }

    private fun dump(message: String): String {
        val rects = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").findAll(message).map { m -> m.groupValues.drop(1).map(String::toInt) }.toList()
        val out = StringBuilder()
        fun visit(node: SemanticsNode, depth: Int) {
            val b = node.boundsInWindow
            val near = rects.any { (l, t, r, bottom) -> b.left <= r + 2 && b.right >= l - 2 && b.top <= bottom + 2 && b.bottom >= t - 2 }
            if (near) {
                val c = node.config
                val flags = buildString {
                    if (c.contains(SemanticsActions.OnClick)) append('C')
                    if (c.contains(SemanticsActions.ScrollBy)) append('S')
                    if (c.contains(SemanticsProperties.LiveRegion)) append('L')
                    if (c.contains(SemanticsActions.Dismiss)) append('D')
                    if (c.isMergingSemanticsOfDescendants) append('M')
                    if (c.contains(SemanticsActions.SetText)) append('E')
                }
                val text = (c.getOrNull(SemanticsProperties.Text)?.joinToString("/") { it.text }.orEmpty() + "~" + c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("/").orEmpty()).take(24)
                val pos = node.positionInWindow
                out.append("$depth{${b.left.toInt()},${b.top.toInt()},${b.right.toInt()},${b.bottom.toInt()}|${node.size.width}x${node.size.height}@${pos.x.toInt()},${pos.y.toInt()} $flags ${c.getOrNull(SemanticsProperties.TestTag).orEmpty()} $text} ")
            }
            node.children.forEach { visit(it, depth + 1) }
        }
        val roots = compose().onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        for (root in roots) {
            out.append("ROOT ")
            visit(root, 0)
        }
        return out.toString().take(3200)
    }
}
