package com.example.adblock

import java.io.ByteArrayOutputStream

class DnsQuestion(val name: String, val qtype: Int, val endOffset: Int)

class UdpDnsPacket(
    val srcIp: ByteArray,
    val srcPort: Int,
    val dstIp: ByteArray,
    val dstPort: Int,
    val dnsStart: Int,
    val dnsEnd: Int
)

/** Đọc và dựng gói IPv4/UDP/DNS ở mức byte. */
object DnsPacket {

    fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    /** Trả về thông tin UDP/DNS nếu gói là IPv4 UDP gửi tới cổng 53. */
    fun parseUdpDns(b: ByteArray, len: Int): UdpDnsPacket? {
        if (len < 28) return null
        if ((b[0].toInt() shr 4) != 4) return null
        val ihl = (b[0].toInt() and 0x0F) * 4
        if ((b[9].toInt() and 0xFF) != 17) return null
        if (len < ihl + 8) return null
        val dstPort = u16(b, ihl + 2)
        if (dstPort != 53) return null
        val udpLen = u16(b, ihl + 4)
        val dnsStart = ihl + 8
        val dnsEnd = minOf(len, ihl + udpLen)
        return UdpDnsPacket(
            srcIp = b.copyOfRange(12, 16),
            srcPort = u16(b, ihl),
            dstIp = b.copyOfRange(16, 20),
            dstPort = dstPort,
            dnsStart = dnsStart,
            dnsEnd = dnsEnd
        )
    }

    /** Đọc câu hỏi DNS đầu tiên: tên miền và loại bản ghi (A = 1, AAAA = 28). */
    fun parseQuestion(buf: ByteArray, start: Int, end: Int): DnsQuestion? {
        if (end - start < 12) return null
        if (u16(buf, start + 4) < 1) return null
        var pos = start + 12
        val labels = ArrayList<String>()
        while (true) {
            if (pos >= end) return null
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if ((len and 0xC0) != 0) return null // truy vấn hợp lệ không dùng nén
            if (pos + 1 + len > end) return null
            labels.add(String(buf, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        if (pos + 4 > end) return null
        val qtype = u16(buf, pos)
        return DnsQuestion(labels.joinToString(".").lowercase(), qtype, pos + 4)
    }

    /**
     * Trả lời giả cho tên miền bị chặn:
     * - Loại A: trả về 0.0.0.0
     * - Loại khác (AAAA...): NOERROR nhưng không có bản ghi nào
     */
    fun buildBlockedResponse(packet: ByteArray, q: DnsQuestion, qStart: Int): ByteArray {
        val answerA = q.qtype == 1
        val out = ByteArrayOutputStream()

        val header = ByteArray(12)
        System.arraycopy(packet, qStart, header, 0, 12)
        header[2] = ((header[2].toInt() and 0x01) or 0x80).toByte() // QR = 1, giữ RD
        header[3] = 0x80.toByte()                                    // RA = 1, RCODE = 0
        putU16(header, 6, if (answerA) 1 else 0)                     // ANCOUNT
        putU16(header, 8, 0)                                         // NSCOUNT
        putU16(header, 10, 0)                                        // ARCOUNT
        out.write(header)

        // Phần câu hỏi được giữ nguyên
        out.write(packet, qStart + 12, q.endOffset - (qStart + 12))

        if (answerA) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C)) // con trỏ tới tên ở offset 12
            out.write(byteArrayOf(0, 1, 0, 1))          // type A, class IN
            out.write(byteArrayOf(0, 0, 0, 60))         // TTL 60 giây
            out.write(byteArrayOf(0, 4, 0, 0, 0, 0))    // RDLENGTH 4, 0.0.0.0
        }
        return out.toByteArray()
    }

    /** Dựng gói IPv4 + UDP chứa payload. */
    fun buildIpUdp(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45                       // IPv4, IHL 5
        putU16(p, 2, total)
        putU16(p, 6, 0x4000)              // Don't Fragment
        p[8] = 64                         // TTL
        p[9] = 17                         // UDP
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        putU16(p, 10, checksum(p, 0, 20))

        putU16(p, 20, srcPort)
        putU16(p, 22, dstPort)
        putU16(p, 24, 8 + payload.size)
        putU16(p, 26, 0)                  // checksum UDP là tùy chọn trên IPv4
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
