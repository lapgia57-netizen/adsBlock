package com.example.adblock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.update

/**
 * Chỉ định tuyến đúng địa chỉ DNS ảo 10.0.0.1 vào VPN, nên các gói khác đi thẳng ra mạng
 * (không tốn pin và không ảnh hưởng tốc độ). Truy vấn DNS được lọc, phần còn lại
 * chuyển tiếp tới DNS thật qua socket đã protect() để tránh vòng lặp.
 */
class AdBlockVpnService : VpnService() {

    private val vpnDns = "10.0.0.1"
    private val upstream: InetAddress = InetAddress.getByName("1.1.1.1") // IP trực tiếp, không cần phân giải
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var pool: ExecutorService? = null

    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return Service.START_NOT_STICKY
        }
        startForegroundNotification()
        if (!running) startVpn()
        return Service.START_STICKY
    }

    private fun startVpn() {
        running = true
        pool = Executors.newFixedThreadPool(8)
        worker = Thread({ runTunnel() }, "adblock-tunnel").apply { start() }
    }

    private fun runTunnel() {
        Blocklist.load(this)

        val pfd = Builder()
            .setSession("AdBlock")
            .addAddress("10.0.0.2", 32)
            .addDnsServer(vpnDns)
            .addRoute(vpnDns, 32)
            .setBlocking(true)
            .establish()

        if (pfd == null) {
            running = false
            stopSelf()
            return
        }
        tun = pfd
        AdBlockStats.running.value = true
        readLoop(pfd)
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val packet = buf.copyOf(n)
                pool?.execute { handle(packet, output) }
            }
        } catch (_: IOException) {
            // TUN đã đóng khi người dùng tắt VPN
        } finally {
            running = false
        }
    }

    private fun handle(packet: ByteArray, out: FileOutputStream) {
        val udp = DnsPacket.parseUdpDns(packet, packet.size) ?: return
        val q = DnsPacket.parseQuestion(packet, udp.dnsStart, udp.dnsEnd) ?: return

        val reply: ByteArray = if (Blocklist.isBlocked(q.name)) {
            AdBlockStats.blocked.update { it + 1 }
            BlockedHistory.record(this, q.name, BlockedSource.DNS)
            DnsPacket.buildBlockedResponse(packet, q, udp.dnsStart)
        } else {
            forward(packet, udp.dnsStart, udp.dnsEnd) ?: return
        }

        val ip = DnsPacket.buildIpUdp(
            srcIp = udp.dstIp, srcPort = 53,
            dstIp = udp.srcIp, dstPort = udp.srcPort,
            payload = reply
        )
        synchronized(out) { out.write(ip) }
    }

    private fun forward(packet: ByteArray, start: Int, end: Int): ByteArray? {
        return try {
            DatagramSocket().use { sock ->
                if (!protect(sock)) return null
                sock.soTimeout = 3000
                sock.send(DatagramPacket(packet, start, end - start, upstream, 53))
                val rb = ByteArray(4096)
                val rp = DatagramPacket(rb, rb.size)
                sock.receive(rp)
                rb.copyOf(rp.length)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun stopVpn() {
        running = false
        AdBlockStats.running.value = false
        tun?.close()
        tun = null
        worker?.interrupt()
        worker = null
        pool?.shutdownNow()
        pool = null
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AdBlock", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AdBlock đang chặn quảng cáo")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.adblock.START"
        const val ACTION_STOP = "com.example.adblock.STOP"
        private const val CHANNEL_ID = "adblock"
        private const val NOTIFICATION_ID = 1
    }
}
