package com.example.adblock

import android.content.Context
import java.io.IOException

/** Danh sách tên miền được đóng gói cùng ứng dụng trong assets/hosts.txt. */
object Blocklist {

    private val adHostPattern = Regex(
        "(^|[.-])(ad|ads|adserver|adservice|advert|advertising|banner|doubleclick|googlesyndication|pagead|gampad|adnxs|taboola|outbrain|criteo)([.-]|$)",
        RegexOption.IGNORE_CASE
    )
    private val adPathPatterns = listOf(
        Regex("/(?:ads?|adserver|adservice|advertising|pagead|gampad|doubleclick)(?:/|$)", RegexOption.IGNORE_CASE),
        Regex("/(?:ads?|tracking|analytics)[._-](?:async|pixel|request)(?:/|$)", RegexOption.IGNORE_CASE)
    )
    private val domainPattern = Regex(
        "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
    )

    @Volatile
    private var domains: Set<String> = emptySet()

    /** Đọc danh sách hosts được đóng gói trong APK; sửa assets/hosts.txt rồi build APK để cập nhật. */
    fun load(context: Context) {
        val loadedDomains = context.assets.open("hosts.txt").bufferedReader().use {
            parse(it.lineSequence())
        }
        if (loadedDomains.isEmpty()) {
            throw IOException("assets/hosts.txt không chứa domain hợp lệ")
        }
        domains = loadedDomains
    }

    /** Kiểm tra cả tên miền cha: a.ads.example.com bị chặn nếu ads.example.com nằm trong danh sách. */
    fun isBlocked(name: String): Boolean {
        val set = domains
        var n = name.lowercase().trimEnd('.')
        while (true) {
            if (set.contains(n)) return true
            val dot = n.indexOf('.')
            if (dot < 0) return false
            n = n.substring(dot + 1)
        }
    }

    /** Combines bundled host rules with conservative ad endpoint patterns for browser requests. */
    fun isAdRequest(host: String, path: String): Boolean {
        return isBlocked(host) ||
            adHostPattern.containsMatchIn(host) ||
            adPathPatterns.any { it.containsMatchIn(path) }
    }

    private fun parse(lines: Sequence<String>): Set<String> {
        val out = HashSet<String>()
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            val candidate = if (parts.size >= 2) {
                parts[1]
            } else {
                parts[0].removePrefix("||").removeSuffix("^").removePrefix("*.")
            }
            val domain = candidate.lowercase().trimEnd('.')
            if (domain in setOf("localhost", "broadcasthost", "ip6-localhost", "ip6-loopback")) continue
            if (domainPattern.matches(domain)) out.add(domain)
        }
        return out
    }
}
