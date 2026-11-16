package org.mudlet.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.theme.wallpaperColoursAvailable

/** Whose values a settings page shows and changes: one open game's, or the app's own. */
sealed interface SettingsOwner {
    /** Whether its values can be read and changed now: a game's once its profile has loaded. */
    val available: Boolean

    fun <T> value(setting: Settings.Setting<T>): T

    fun <T> change(setting: Settings.Setting<T>, value: T)
}

/** The game [name]'s own values, which Mudlet keeps with its profile. */
data class GameSettings(val name: String) : SettingsOwner {
    private val session get() = EngineClient.session(name)

    override val available get() = session?.settingsAvailable == true

    override fun <T> value(setting: Settings.Setting<T>): T = session?.setting(setting) ?: setting.value

    override fun <T> change(setting: Settings.Setting<T>, value: T) {
        session?.changeSetting(setting, value)
    }
}

/** The app's values: its own settings, and the game settings that games added in the app start with. */
data object AppSettings : SettingsOwner {
    override val available get() = true

    override fun <T> value(setting: Settings.Setting<T>): T = setting.value

    override fun <T> change(setting: Settings.Setting<T>, value: T) = setting.set(value)
}

/** A page of the settings: [forGame] ones hold each game's own settings, the others the app's. */
enum class SettingsCategory(val forGame: Boolean, @StringRes val title: Int, @StringRes val summary: Int, @DrawableRes val icon: Int) {
    DISPLAY(true, R.string.settings_display, R.string.settings_display_summary, R.drawable.ic_text_fields),
    ACCESSIBILITY(true, R.string.settings_accessibility, R.string.settings_accessibility_summary, R.drawable.ic_accessibility),
    INPUT(true, R.string.settings_input, R.string.settings_input_summary, R.drawable.ic_keyboard),
    CONNECTION(true, R.string.settings_connection, R.string.settings_connection_summary, R.drawable.ic_lan),
    SECURITY(true, R.string.settings_security, R.string.settings_security_summary, R.drawable.ic_security),
    ADVANCED(true, R.string.settings_advanced, R.string.settings_advanced_summary, R.drawable.ic_tune),
    APPEARANCE(false, R.string.settings_appearance, R.string.settings_appearance_summary, R.drawable.ic_palette),
}

/**
 * One row of a settings page. Search finds it by its [label], [hint], [keywords] (words a player might search for
 * that the label does not have) and, for a choice, its choices' labels.
 */
sealed class SettingEntry(
    val category: SettingsCategory,
    @StringRes val label: Int,
    @StringRes val hint: Int?,
    @StringRes val keywords: Int,
) {
    abstract val setting: Settings.Setting<*>

    /** A switch, which turns where search finds it too. */
    class Switch(category: SettingsCategory, override val setting: Settings.BoolSetting, label: Int, hint: Int, keywords: Int) : SettingEntry(category, label, hint, keywords)

    /** One of a few choices, a radio button each. */
    class Choice<E : Enum<E>>(
        category: SettingsCategory,
        override val setting: Settings.EnumSetting<E>,
        label: Int,
        keywords: Int,
        val choiceLabel: (E) -> Int,
    ) : SettingEntry(category, label, null, keywords) {
        fun choiceLabels(): List<Int> = setting.entries.map(choiceLabel)

        /** The index in [choiceLabels] of [owner]'s choice. */
        fun chosen(owner: SettingsOwner): Int = setting.entries.indexOf(owner.value(setting))

        fun choose(owner: SettingsOwner, index: Int) = owner.change(setting, setting.entries[index])
    }

    /**
     * A whole number: a slider, with buttons a step [less] and [more], its value as [valueText] has it and, for the
     * console's text size, a [sample] of game text at that size.
     */
    class Number(
        category: SettingsCategory,
        override val setting: Settings.IntSetting,
        label: Int,
        hint: Int,
        keywords: Int,
        @PluralsRes val valueText: Int,
        @StringRes val less: Int,
        @StringRes val more: Int,
        val sample: Boolean = false,
    ) : SettingEntry(category, label, hint, keywords)
}

