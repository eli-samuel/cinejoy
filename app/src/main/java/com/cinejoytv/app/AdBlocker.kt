package com.cinejoytv.app

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Domain-level network blocker fed by standard uBlock Origin / AdBlock Plus filter lists.
 *
 * Only whole-domain rules ("||example.com^", hosts-file entries) and their "@@" exceptions are
 * used. That covers the bulk of ad/tracker/popunder networks while keeping lookups O(labels)
 * and memory small. Cosmetic rules are skipped; a small cosmetic set lives in inject.js.
 */
class AdBlocker(private val context: Context) {

    @Volatile var enabled = true
    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()

    val ruleCount: Int get() = blocked.size

    private val compiled = File(context.filesDir, "blocklist.txt")
    private val prefs = context.getSharedPreferences("adblock", Context.MODE_PRIVATE)

    fun init() = thread(name = "adblock-init") {
        load()
        val age = System.currentTimeMillis() - prefs.getLong("updated", 0)
        if (!compiled.exists() || age > UPDATE_INTERVAL_MS) update()
    }

    fun shouldBlock(uri: Uri): Boolean = uri.host?.let { isBlockedHost(it.lowercase()) } ?: false

    fun isBlockedHost(host: String): Boolean =
        enabled && !Site.isFirstParty(host) && !matches(host, allowed) && matches(host, blocked)

    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Downloads all filter lists and swaps in the new rules. Blocking; call off the main thread. */
    fun update(): Boolean {
        val block = HashSet<String>(150_000)
        val allow = HashSet<String>()
        var ok = 0
        for (list in FILTER_LISTS) {
            try {
                val conn = URL(list).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { parseLine(it, block, allow) }
                }
                ok++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch $list", e)
            }
        }
        if (ok == 0) return false
        readDefaults(block)
        val tmp = File(compiled.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            block.forEach { w.write(it); w.write("\n") }
            allow.forEach { w.write("@@"); w.write(it); w.write("\n") }
        }
        tmp.renameTo(compiled)
        blocked = block
        allowed = allow
        prefs.edit().putLong("updated", System.currentTimeMillis()).apply()
        Log.i(TAG, "Filter lists updated: ${block.size} blocked, ${allow.size} exceptions")
        return true
    }

    private fun load() {
        val block = HashSet<String>()
        val allow = HashSet<String>()
        readDefaults(block)
        if (compiled.exists()) {
            compiled.forEachLine { line ->
                if (line.startsWith("@@")) allow.add(line.substring(2)) else if (line.isNotEmpty()) block.add(line)
            }
        }
        blocked = block
        allowed = allow
    }

    private fun readDefaults(into: MutableSet<String>) {
        context.assets.open("default_blocklist.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { into.add(it) }
        }
    }

    private fun parseLine(raw: String, block: MutableSet<String>, allow: MutableSet<String>) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[' || line[0] == '#') return
        // Cosmetic / scriptlet rules are not network rules.
        if (COSMETIC_MARKERS.any { line.contains(it) }) return

        // Hosts-file entry: "0.0.0.0 ads.example.com"
        if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
            val domain = line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase()
            if (isValidDomain(domain) && domain != "localhost" && domain != "0.0.0.0") block.add(domain)
            return
        }

        var s = line
        val exception = s.startsWith("@@")
        if (exception) s = s.substring(2)
        if (!s.startsWith("||")) return
        s = s.substring(2)

        val end = s.indexOfFirst { it == '^' || it == '$' || it == '/' || it == '|' }
        val domain = (if (end < 0) s else s.substring(0, end)).lowercase()
        var rest = if (end < 0) "" else s.substring(end)
        if (rest.startsWith("^")) rest = rest.substring(1)
        if (rest.startsWith("|")) rest = rest.substring(1)
        if (rest.isNotEmpty() && rest[0] != '$') return // path-specific rule
        val options = rest.removePrefix("$").split(',').filter { it.isNotEmpty() }

        if (!isValidDomain(domain)) return
        if (options.any { opt -> SKIP_OPTIONS.any { opt.startsWith(it) } }) return
        if (exception) {
            if (options.any { it in COSMETIC_EXCEPTIONS }) return
            allow.add(domain)
        } else {
            block.add(domain)
        }
    }

    private fun isValidDomain(d: String) =
        d.contains('.') && d.none { it == '*' || it == ' ' || it == '/' } && !d.startsWith('.') && !d.endsWith('.')

    companion object {
        private const val TAG = "AdBlocker"
        private const val UPDATE_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000

        val FILTER_LISTS = listOf(
            "https://ublockorigin.github.io/uAssets/filters/filters.txt",
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
        )

        private val COSMETIC_MARKERS = listOf("##", "#@#", "#?#", "#$#", "#%#", "#+js")

        // Rules with these options are conditional; applying them as blanket domain blocks
        // would over-block (e.g. "$popup" rules on otherwise legitimate domains).
        private val SKIP_OPTIONS = listOf(
            "domain=", "from=", "to=", "popup", "~", "first-party", "1p", "badfilter", "csp",
            "removeparam", "redirect-rule", "header", "permissions", "method=", "denyallow",
            "match-case", "strict",
        )
        private val COSMETIC_EXCEPTIONS = setOf("generichide", "ghide", "elemhide", "ehide", "specifichide", "shide")

        fun matches(host: String, set: Set<String>): Boolean {
            var h = host
            while (true) {
                if (h in set) return true
                val dot = h.indexOf('.')
                if (dot < 0 || dot == h.length - 1) return false
                h = h.substring(dot + 1)
            }
        }
    }
}
