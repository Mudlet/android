package org.mudlet.android.about

/** What a stable release says is new: its [version] and the lines of its section, as Google Play shows them */
data class ReleaseNotes(val version: String, val lines: List<String>) {
    companion object {
        /** docs/release-notes.md, which the build copies into the APK's assets */
        const val ASSET = "release-notes.md"

        // The heading scripts/release-plan.sh takes a release's notes from; the file's "## x.y.z" example is not one
        private val heading = Regex("""^## (.+)$""")

        /**
         * Each release's section of [text], in the file's order (the newest first): the lines between its heading and
         * the next "## ", without blank lines, as scripts/release-plan.sh cuts them for Play.
         */
        fun parse(text: String): List<ReleaseNotes> {
            val releases = ArrayList<ReleaseNotes>()
            var version: String? = null
            var lines = ArrayList<String>()
            fun end() {
                version?.let { releases += ReleaseNotes(it, lines) }
                version = null
                lines = ArrayList()
            }
            for (line in text.lines()) {
                if (line.startsWith("## ")) {
                    end()
                    version = heading.matchEntire(line)?.groupValues?.get(1)
                } else if (version != null && line.isNotBlank()) {
                    lines.add(line.trim())
                }
            }
            end()
            return releases
        }
    }
}
