package org.mudlet.android.ui.settings

import java.text.Collator
import java.util.Locale

/** A language the app can be shown in: its BCP 47 [tag] and its [name] in that language, as the language list shows it. */
data class AppLanguageChoice(val tag: String, val name: String)

/**
 * The languages the app is translated into. app/build.gradle.kts lists the `values-<qualifiers>` folders that hold a
 * translated string in BuildConfig.TRANSLATIONS, the same folders its GenerateLocaleConfig builds Android's per-app language
 * list from, so the two lists match (AppLanguageTest checks it on Android 13 and later).
 */
object AppLanguages {
    private val language = Regex("[a-z]{2,3}")
    private val region = Regex("r([A-Z]{2}|[0-9]{3})")
    private val mobileNetwork = Regex("(mcc|mnc)[0-9]+")

    // Android's resource folders keep Java's old codes, which Locale.toLanguageTag() and LocaleConfig give as these
    private val modernCodes = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /**
     * The BCP 47 tag of the locale a values folder's [qualifiers] name ("de-rDE" is "de-DE", "b+sr+Latn" is
     * "sr-Latn"), or null for a folder that names none ("night", "v31").
     */
    fun tagOf(qualifiers: String): String? {
        val first = qualifiers.split('-').dropWhile { mobileNetwork.matches(it) }
        val head = first.firstOrNull() ?: return null
        val parts = if (head.startsWith("b+")) {
            head.removePrefix("b+").split('+')
        } else {
            listOfNotNull(head, first.getOrNull(1)?.takeIf { region.matches(it) }?.drop(1))
        }
        if (!language.matches(parts[0])) return null
        return (listOf(modernCodes[parts[0]] ?: parts[0]) + parts.drop(1)).joinToString("-")
    }

    /**
     * The tags of the English's own locale, [default], and of each folder in [folders], the comma-separated
     * BuildConfig.TRANSLATIONS, each once.
     */
    fun tags(default: String, folders: String): List<String> = (listOf(default) + folders.split(',').filter { it.isNotBlank() }.mapNotNull { tagOf(it.trim()) }).distinct()

    /**
     * Each of [tags] named in its own language, in the order of those names: only the language ("Deutsch"), unless
     * another tag is the same language too ("Português (Brasil)", "Português (Portugal)").
     */
    fun choices(tags: List<String>): List<AppLanguageChoice> {
        val locales = tags.map { Locale.forLanguageTag(it) }
        val shared = locales.groupingBy { it.language }.eachCount()
        val named = tags.zip(locales) { tag, locale ->
            val name = if (shared.getValue(locale.language) > 1) locale.getDisplayName(locale) else locale.getDisplayLanguage(locale)
            // A language Java has no name for gives its code back
            AppLanguageChoice(tag, if (name.isBlank() || name == locale.language) tag else name.replaceFirstChar { it.titlecase(locale) })
        }
        val collator = Collator.getInstance(Locale.ROOT)
        return named.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
}
