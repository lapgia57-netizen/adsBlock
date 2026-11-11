package com.example.adblock

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Danh sách tên miền cần chặn, dùng chung trong cả process. */
object Blocklist {

    private val sourceUrls = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/pro.txt"
    )
    private const val CACHE_NAME = "blocklist.txt"
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

    /** Nạp từ cache nếu có, nếu không thì dùng danh sách dự phòng trong assets. */
    fun load(context: Context) {
        val cache = File(context.filesDir, CACHE_NAME)
        domains = if (cache.exists()) {
            parse(cache.readLines())
        } else {
            parse(context.assets.open("hosts.txt").bufferedReader().readLines())
        }
    }

    /** Tải danh sách mới từ internet, lưu cache và trả về số tên miền. */
    fun update(context: Context): Int {
        val parsed = sourceUrls.flatMap { source ->
            val conn = URL(source).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                if (conn.responseCode !in 200..299) {
                    throw IOException("Nguồn danh sách trả về HTTP ${conn.responseCode}: $source")
                }
                conn.inputStream.bufferedReader().use { parse(it.readLines()) }
            } finally {
                conn.disconnect()
            }
        }.toSet()
        check(parsed.isNotEmpty()) { "Danh sách tải về bị rỗng" }

        val cache = File(context.filesDir, CACHE_NAME)
        val temporary = File(context.filesDir, "$CACHE_NAME.tmp")
        try {
            temporary.writeText(parsed.joinToString("\n"))
            try {
                Files.move(
                    temporary.toPath(),
                    cache.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                throw IOException("Không thể dọn tệp danh sách tạm")
            }
        }
        domains = parsed
        return parsed.size
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

    /** Combines downloaded host rules with conservative ad endpoint patterns for browser requests. */
    fun isAdRequest(host: String, path: String): Boolean {
        return isBlocked(host) ||
            adHostPattern.containsMatchIn(host) ||
            adPathPatterns.any { it.containsMatchIn(path) }
    }

    private fun parse(lines: List<String>): Set<String> {
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
