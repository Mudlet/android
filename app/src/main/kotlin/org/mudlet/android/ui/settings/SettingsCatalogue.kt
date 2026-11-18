package org.mudlet.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.ServerEncodings
import org.mudlet.android.ui.EngineClient
import org.mudlet.android.ui.theme.wallpaperColoursAvailable

/** Whose values a settings page shows and changes: one open game's, or the app's own. */
sealed interface SettingsOwner {
    /** Whether its values can be read and changed now: a game's once its profile has loaded. */
    val available: Boolean

    fun <T> value(setting: Settings.Setting<T>): T

    fun <T> change(setting: Settings.Setting<T>, value: T)

    /** Puts [settings] back to what Mudlet starts a profile with, all at once. */
    fun resetToMudlets(settings: List<Settings.Setting<*>>)
}

/** The game [name]'s own values, which Mudlet keeps with its profile. */
data class GameSettings(val name: String) : SettingsOwner {
    private val session get() = EngineClient.session(name)

    override val available get() = session?.settingsAvailable == true

    override fun <T> value(setting: Settings.Setting<T>): T = session?.setting(setting) ?: setting.value

    override fun <T> change(setting: Settings.Setting<T>, value: T) {
        session?.changeSetting(setting, value)
    }

    override fun resetToMudlets(settings: List<Settings.Setting<*>>) {
        session?.resetSettings(settings)
    }
}

/** The app's values: its own settings, and the game settings that games added in the app start with. */
data object AppSettings : SettingsOwner {
    override val available get() = true

    override fun <T> value(setting: Settings.Setting<T>): T = setting.value

    override fun <T> change(setting: Settings.Setting<T>, value: T) = setting.set(value)

    override fun resetToMudlets(settings: List<Settings.Setting<*>>) = settings.forEach { it.setMudletDefault() }
}

/**
 * A page of the settings: [forGame] ones hold each game's own settings, the others the app's. Those [sharedByGames]
 * Mudlet keeps for every profile at once, so a game added has none of its own to start with ("New games" lacks them).
 */
enum class SettingsCategory(
    val forGame: Boolean,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
    val sharedByGames: Boolean = false,
) {
    DISPLAY(true, R.string.settings_display, R.string.settings_display_summary, R.drawable.ic_text_fields),
    COLOURS(true, R.string.settings_colours, R.string.settings_colours_summary, R.drawable.ic_invert_colors),
    MAP(true, R.string.settings_map, R.string.settings_map_summary, R.drawable.ic_map),
    ACCESSIBILITY(true, R.string.settings_accessibility, R.string.settings_accessibility_summary, R.drawable.ic_accessibility),
    INPUT(true, R.string.settings_input, R.string.settings_input_summary, R.drawable.ic_keyboard),
    CONNECTION(true, R.string.settings_connection, R.string.settings_connection_summary, R.drawable.ic_lan),
    SOUND(true, R.string.settings_sound, R.string.settings_sound_summary, R.drawable.ic_volume_up, sharedByGames = true),
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
    /** What names it on its page, which search opens at. */
    abstract val key: String

    /** Whether a page of [game]'s, or of "New games" for null, has it. */
    fun shownFor(game: String?) = game != null || this !is Action

    /** A row that changes [setting]. */
    sealed class Value(category: SettingsCategory, label: Int, hint: Int?, keywords: Int) : SettingEntry(category, label, hint, keywords) {
        abstract val setting: Settings.Setting<*>

        override val key get() = setting.key
    }

    /** A switch, which turns where search finds it too. */
    class Switch(category: SettingsCategory, override val setting: Settings.BoolSetting, label: Int, hint: Int, keywords: Int) : Value(category, label, hint, keywords)

    /**
     * Something done to an open game at once, by [button], which works where search finds it too. Only a game's page
     * has it, as "New games" has nothing to do it to.
     */
    class Action(
        category: SettingsCategory,
        override val key: String,
        label: Int,
        hint: Int,
        keywords: Int,
        @StringRes val button: Int,
        val run: (game: String) -> Unit,
    ) : SettingEntry(category, label, hint, keywords)

    /** One of a few choices, a radio button each. */
    class Choice<E : Enum<E>>(
        category: SettingsCategory,
        override val setting: Settings.EnumSetting<E>,
        label: Int,
        keywords: Int,
        val choiceLabel: (E) -> Int,
    ) : Value(category, label, null, keywords) {
        fun choiceLabels(): List<Int> = setting.entries.map(choiceLabel)

        /** The index in [choiceLabels] of [owner]'s choice. */
        fun chosen(owner: SettingsOwner): Int = setting.entries.indexOf(owner.value(setting))

        fun choose(owner: SettingsOwner, index: Int) = owner.change(setting, setting.entries[index])
    }

    /** Some text typed in, such as the command separator, which changes as the field is left or its Done key pressed. */
    class Typed(category: SettingsCategory, override val setting: Settings.TextSetting, label: Int, hint: Int, keywords: Int) : Value(category, label, hint, keywords)

    /** A colour: its swatch and "#rrggbb" in a row, which opens a dialog to change it. */
    class Colour(
        category: SettingsCategory,
        override val setting: Settings.ColourSetting,
        label: Int,
        keywords: Int = R.string.settings_colours_keywords,
    ) : Value(category, label, null, keywords)

    /**
     * One of many [choices], such as Mudlet's names for something: a row with the current one, which opens the list to
     * pick from. [choiceLabel] is a choice's label, or null for one the app has none for, which shows as it is.
     */
    class Pick(
        category: SettingsCategory,
        override val setting: Settings.TextSetting,
        label: Int,
        hint: Int,
        keywords: Int,
        val choices: List<String>,
        val choiceLabel: (String) -> Int?,
    ) : Value(category, label, hint, keywords)

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
        /** What [less] and [more] change the value by, to the next multiple of it. */
        val step: Int = 1,
    ) : Value(category, label, hint, keywords)
}

