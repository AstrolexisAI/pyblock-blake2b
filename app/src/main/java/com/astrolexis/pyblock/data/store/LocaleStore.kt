package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** In-app language override (6 locales). Live switch without recreating the
 *  Activity: [LocalizedApp] provides a locale-adjusted Context so `stringResource`
 *  recomposes when [lang] changes. Mirrors iOS `LocalizationManager`. */
object LocaleStore {
    data class Lang(val code: String, val name: String)

    /** Shipped languages. `code` maps to a values-<qualifier> resource dir. */
    val supported = listOf(
        Lang("en", "English"),
        Lang("es", "Español"),
        Lang("pt-BR", "Português"),
        Lang("fr", "Français"),
        Lang("de", "Deutsch"),
        Lang("it", "Italiano"),
    )

    private const val PREFS = "pyblock_locale"
    private const val KEY = "lang"

    private val _lang = MutableStateFlow("en")
    val lang: StateFlow<String> = _lang.asStateFlow()
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _lang.value = p.getString(KEY, null) ?: systemDefault()
    }

    fun select(code: String) {
        if (supported.none { it.code == code } || code == _lang.value) return
        _lang.value = code
        prefs?.edit()?.putString(KEY, code)?.apply()
    }

    /** Best match of the device language to our supported set. */
    private fun systemDefault(): String = when (Locale.getDefault().language.lowercase()) {
        "es" -> "es"; "pt" -> "pt-BR"; "fr" -> "fr"; "de" -> "de"; "it" -> "it"; else -> "en"
    }

    fun locale(code: String): Locale = when (code) {
        "pt-BR" -> Locale("pt", "BR")
        else -> Locale.forLanguageTag(code)
    }
}
