package org.mudlet.android.about

import org.junit.Assert.assertEquals
import org.junit.Test

/** The parser of the release notes the changelog shows */
class ReleaseNotesTest {
    @Test
    fun eachVersionsSectionIsItsLinesUpToTheNextHeading_newestFirst() {
        val text = """
            # Release notes

            Intro text, not a release.

            ```text
            ## x.y.z

            - What a player will notice first.
            ```

            ## 1.1.0

            - Second release.
              - Indented.

            ## 1.0.0
            - First release.
        """.trimIndent()
        assertEquals(
            listOf(
                ReleaseNotes("1.1.0", listOf("- Second release.", "- Indented.")),
                ReleaseNotes("1.0.0", listOf("- First release.")),
            ),
            ReleaseNotes.parse(text),
        )
    }
}