/** The key of desktop Mudlet's "Clear stored media", which is no setting's. */
const val CLEAR_MEDIA_KEY = "clearStoredMedia"

// The names of the sixteen ANSI colours, in the order of [Settings.ansiColours]
private val ANSI_COLOUR_LABELS = listOf(
    R.string.settings_colour_black,
    R.string.settings_colour_red,
    R.string.settings_colour_green,
    R.string.settings_colour_yellow,
    R.string.settings_colour_blue,
    R.string.settings_colour_magenta,
    R.string.settings_colour_cyan,
    R.string.settings_colour_white,
    R.string.settings_colour_light_black,
    R.string.settings_colour_light_red,
    R.string.settings_colour_light_green,
    R.string.settings_colour_light_yellow,
    R.string.settings_colour_light_blue,
    R.string.settings_colour_light_magenta,
    R.string.settings_colour_light_cyan,
    R.string.settings_colour_light_white,
)

// The names of the map's own colours, in the order of [Settings.mapColours]
private val MAP_COLOUR_LABELS = listOf(
    R.string.settings_map_colour_background,
    R.string.settings_map_colour_link,
    R.string.settings_map_colour_room_border,
    R.string.settings_map_colour_collision_border,
    R.string.settings_map_colour_lower_level,
    R.string.settings_map_colour_upper_level,
    R.string.settings_map_colour_grid,
)

