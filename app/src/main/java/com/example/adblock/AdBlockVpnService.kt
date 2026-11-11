package com.example.adblock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return Service.START_NOT_STICKY
        }
        startForegroundNotification()
        if (!running) {
            AdBlockStats.error.value = ""
            startVpn()
        }
        return Service.START_STICKY
    }

    private fun startVpn() {
        running = true
        pool = Executors.newFixedThreadPool(8)
        worker = Thread({ runTunnel() }, "adblock-tunnel").apply { start() }
    }

    private fun runTunnel() {
        val pfd = try {
            Blocklist.load(this)
            Builder()
                .setSession("AdBlock")
                .addAddress("10.0.0.2", 32)
                .addDnsServer(vpnDns)
                .addRoute(vpnDns, 32)
                .setBlocking(true)
                .establish()
        } catch (error: Exception) {
            running = false
            AdBlockStats.running.value = false
            AdBlockStats.error.value =
                "Không thể khởi động VPN: ${error.message ?: "lỗi không xác định"}"
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (pfd == null) {
            running = false
            AdBlockStats.running.value = false
            AdBlockStats.error.value = "Android không cấp được kết nối VPN"
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
        } catch (error: IOException) {
            if (running) {
                AdBlockStats.error.value =
                    "Kết nối VPN bị gián đoạn: ${error.message ?: "lỗi đọc dữ liệu"}"
            }
        } finally {
            running = false
            AdBlockStats.running.value = false
            if (tun === pfd) {
                tun = null
                pfd.close()
                stopSelf()
            }
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

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AdBlock đang chặn quảng cáo")
            .setContentText("Tiếp tục chạy nền · Nhấn để mở ứng dụng")
            .setSmallIcon(R.drawable.ic_adblock_notification)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dừng",
                stopIntent
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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

    override fun onRevoke() {
        stopVpn()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    companion object {
        const val ACTION_START = "com.example.adblock.START"
        const val ACTION_STOP = "com.example.adblock.STOP"
        private const val CHANNEL_ID = "adblock"
        private const val NOTIFICATION_ID = 1
    }
}