/** Every setting the settings screen has, in the order its pages show them. A game category's are each game's own. */
val settingsCatalogue: List<SettingEntry> by lazy {
    listOfNotNull(
        SettingEntry.Choice(SettingsCategory.DISPLAY, Settings.blankLines, R.string.settings_blank_lines, R.string.settings_blank_lines_keywords) {
            when (it) {
                Settings.BlankLines.SHOW -> R.string.settings_blank_lines_show
                Settings.BlankLines.HIDE -> R.string.settings_blank_lines_hide
                Settings.BlankLines.SPACE -> R.string.settings_blank_lines_space
            }
        },
        SettingEntry.Switch(
            SettingsCategory.DISPLAY,
            Settings.joinSplitLines,
            R.string.settings_join_split_lines,
            R.string.settings_join_split_lines_hint,
            R.string.settings_join_split_lines_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.DISPLAY,
            Settings.undoWrap,
            R.string.settings_undo_wrap,
            R.string.settings_undo_wrap_hint,
            R.string.settings_undo_wrap_keywords,
        ),
        SettingEntry.Number(
            SettingsCategory.DISPLAY,
            Settings.undoWrapWidth,
            R.string.settings_undo_wrap_width,
            R.string.settings_undo_wrap_width_hint,
            R.string.settings_undo_wrap_keywords,
            valueText = R.plurals.settings_undo_wrap_width_value,
            less = R.string.settings_undo_wrap_width_less,
            more = R.string.settings_undo_wrap_width_more,
        ),
        SettingEntry.Switch(
            SettingsCategory.ACCESSIBILITY,
            Settings.readNewText,
            R.string.settings_read_new_text,
            R.string.settings_read_new_text_hint,
            R.string.settings_read_new_text_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.ACCESSIBILITY,
            Settings.advertiseScreenReader,
            R.string.settings_advertise_screen_reader,
            R.string.settings_advertise_screen_reader_hint,
            R.string.settings_advertise_screen_reader_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.ACCESSIBILITY,
            Settings.closedCaptions,
            R.string.settings_closed_captions,
            R.string.settings_closed_captions_hint,
            R.string.settings_closed_captions_keywords,
        ),
        SettingEntry.Choice(SettingsCategory.INPUT, Settings.showSentText, R.string.settings_sent_text, R.string.settings_sent_text_keywords) {
            when (it) {
                Settings.SentText.SCRIPT -> R.string.settings_sent_text_script
                Settings.SentText.ALWAYS -> R.string.settings_sent_text_always
                Settings.SentText.NEVER -> R.string.settings_sent_text_never
            }
        },
        SettingEntry.Switch(
            SettingsCategory.INPUT,
            Settings.unixLineEndings,
            R.string.settings_unix_line_endings,
            R.string.settings_unix_line_endings_hint,
            R.string.settings_unix_line_endings_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.CONNECTION,
            Settings.offerSecureConnection,
            R.string.settings_offer_secure,
            R.string.settings_offer_secure_hint,
            R.string.settings_offer_secure_keywords,
        ),
        SettingEntry.Switch(SettingsCategory.CONNECTION, Settings.gmcp, R.string.settings_gmcp, R.string.settings_gmcp_hint, R.string.settings_protocol_keywords),
        SettingEntry.Switch(SettingsCategory.CONNECTION, Settings.msdp, R.string.settings_msdp, R.string.settings_msdp_hint, R.string.settings_protocol_keywords),
        SettingEntry.Switch(SettingsCategory.CONNECTION, Settings.mssp, R.string.settings_mssp, R.string.settings_mssp_hint, R.string.settings_protocol_keywords),
        SettingEntry.Switch(SettingsCategory.CONNECTION, Settings.msp, R.string.settings_msp, R.string.settings_msp_hint, R.string.settings_protocol_keywords),
        SettingEntry.Switch(SettingsCategory.CONNECTION, Settings.mxp, R.string.settings_mxp, R.string.settings_mxp_hint, R.string.settings_protocol_keywords),
        SettingEntry.Switch(SettingsCategory.CONNECTION, Settings.mtts, R.string.settings_mtts, R.string.settings_mtts_hint, R.string.settings_protocol_keywords),
        SettingEntry.Switch(SettingsCategory.CONNECTION, Settings.mnes, R.string.settings_mnes, R.string.settings_mnes_hint, R.string.settings_protocol_keywords),
        SettingEntry.Switch(SettingsCategory.CONNECTION, Settings.naws, R.string.settings_naws, R.string.settings_naws_hint, R.string.settings_protocol_keywords),
        SettingEntry.Switch(SettingsCategory.CONNECTION, Settings.charset, R.string.settings_charset, R.string.settings_charset_hint, R.string.settings_protocol_keywords),
        SettingEntry.Switch(
            SettingsCategory.CONNECTION,
            Settings.newEnviron,
            R.string.settings_new_environ,
            R.string.settings_new_environ_hint,
            R.string.settings_protocol_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.ADVANCED,
            Settings.forceCompressionOff,
            R.string.settings_force_compression_off,
            R.string.settings_force_compression_off_hint,
            R.string.settings_force_compression_off_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.ADVANCED,
            Settings.forceGaOff,
            R.string.settings_force_ga_off,
            R.string.settings_force_ga_off_hint,
            R.string.settings_force_ga_off_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.ADVANCED,
            Settings.versionInTerminalType,
            R.string.settings_version_in_ttype,
            R.string.settings_version_in_ttype_hint,
            R.string.settings_version_in_ttype_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.ADVANCED,
            Settings.forceMxpOn,
            R.string.settings_force_mxp_on,
            R.string.settings_force_mxp_on_hint,
            R.string.settings_force_mxp_on_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.ADVANCED,
            Settings.lazyTriggerVariables,
            R.string.settings_lazy_trigger_variables,
            R.string.settings_lazy_trigger_variables_hint,
            R.string.settings_lazy_trigger_variables_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.ADVANCED,
            Settings.showScriptErrors,
            R.string.settings_show_script_errors,
            R.string.settings_show_script_errors_hint,
            R.string.settings_show_script_errors_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.SECURITY,
            Settings.gameInstallsPackages,
            R.string.settings_game_installs_packages,
            R.string.settings_game_installs_packages_hint,
            R.string.settings_game_installs_packages_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.SECURITY,
            Settings.gamePlaysMedia,
            R.string.settings_game_plays_media,
            R.string.settings_game_plays_media_hint,
            R.string.settings_game_plays_media_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.SECURITY,
            Settings.acceptExpiredCertificate,
            R.string.settings_accept_expired_certificate,
            R.string.settings_accept_expired_certificate_hint,
            R.string.settings_certificate_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.SECURITY,
            Settings.acceptSelfSignedCertificate,
            R.string.settings_accept_self_signed_certificate,
            R.string.settings_accept_self_signed_certificate_hint,
            R.string.settings_certificate_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.SECURITY,
            Settings.acceptAnyCertificate,
            R.string.settings_accept_any_certificate,
            R.string.settings_accept_any_certificate_hint,
            R.string.settings_certificate_keywords,
        ),
        SettingEntry.Number(
            SettingsCategory.APPEARANCE,
            Settings.textSize,
            R.string.settings_text_size,
            R.string.settings_text_size_hint,
            R.string.settings_text_size_keywords,
            valueText = R.plurals.settings_text_size_value,
            less = R.string.settings_text_smaller,
            more = R.string.settings_text_larger,
            sample = true,
        ),
        // Android 12 and later only, which can colour apps from the wallpaper
        if (wallpaperColoursAvailable) {
            SettingEntry.Switch(
                SettingsCategory.APPEARANCE,
                Settings.wallpaperColours,
                R.string.settings_wallpaper_colours,
                R.string.settings_wallpaper_colours_hint,
                R.string.settings_wallpaper_colours_keywords,
            )
        } else {
            null
        },
        SettingEntry.Switch(
            SettingsCategory.APPEARANCE,
            Settings.mapBehindText,
            R.string.settings_map_behind_text,
            R.string.settings_map_behind_text_hint,
            R.string.settings_map_behind_text_keywords,
        ),
    ).also { entries ->
        // A game's page changes the game's settings in Mudlet, which only Mudlet's own settings can be
        check(entries.all { it.category.forGame == it.setting.mudlet }) { "a game category holds exactly the Mudlet settings" }
    }
}