/** Every setting the settings screen has, in the order its pages show them. A game category's are each game's own. */
val settingsCatalogue: List<SettingEntry> by lazy {
    listOfNotNull(
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
        SettingEntry.Number(
            SettingsCategory.DISPLAY,
            Settings.consoleBufferSize,
            R.string.settings_console_buffer,
            R.string.settings_console_buffer_hint,
            R.string.settings_console_buffer_keywords,
            valueText = R.plurals.settings_console_buffer_value,
            less = R.string.settings_console_buffer_less,
            more = R.string.settings_console_buffer_more,
            step = 1000,
        ),
        SettingEntry.Switch(
            SettingsCategory.DISPLAY,
            Settings.gameLinks,
            R.string.settings_game_links,
            R.string.settings_game_links_hint,
            R.string.settings_game_links_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.COLOURS,
            Settings.gameChangesColours,
            R.string.settings_game_changes_colours,
            R.string.settings_game_changes_colours_hint,
            R.string.settings_colours_keywords,
        ),
        SettingEntry.Colour(SettingsCategory.COLOURS, Settings.foregroundColour, R.string.settings_colour_foreground),
        SettingEntry.Colour(SettingsCategory.COLOURS, Settings.backgroundColour, R.string.settings_colour_background),
        SettingEntry.Colour(SettingsCategory.COLOURS, Settings.commandForegroundColour, R.string.settings_colour_command_foreground),
        SettingEntry.Colour(SettingsCategory.COLOURS, Settings.commandBackgroundColour, R.string.settings_colour_command_background),
        *Settings.ansiColours.zip(ANSI_COLOUR_LABELS) { setting, label -> SettingEntry.Colour(SettingsCategory.COLOURS, setting, label) }.toTypedArray(),
        SettingEntry.Number(
            SettingsCategory.MAP,
            Settings.mapRoomSize,
            R.string.settings_map_room_size,
            R.string.settings_map_room_size_hint,
            R.string.settings_map_size_keywords,
            valueText = R.plurals.settings_map_size_value,
            less = R.string.settings_map_room_size_less,
            more = R.string.settings_map_room_size_more,
        ),
        SettingEntry.Number(
            SettingsCategory.MAP,
            Settings.mapExitSize,
            R.string.settings_map_exit_size,
            R.string.settings_map_exit_size_hint,
            R.string.settings_map_size_keywords,
            valueText = R.plurals.settings_map_size_value,
            less = R.string.settings_map_exit_size_less,
            more = R.string.settings_map_exit_size_more,
        ),
        SettingEntry.Switch(
            SettingsCategory.MAP,
            Settings.mapRoomBorders,
            R.string.settings_map_room_borders,
            R.string.settings_map_room_borders_hint,
            R.string.settings_map_size_keywords,
        ),
        SettingEntry.Number(
            SettingsCategory.MAP,
            Settings.mapRoomBorderSize,
            R.string.settings_map_border_size,
            R.string.settings_map_border_size_hint,
            R.string.settings_map_size_keywords,
            valueText = R.plurals.settings_map_size_value,
            less = R.string.settings_map_border_size_less,
            more = R.string.settings_map_border_size_more,
        ),
        SettingEntry.Switch(
            SettingsCategory.MAP,
            Settings.mapLargeAreaExitArrows,
            R.string.settings_map_large_area_exit_arrows,
            R.string.settings_map_large_area_exit_arrows_hint,
            R.string.settings_map_large_area_exit_arrows_keywords,
        ),
        SettingEntry.Choice(SettingsCategory.MAP, Settings.playerRoomStyle, R.string.settings_map_player_room, R.string.settings_map_player_room_keywords) {
            when (it) {
                Settings.PlayerRoomStyle.ORIGINAL -> R.string.settings_map_player_room_original
                Settings.PlayerRoomStyle.RED_RING -> R.string.settings_map_player_room_red_ring
                Settings.PlayerRoomStyle.BLUE_YELLOW_RING -> R.string.settings_map_player_room_blue_yellow_ring
                Settings.PlayerRoomStyle.CUSTOM_RING -> R.string.settings_map_player_room_custom_ring
            }
        },
        SettingEntry.Number(
            SettingsCategory.MAP,
            Settings.playerRoomOuterDiameter,
            R.string.settings_map_player_room_outer_diameter,
            R.string.settings_map_player_room_outer_diameter_hint,
            R.string.settings_map_player_room_keywords,
            valueText = R.plurals.settings_map_percent_value,
            less = R.string.settings_map_player_room_outer_diameter_less,
            more = R.string.settings_map_player_room_outer_diameter_more,
        ),
        SettingEntry.Number(
            SettingsCategory.MAP,
            Settings.playerRoomInnerDiameter,
            R.string.settings_map_player_room_inner_diameter,
            R.string.settings_map_player_room_inner_diameter_hint,
            R.string.settings_map_player_room_keywords,
            valueText = R.plurals.settings_map_percent_value,
            less = R.string.settings_map_player_room_inner_diameter_less,
            more = R.string.settings_map_player_room_inner_diameter_more,
        ),
        SettingEntry.Colour(
            SettingsCategory.MAP,
            Settings.playerRoomOuterColour,
            R.string.settings_map_player_room_outer_colour,
            R.string.settings_map_player_room_keywords,
        ),
        SettingEntry.Colour(
            SettingsCategory.MAP,
            Settings.playerRoomInnerColour,
            R.string.settings_map_player_room_inner_colour,
            R.string.settings_map_player_room_keywords,
        ),
        *Settings.mapColours.zip(MAP_COLOUR_LABELS + ANSI_COLOUR_LABELS) { setting, label ->
            SettingEntry.Colour(SettingsCategory.MAP, setting, label, R.string.settings_map_colours_keywords)
        }.toTypedArray(),
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
        SettingEntry.Choice(SettingsCategory.ACCESSIBILITY, Settings.blankLines, R.string.settings_blank_lines, R.string.settings_blank_lines_keywords) {
            when (it) {
                Settings.BlankLines.SHOW -> R.string.settings_blank_lines_show
                Settings.BlankLines.HIDE -> R.string.settings_blank_lines_hide
                Settings.BlankLines.SPACE -> R.string.settings_blank_lines_space
            }
        },
        SettingEntry.Switch(
            SettingsCategory.ACCESSIBILITY,
            Settings.closedCaptions,
            R.string.settings_closed_captions,
            R.string.settings_closed_captions_hint,
            R.string.settings_closed_captions_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.ACCESSIBILITY,
            Settings.blinkText,
            R.string.settings_blink_text,
            R.string.settings_blink_text_hint,
            R.string.settings_blink_text_keywords,
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
            SettingsCategory.INPUT,
            Settings.clearInputAfterSend,
            R.string.settings_clear_input,
            R.string.settings_clear_input_hint,
            R.string.settings_clear_input_keywords,
        ),
        SettingEntry.Typed(
            SettingsCategory.INPUT,
            Settings.commandSeparator,
            R.string.settings_command_separator,
            R.string.settings_command_separator_hint,
            R.string.settings_command_separator_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.INPUT,
            Settings.highlightHistory,
            R.string.settings_highlight_history,
            R.string.settings_highlight_history_hint,
            R.string.settings_highlight_history_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.INPUT,
            Settings.showPasswords,
            R.string.settings_show_passwords,
            R.string.settings_show_passwords_hint,
            R.string.settings_show_passwords_keywords,
        ),
        SettingEntry.Switch(
            SettingsCategory.INPUT,
            Settings.runAllKeyBindings,
            R.string.settings_run_all_key_bindings,
            R.string.settings_run_all_key_bindings_hint,
            R.string.settings_run_all_key_bindings_keywords,
        ),
        SettingEntry.Pick(
            SettingsCategory.CONNECTION,
            Settings.serverEncoding,
            R.string.settings_server_encoding,
            R.string.settings_server_encoding_hint,
            R.string.settings_server_encoding_keywords,
            choices = ServerEncodings.names,
            choiceLabel = encodingLabels::get,
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
        SettingEntry.Switch(SettingsCategory.SOUND, Settings.muteScripts, R.string.settings_mute_scripts, R.string.settings_mute_scripts_hint, R.string.settings_mute_keywords),
        SettingEntry.Switch(SettingsCategory.SOUND, Settings.muteGame, R.string.settings_mute_game, R.string.settings_mute_game_hint, R.string.settings_mute_keywords),
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
            Settings.fixLinebreaks,
            R.string.settings_fix_linebreaks,
            R.string.settings_fix_linebreaks_hint,
            R.string.settings_fix_linebreaks_keywords,
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
        SettingEntry.Action(
            SettingsCategory.SECURITY,
            CLEAR_MEDIA_KEY,
            R.string.settings_clear_media,
            R.string.settings_clear_media_hint,
            R.string.settings_clear_media_keywords,
            R.string.settings_clear_media_button,
        ) { game -> EngineClient.session(game)?.clearStoredMedia() },
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
        check(entries.all { it.category.forGame == ((it as? SettingEntry.Value)?.setting?.mudlet ?: true) }) { "a game category holds exactly the Mudlet settings, and the actions" }
    }
}

/**
 * A settings page: [category]'s settings of the game [game], or the app's values of them where [game] is null (the
 * app's own settings, or what games added in the app start with). [highlight] is the key of the setting search
 * opened it at.
 */
data class SettingsPage(val category: SettingsCategory, val game: String?, val highlight: String? = null) {
    val owner: SettingsOwner get() = game?.let(::GameSettings) ?: AppSettings

    val entries get() = settingsCatalogue.filter { it.category == category && it.shownFor(game) }

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
