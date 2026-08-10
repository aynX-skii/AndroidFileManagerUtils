package com.aynux.afmu.core

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Applies the language chosen in [Prefs] on top of whatever the system locale is.
 *
 * Done by deriving a configuration context rather than through the platform's per-app language
 * API: that one only exists on API 33+, and this app supports 26+. It works the same on every
 * version and keeps the choice entirely inside our own preferences.
 */
object LocaleHelper {

    /** Wrap a context so its resources resolve in the configured language. */
    fun wrap(base: Context): Context = wrap(base, Prefs(base).language)

    /**
     * Wrap a context so its resources resolve in [tag].
     *
     * [Prefs.LANG_SYSTEM] resolves to the device locale explicitly instead of returning [base]
     * untouched, so this also works when [base] is already localised — which it is once the
     * activity has wrapped its own base context. Otherwise switching *back* to "follow system"
     * would just keep whatever language was applied before.
     */
    fun wrap(base: Context, tag: String): Context {
        val locale = resolve(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Point [Locale.getDefault] at the chosen language, for the formatting APIs that take no
     * Context. Separate from [wrap] because that one gets called from composition, which is no
     * place for a process-wide side effect.
     */
    fun applyDefault(tag: String) {
        Locale.setDefault(resolve(tag))
    }

    /**
     * The language actually in effect, as one of [Prefs.LANG_ENGLISH] / [Prefs.LANG_CHINESE].
     * Used to show which option is active when the setting is "follow system".
     */
    fun effective(context: Context): String {
        val tag = Prefs(context).language
        if (tag != Prefs.LANG_SYSTEM) return tag
        return if (systemLocale().language == "zh") Prefs.LANG_CHINESE else Prefs.LANG_ENGLISH
    }

    private fun resolve(tag: String): Locale =
        if (tag == Prefs.LANG_SYSTEM) systemLocale() else Locale.forLanguageTag(tag)

    /**
     * Read from [Resources.getSystem] rather than any app context: ours carry the override we
     * are trying to see past, and [Locale.getDefault] is process state we ourselves write.
     */
    private fun systemLocale(): Locale {
        val locales = Resources.getSystem().configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }
}