/**
 * A settings page: [category]'s settings of the game [game], or the app's values of them where [game] is null (the
 * app's own settings, or what games added in the app start with). [highlight] is the key of the setting search
 * opened it at.
 */
data class SettingsPage(val category: SettingsCategory, val game: String?, val highlight: String? = null) {
    val owner: SettingsOwner get() = game?.let(::GameSettings) ?: AppSettings

    val entries get() = settingsCatalogue.filter { it.category == category }

    /** As a navigation argument, and for rememberSaveable. */
    fun encode(): String = listOf(category.name, highlight.orEmpty(), game?.let { GAME_PREFIX + it } ?: NO_GAME).joinToString(SEPARATOR)

    companion object {
        // The game's name comes last, as it may hold the separator
        private const val SEPARATOR = "/"
        private const val GAME_PREFIX = "g:"
        private const val NO_GAME = "-"

        fun decode(encoded: String): SettingsPage? {
            val parts = encoded.split(SEPARATOR, limit = 3)
            if (parts.size != 3) return null
            val category = SettingsCategory.entries.firstOrNull { it.name == parts[0] } ?: return null
            return SettingsPage(category, parts[2].takeIf { it.startsWith(GAME_PREFIX) }?.removePrefix(GAME_PREFIX), parts[1].ifEmpty { null })
        }
    }
}
