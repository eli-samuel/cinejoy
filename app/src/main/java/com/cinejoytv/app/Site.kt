package com.cinejoytv.app

import android.net.Uri

/** The website this build wraps (set per flavor in build.gradle.kts). */
object Site {

    val home: String = BuildConfig.HOME_URL

    /** Registrable host without "www.", e.g. "cinejoy.pk". Subdomains count as the same site. */
    val domain: String = domainOf(home)!!

    fun isFirstParty(host: String) = host == domain || host.endsWith(".$domain")

    fun domainOf(url: String): String? =
        Uri.parse(url).host?.lowercase()?.removePrefix("www.")?.takeIf { it.contains('.') }
}
