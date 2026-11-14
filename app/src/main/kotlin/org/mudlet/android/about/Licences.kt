package org.mudlet.android.about

import org.json.JSONArray
import org.json.JSONObject

/** Where in the licences list a component is shown: the about screen has a heading for each */
enum class LicenceGroup { APP, NATIVE, QT, ANDROID }

/** Something the APK bundles, under [licence], an SPDX expression; [texts] are the licence texts to show for it */
data class Component(
    val id: String,
    val group: LicenceGroup,
    val name: String,
    val version: String,
    val licence: String,
    val texts: List<String>,
    val copyright: String,
    val url: String,
    val source: String,
)

/**
 * The licences of what the APK bundles, from assets/licences (docs/licences.md): notices.json, written by hand, with
 * the native libraries and the engine; qt-third-party.json, the code from others inside Qt; android.json, the Gradle
 * runtime dependencies, which the build writes. Each lists components; [forEngine] keeps those the APK's engine has.
 */
object Licences {
    const val DIR = "licences"
    val FILES = listOf("notices.json", "qt-third-party.json", "android.json")
    private val GROUP_OF_FILE = mapOf("qt-third-party.json" to LicenceGroup.QT, "android.json" to LicenceGroup.ANDROID)

    /** The identifiers an SPDX expression names, which are also the names of their texts: "MIT AND (A OR B)" is MIT, A, B */
    fun licenceIds(expression: String): List<String> = Regex("[A-Za-z0-9.+-]+").findAll(expression).map { it.value }
        .filter { it !in setOf("AND", "OR", "WITH") }.distinct().toList()

    /**
     * The components of [files] (file name to its JSON) that the APK of [engine] ("standin" or "mudlet") carries, in
     * group order, sorted by name within a group. Throws on a component that lacks an id, a name or a licence, as
     * scripts/check-licences.py would have.
     */
    fun parse(files: Map<String, String>, engine: String): List<Component> = files.flatMap { (file, json) ->
        val array: JSONArray = JSONObject(json).getJSONArray("components")
        (0 until array.length()).map { array.getJSONObject(it) }
            .filter { o -> o.optJSONArray("engines")?.let { a -> (0 until a.length()).any { a.getString(it) == engine } } ?: true }
            .map { o -> component(o, GROUP_OF_FILE[file]) }
    }.sortedWith(compareBy<Component> { it.group }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    private fun component(o: JSONObject, group: LicenceGroup?): Component {
        val licence = o.getString("licence").also { require(it.isNotBlank()) { "${o.optString("id")} has no licence" } }
        val extra = o.optJSONArray("texts")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return Component(
            id = o.getString("id"),
            group = group ?: LicenceGroup.valueOf(o.getString("group").uppercase()),
            name = o.getString("name"),
            version = o.optString("version"),
            licence = licence,
            texts = (licenceIds(licence) + extra).distinct(),
            copyright = o.optString("copyright"),
            url = o.optString("url"),
            source = o.optString("source"),
        )
    }

    fun textPath(id: String) = "$DIR/texts/$id.txt"

    /** A licence text's paragraphs: its blank-line-separated blocks, each with its hard line breaks joined, to wrap to the screen */
    fun paragraphs(text: String): List<String> = text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n")).map { block -> block.lines().joinToString(" ") { it.trim() }.trim() }
        .filter { it.isNotEmpty() }
}
