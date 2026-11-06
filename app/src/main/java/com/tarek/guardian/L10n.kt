package com.tarek.guardian

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * One place to turn a string resource into text, from anywhere — services,
 * receivers and plain objects included, not only activities.
 *
 * ## Why not just `context.getString`
 *  * The app language is chosen per app (AppCompat). Below Android 13 the
 *    *application* context does not follow that choice, only activities do, so
 *    a service calling `getString` would still speak the phone's language. This
 *    builds a context in the chosen language explicitly.
 *  * Numbers are always formatted with Western digits (0-9). Arabic phones mix
 *    both digit sets across apps; a parental app full of times and minutes reads
 *    more clearly with one, and phone numbers must never be converted.
 */
object L10n {

    private lateinit var app: Context
    @Volatile private var cachedTag: String? = null
    @Volatile private var cached: Context? = null

    fun init(c: Context) { app = c.applicationContext }

    /** A context whose resources are in the app's chosen language. */
    fun ctx(): Context {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (tag.isBlank()) return app
        cached?.let { if (tag == cachedTag) return it }
        val conf = Configuration(app.resources.configuration)
        conf.setLocale(Locale.forLanguageTag(tag.substringBefore(',')))
        return app.createConfigurationContext(conf).also { cached = it; cachedTag = tag }
    }

    val isArabic: Boolean
        get() = ctx().resources.configuration.locales[0].language == "ar"

    fun s(@StringRes id: Int, vararg args: Any): String {
        val raw = ctx().getString(id)
        return if (args.isEmpty()) raw else String.format(Locale.ENGLISH, raw, *args)
    }

    fun p(@PluralsRes id: Int, quantity: Int, vararg args: Any): String {
        val raw = ctx().resources.getQuantityString(id, quantity)
        val a = if (args.isEmpty()) arrayOf<Any>(quantity) else args
        return String.format(Locale.ENGLISH, raw, *a)
    }

    // ── Shared formatting ──────────────────────────────────────────────────────

    /** "1h 20m" / "1س 20د". */
    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) s(R.string.fmt_h_m, h, m) else s(R.string.fmt_m, m)
    }

    /** How long ago, in words. */
    fun ago(ts: Long): String {
        if (ts == 0L) return "—"
        val min = ((System.currentTimeMillis() - ts) / 60_000).toInt()
        return when {
            min < 1    -> s(R.string.ago_now)
            min < 60   -> p(R.plurals.ago_min, min)
            min < 1440 -> p(R.plurals.ago_hour, min / 60)
            else       -> p(R.plurals.ago_day, min / 1440)
        }
    }

    /** Locale for dates: Arabic month names, Western digits. */
    fun dateLocale(): Locale =
        if (isArabic) Locale.forLanguageTag("ar-u-nu-latn") else Locale.ENGLISH
}
