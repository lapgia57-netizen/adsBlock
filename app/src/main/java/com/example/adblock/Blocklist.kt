package com.example.adblock

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Danh sách tên miền cần chặn, dùng chung trong cả process. */
object Blocklist {

    private const val SOURCE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val CACHE_NAME = "blocklist.txt"

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
        val conn = URL(SOURCE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        val lines = conn.inputStream.bufferedReader().use { it.readLines() }
        val parsed = parse(lines)
        check(parsed.isNotEmpty()) { "Danh sách tải về bị rỗng" }
        File(context.filesDir, CACHE_NAME).writeText(parsed.joinToString("\n"))
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

    private fun parse(lines: List<String>): Set<String> {
        val out = HashSet<String>()
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            val domain = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
            if (domain == "localhost" || domain == "0.0.0.0" || domain == "broadcasthost") continue
            out.add(domain)
        }
        return out
    }
}
