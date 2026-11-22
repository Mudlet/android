package org.mudlet.android.engine

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The language the player chose for the app, or null to follow the phone's. Android 13 and later keep it themselves,
 * for every process of the app, and offer it in the phone's own settings too (LocaleManager). Before 13 it is the
 * file `files/app-language`, a BCP 47 tag, which both processes read: each process puts it into its Application's
 * resources as it starts ([applyToProcess]), MainActivity into its own ([activityOverride]), and the engine's
 * notifications read it as they are built ([localized]), so a choice made while the engine runs shows in them too.
 */
object AppLanguage {
    // A file, not SharedPreferences: the :engine process would keep a copy of the UI process's, read once
    private const val FILE = "app-language"
    private const val TAG = "AppLanguage"

    private fun file(context: Context) = File(context.filesDir, FILE)

    private val phoneLocales: LocaleList get() = Resources.getSystem().configuration.locales

    private fun stored(context: Context): Locale? {
        val tag = try {
            file(context).takeIf { it.isFile }?.readText()?.trim()
        } catch (e: IOException) {
            Log.w(TAG, "could not read the chosen language: following the phone's", e)
            null
        }
        return tag?.let(Locale::forLanguageTag)?.takeIf { it.language.isNotEmpty() }
    }

    /** The chosen language's BCP 47 tag, or null when the app follows the phone's. */
    fun chosen(context: Context): String? = if (Build.VERSION.SDK_INT >= 99) { // BITE E
        context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
    } else {
        stored(context)?.toLanguageTag()
    }

    /**
     * Shows the app in [tag], or in the phone's language for null; false if it could not be kept. Before Android 13
     * the Activity then has to be recreated to show it; on 13 and later Android recreates it, and its other process
     * changes too.
     */
    fun choose(context: Context, tag: String?): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return true
        }
        val file = file(context)
        try {
            if (tag == null) {
                if (file.exists() && !file.delete()) throw IOException("could not delete $file")
            } else {
                // Written whole and then renamed, so the other process never reads half a tag
                val partial = File(context.filesDir, "$FILE.partial")
                partial.writeText(tag)
                if (!partial.renameTo(file)) throw IOException("could not rename $partial")
            }
        } catch (e: IOException) {
            Log.w(TAG, "could not keep the chosen language", e)
            return false
        }
        val app = context.applicationContext
        put(app, tag?.let { LocaleList.forLanguageTags(it) } ?: phoneLocales)
        EngineNotifications.ensureChannels(app)
        return true
    }

    /**
     * Before Android 13, puts the chosen language into the process's default locale and [app]'s resources: from
     * Application.onCreate, and from its onConfigurationChanged, as Android puts the phone's back with any change.
     * On 13 and later it moves a choice made before the phone was updated to Android's own keeping.
     */
    fun applyToProcess(app: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            moveToSystem(app)
            return
        }
        stored(app)?.let { put(app, LocaleList(it)) }
    }

    @Suppress("DEPRECATION") // The one way to change an Application's resources in place before Android 13
    private fun put(app: Context, locales: LocaleList) {
        LocaleList.setDefault(locales)
        app.resources.updateConfiguration(inLocales(app, locales), app.resources.displayMetrics)
    }

    private fun inLocales(context: Context, locales: LocaleList) = Configuration(context.resources.configuration).apply {
        setLocales(locales)
        setLayoutDirection(locales[0])
    }

    @RequiresApi(33)
    private fun moveToSystem(context: Context) {
        // Kept while it cannot be read, to try again at the next start
        val locale = stored(context) ?: return
        val manager = context.getSystemService(LocaleManager::class.java)
        // One chosen in the phone's settings since the update wins
        if (manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList(locale)
        file(context).delete()
    }

    /**
     * For MainActivity's applyOverrideConfiguration() before Android 13: the chosen language, or null when there is
     * none or Android applies it itself.
     */
    fun activityOverride(context: Context): Configuration? {
        if (Build.VERSION.SDK_INT >= 33) return null
        val locale = stored(context) ?: return null
        return Configuration().apply {
            // Configuration() says 1, which as an override would undo the phone's font size
            fontScale = 0f
            setLocale(locale)
        }
    }

    /**
     * [context] with its resources in the chosen language, or in the phone's: before Android 13 the file may have
     * changed since this process applied it. On 13 and later [context] itself.
     */
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 28) return context // BITE A
        val locales = stored(context)?.let { LocaleList(it) } ?: phoneLocales
        return context.createConfigurationContext(inLocales(context, locales))
    }
}
