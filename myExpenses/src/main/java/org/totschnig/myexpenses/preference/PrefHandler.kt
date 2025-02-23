package org.totschnig.myexpenses.preference

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.preference.PreferenceFragmentCompat
import org.totschnig.myexpenses.BuildConfig
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.dialog.MenuItem
import org.totschnig.myexpenses.dialog.valueOf
import org.totschnig.myexpenses.dialog.values
import org.totschnig.myexpenses.util.Utils
import org.totschnig.myexpenses.util.toDayOfWeek
import java.util.Calendar
import java.util.Locale

interface PrefHandler {
    fun getKey(key: PrefKey): String
    fun getString(key: PrefKey, defValue: String? = null): String?
    fun getString(key: String, defValue: String? = null): String?
    fun putString(key: PrefKey, value: String?)
    fun putString(key: String, value: String?)
    fun getBoolean(key: PrefKey, defValue: Boolean): Boolean
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun putBoolean(key: PrefKey, value: Boolean)
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: PrefKey, defValue: Int): Int
    fun getInt(key: String, defValue: Int): Int
    fun putInt(key: PrefKey, value: Int)
    fun putInt(key: String, value: Int)
    fun getLong(key: PrefKey, defValue: Long): Long
    fun getLong(key: String, defValue: Long): Long
    fun putLong(key: PrefKey, value: Long)
    fun putLong(key: String, value: Long)

    fun getStringSet(key:PrefKey, separator: Char = ':'): Set<String>?

    /**
     * @param separator no item in value must contain separator
     */
    fun putStringSet(key: PrefKey, value: Set<String>, separator: Char = ':')
    fun remove(key: PrefKey)
    fun remove(key: String)
    fun isSet(key: PrefKey): Boolean
    fun isSet(key: String): Boolean
    fun matches(key: String, vararg prefKeys: PrefKey): Boolean
    fun setDefaultValues(context: Context)
    fun preparePreferenceFragment(preferenceFragmentCompat: PreferenceFragmentCompat)

    fun getStringPreferencesKey(key: PrefKey) = stringPreferencesKey(getKey(key))
    fun getBooleanPreferencesKey(key: PrefKey) = booleanPreferencesKey(getKey(key))

    fun requireString(key: PrefKey, defaultValue: String) =
        getString(key, defaultValue)!!

    fun requireString(key: String, defaultValue: String) =
        getString(key, defaultValue)!!

    val encryptDatabase
        get() = getBoolean(PrefKey.ENCRYPT_DATABASE, false)

    val collate
        get() = if (encryptDatabase) "NOCASE" else "LOCALIZED"

    val monthStart
        get() = try {
            requireString((PrefKey.GROUP_MONTH_STARTS), "1").toInt()
                .takeIf { it in 1..31 }
        } catch (e: NumberFormatException) {
            null
        } ?: 1

    val weekStart
        get() = try {
            getString(PrefKey.GROUP_WEEK_STARTS)?.toInt()
        } catch (e: NumberFormatException) {
            null
        }.takeIf { it in Calendar.SUNDAY..Calendar.SATURDAY }

    fun weekStartWithFallback(locale: Locale = Locale.getDefault()) = weekStart ?: Utils.getFirstDayOfWeek(locale)

    val weekStartAsDayOfWeek
        get() = weekStartWithFallback().toDayOfWeek

    fun uiMode(context: Context) = getString(
        PrefKey.UI_THEME_KEY,
        context.getString(R.string.pref_ui_theme_default)
    )

    val isProtected
        get() =  getBoolean(PrefKey.PROTECTION_LEGACY, false) ||
                getBoolean(PrefKey.PROTECTION_DEVICE_LOCK_SCREEN, false)

    val shouldSecureWindow
        get() = isProtected && !getBoolean(PrefKey.PROTECTION_ALLOW_SCREENSHOT, false)

    val defaultTransferCategory: Long?
        get() = getLong(PrefKey.DEFAULT_TRANSFER_CATEGORY, -1L).takeIf { it != -1L }

    val mainMenu: List<MenuItem>
        get() = getStringSet(PrefKey.CUSTOMIZE_MAIN_MENU)
            ?.let { stored -> stored.mapNotNull {
                try {
                    MenuItem.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            } }
            ?: MenuItem.defaultConfiguration

    val shouldDebug: Boolean
        get() = getBoolean(PrefKey.DEBUG_LOGGING, BuildConfig.DEBUG)
}

inline fun <reified T : Enum<T>> PrefHandler.enumValueOrDefault(prefKey: PrefKey, default: T): T =
    org.totschnig.myexpenses.util.enumValueOrDefault(getString(prefKey, default.name), default)

inline fun <reified T : Enum<T>> PrefHandler.enumValueOrDefault(prefKey: String, default: T): T =
    org.totschnig.myexpenses.util.enumValueOrDefault(getString(prefKey, default.name), default)