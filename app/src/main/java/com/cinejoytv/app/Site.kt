package com.cinejoytv.app

import android.content.SharedPreferences
import android.net.Uri

/**
 * The website this build wraps. The default is set per flavor in build.gradle.kts, and it can be
 * changed from the menu when the site moves to a new domain.
 */
object Site {

    private const val PREF = "homeUrl"

    // Declared before `domain`, which uses it during initialisation.
    private val HOST = Regex("[a-z0-9.-]+")

    @Volatile var home: String = BuildConfig.HOME_URL
        private set

    /** Host without "www.", e.g. "cinejoy.pk". Subdomains count as the same site. */
    @Volatile var domain: String = domainOf(home)!!
        private set

    fun load(prefs: SharedPreferences) {
        prefs.getString(PREF, null)?.let { normalize(it) }?.let { use(it) }
    }

    /** Switches to a new address ("example.com" or a full URL). Returns false if it isn't a web address. */
    fun change(prefs: SharedPreferences, address: String): Boolean {
        val url = normalize(address) ?: return false
        use(url)
        if (url == BuildConfig.HOME_URL) prefs.edit().remove(PREF).apply() else prefs.edit().putString(PREF, url).apply()
        return true
    }

    fun isFirstParty(host: String) = host == domain || host.endsWith(".$domain")

    // Plain hostnames only: the domain is also pasted into inject.js.
    fun domainOf(url: String): String? =
        Uri.parse(url).host?.lowercase()?.removePrefix("www.")?.takeIf { it.contains('.') && HOST.matches(it) }

    private fun use(url: String) {
        home = url
        domain = domainOf(url)!!
    }

    private fun normalize(address: String): String? {
        var url = address.trim()
        if (url.isEmpty()) return null
        if (!url.contains("://")) url = "https://$url"
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || domainOf(url) == null) return null
        if (uri.path.isNullOrEmpty()) url += "/"
        return url
    }
}
