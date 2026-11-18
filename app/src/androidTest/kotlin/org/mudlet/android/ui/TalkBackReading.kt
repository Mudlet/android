package org.mudlet.android.ui

import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.ViewCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** The screen's nodes as TalkBack moves through them now: see [traversalOrder]. */
class TalkBackReading(private val compose: AndroidComposeTestRule<*, MainActivity>) {
    val nodes = compose.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes().associateBy { it.id }
    val order = traversalOrder(nodes.values.filter { it.parent != null }.map { it.id })
    val read = order.map { describe(nodes.getValue(it)) }

    /**
     * Where each of [expected] is read, which is in that order, each in its own place: its own node's place, or for one
     * TalkBack does not stop at, the first related node's.
     */
    fun assertInOrder(expected: List<Pair<String, SemanticsMatcher>>): List<Pair<String, Int>> {
        val places = expected.map { (name, matcher) ->
            val target = compose.onNode(matcher).fetchSemanticsNode().id
            name to (order.indexOf(target).takeIf { it >= 0 } ?: order.indexOfFirst { related(it, target) })
        }
        // Each in its own place, so no node they are all within stands in for them
        val at = places.map { it.second }
        assertTrue("each is read, in its own place: $places; TalkBack reads $read", at.all { it >= 0 } && at.distinct() == at)
        assertEquals("TalkBack reads $read", places, places.sortedBy { it.second })
        return places
    }

    /**
     * Whether TalkBack reads [matcher]'s node now: it or a node within it, not only a node it is within, as for one
     * scrolled out of view, which TalkBack reaches only by scrolling to it.
     */
    fun reads(matcher: SemanticsMatcher): Boolean {
        val target = compose.onNode(matcher).fetchSemanticsNode().id
        return order.any { it == target || target in nodes.getValue(it).ancestorIds() }
    }

    // Every control is reached by swiping, none by touch alone: here, or in [others], of the screen scrolled elsewhere
    fun assertEveryControlRead(others: List<TalkBackReading> = emptyList()) {
        val readings = listOf(this) + others
        for (control in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            assertTrue("${describe(control)} is read: TalkBack reads ${readings.map { it.read }}", readings.any { r -> r.order.any { r.related(it, control.id) } })
        }
    }

    /**
     * The nodes [ids] as TalkBack moves through them: each node's traversalBefore, which Compose works out only while
     * an accessibility service is on, so its switch for its own tests turns that on here, and gives a test through an
     * extra of the node's AccessibilityNodeInfo. The switch is off again before the next frame, as Compose would
     * otherwise send events the system refuses with no service on.
     */
    private fun traversalOrder(ids: List<Int>): List<Int> {
        val next = compose.runOnUiThread {
            // setContent's ComposeView, and in it the view that holds the semantics
            val view = (compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)
            val delegate = checkNotNull(ViewCompat.getAccessibilityDelegate(view)) { "no accessibility delegate on $view" }
            val forceEnabled = checkNotNull(delegate.javaClass.methods.firstOrNull { it.name.startsWith(FORCE_ENABLED_SETTER) }) {
                "Compose's ${delegate.javaClass.name} has no $FORCE_ENABLED_SETTER"
            }
            forceEnabled.invoke(delegate, true)
            try {
                val provider = checkNotNull(view.accessibilityNodeProvider) { "no accessibility node provider on $view" }
                ids.mapNotNull { id ->
                    val info = provider.createAccessibilityNodeInfo(id) ?: return@mapNotNull null
                    provider.addExtraDataToAccessibilityNodeInfo(id, info, TRAVERSAL_BEFORE, null)
                    if (info.extras.containsKey(TRAVERSAL_BEFORE)) id to info.extras.getInt(TRAVERSAL_BEFORE) else null
                }.toMap()
            } finally {
                forceEnabled.invoke(delegate, false)
            }
        }
        val first = next.keys - next.values.toSet()
        assertEquals("one node TalkBack starts at, of the order $next", 1, first.size)
        val order = generateSequence(first.single()) { next[it] }.take(next.size + 1).toList()
        assertEquals("one chain through every node, without a loop, of $next", next.size + 1, order.distinct().size)
        return order
    }

    // The same node, the one it is read within, or one read within it
    private fun related(a: Int, b: Int): Boolean = a == b || b in nodes.getValue(a).ancestorIds() || a in nodes.getValue(b).ancestorIds()

    private fun describe(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        return "#${node.id} ${text ?: node.config.getOrNull(SemanticsProperties.EditableText) ?: ""}"
    }

    private companion object {
        // Compose's switch for its tests (internal, so its setter's name carries the module's), and the extra it gives
        // a node's traversalBefore in for them
        const val FORCE_ENABLED_SETTER = "setAccessibilityForceEnabledForTesting"
        const val TRAVERSAL_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"
    }
}

fun SemanticsNode.ancestorIds(): List<Int> = generateSequence(parent) { it.parent }.map { it.id }.toList()
