package com.example.adblock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BrowserPrivacyHeaders = mapOf(
    "DNT" to "1",
    "Sec-GPC" to "1"
)

private val AdBlockColorScheme = darkColorScheme(
    primary = Color(0xFF79E0B5),
    onPrimary = Color(0xFF06251A),
    secondary = Color(0xFFB3CCBE),
    background = Color(0xFF0B1210),
    surface = Color(0xFF121D19),
    surfaceVariant = Color(0xFF1C2A24),
    onSurface = Color(0xFFE7F1EB),
    onSurfaceVariant = Color(0xFFB5C8BD)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AdBlockColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AdBlockApp()
                }
            }
        }
    }
}

@Composable
private fun AdBlockApp() {
    val context = LocalContext.current
    var showBrowser by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var blocklistReady by remember { mutableStateOf(false) }
    var blocklistError by remember { mutableStateOf("") }

    LaunchedEffect(context) {
        try {
            withContext(Dispatchers.IO) {
                BlockedHistory.load(context)
                Blocklist.load(context)
            }
            blocklistReady = true
        } catch (e: IOException) {
            blocklistError = e.message ?: "Không thể tải danh sách chặn"
        }
    }

    BackHandler(enabled = showHistory) { showHistory = false }

    if (showBrowser) {
        AdBlockBrowser(onClose = { showBrowser = false })
    } else if (showHistory) {
        BlockedHistoryScreen(onBack = { showHistory = false })
    } else {
        AdBlockScreen(
            blocklistReady = blocklistReady,
            blocklistError = blocklistError,
            onOpenBrowser = { showBrowser = true },
            onOpenHistory = { showHistory = true }
        )
    }
}

@Composable
private fun AdBlockScreen(
    blocklistReady: Boolean,
    blocklistError: String,
    onOpenBrowser: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val context = LocalContext.current
    val running by AdBlockStats.running.collectAsState()
    val blocked by AdBlockStats.blocked.collectAsState()
    val serviceError by AdBlockStats.error.collectAsState()
    val history by BlockedHistory.entries.collectAsState()
    var status by remember { mutableStateOf("") }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* không bắt buộc */ }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startAdBlock(context)
        } else {
            status = "Bạn chưa cấp quyền VPN"
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ADBLOCK", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Bảo vệ kết nối", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Có thể tiếp tục chạy nền khi thu nhỏ ứng dụng.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (serviceError.isNotEmpty()) {
            Text(serviceError, color = MaterialTheme.colorScheme.error)
        }
        if (status.isNotEmpty()) {
            Text(status, color = MaterialTheme.colorScheme.error)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TRẠNG THÁI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (running) "Đang bảo vệ" else "Đang tắt",
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            if (running) "  HOẠT ĐỘNG  " else "  TẠM DỪNG  ",
                            modifier = Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (running) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(blocked.toString(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("yêu cầu bị chặn phiên này", modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        if (running) {
                            stopAdBlock(context)
                        } else {
                            val intent = VpnService.prepare(context)
                            if (intent != null) vpnLauncher.launch(intent) else startAdBlock(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (running) "Tắt bảo vệ" else "Bật bảo vệ")
                }
                Text(
                    "Để tự khởi động lại sau khi khởi động điện thoại, bật VPN luôn bật trong cài đặt Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cài đặt VPN luôn bật")
                }
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cài đặt pin và chạy nền")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(
                title = "ĐÃ LƯU",
                value = history.size.toString(),
                caption = "lượt chặn trong lịch sử",
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "DANH SÁCH",
                value = if (blocklistReady) "Sẵn sàng" else "Đang đọc",
                caption = if (blocklistReady) "đóng gói trong ứng dụng" else "đang đọc file nguồn",
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Danh sách domain được đóng gói trong APK. Sửa file hosts.txt trong mã nguồn và build APK mới để cập nhật.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = onOpenHistory,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)
        ) {
            Text("Xem lịch sử và xuất dữ liệu")
        }

        OutlinedButton(
            onClick = onOpenBrowser,
            enabled = blocklistReady,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(if (blocklistReady) "Mở trình duyệt riêng" else "Đang đọc danh sách chặn...")
        }

        if (blocklistError.isNotEmpty()) {
            Text("Không đọc được danh sách chặn: $blocklistError", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BlockedHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by BlockedHistory.entries.collectAsState()
    val warning by BlockedHistory.warning.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var pendingContent by remember { mutableStateOf("") }

    fun saveDocument(uri: Uri?) {
        if (uri == null) return
        val content = pendingContent
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Không mở được tệp đích")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                }
                status = "Đã lưu tệp thành công"
            } catch (error: IOException) {
                status = "Không thể lưu tệp: ${error.message ?: "lỗi ghi dữ liệu"}"
            }
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
        ::saveDocument
    )
    val textLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
        ::saveDocument
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹  Quay lại")
            }
            Text("Lịch sử chặn", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "${entries.size} sự kiện gần nhất · Tối đa 2.000 · Lưu trên thiết bị này",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (warning.isNotEmpty()) {
            item {
                Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Xuất bản sao lưu", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Chọn vị trí lưu tệp trên thiết bị hoặc bộ nhớ đám mây.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                pendingContent = BlockedHistory.exportJson(entries)
                                jsonLauncher.launch("adblock-history.json")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Xuất JSON")
                        }
                        OutlinedButton(
                            onClick = {
                                pendingContent = BlockedHistory.exportText(entries)
                                textLauncher.launch("adblock-history.txt")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Xuất TXT")
                        }
                    }
                    if (status.isNotEmpty()) {
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        "Chưa có website nào bị chặn. Bật bảo vệ hoặc sử dụng trình duyệt riêng để bắt đầu lưu lịch sử.",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(entry.domain, style = MaterialTheme.typography.titleSmall)
                            Text(
                                DateFormat.getDateTimeInstance().format(Date(entry.timestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                entry.source.displayName,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdBlockBrowser(onClose: () -> Unit) {
    var address by remember { mutableStateOf("https://www.youtube.com") }
    var status by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val openAddress: () -> Unit = {
        val url = normalizeBrowserUrl(address)
        if (url == null) {
            status = "Địa chỉ không hợp lệ"
        } else {
            status = ""
            webView?.loadUrl(url, BrowserPrivacyHeaders)
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Tìm trên YouTube hoặc nhập địa chỉ") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = openAddress) {
                Text("Mở")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { webView?.goBack() },
                enabled = webView?.canGoBack() == true
            ) {
                Text("‹ Quay lại")
            }
            TextButton(
                onClick = { webView?.goForward() },
                enabled = webView?.canGoForward() == true
            ) {
                Text("Tiến ›")
            }
            TextButton(
                onClick = { webView?.reload() },
                enabled = webView != null
            ) {
                Text("Tải lại")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text("Đóng")
            }
        }

        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        AndroidView(
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.safeBrowsingEnabled = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val scheme = request.url.scheme?.lowercase()
                            return scheme != "http" && scheme != "https"
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val host = request.url.host ?: return null
                            if (!Blocklist.isAdRequest(host, request.url.encodedPath.orEmpty())) return null
                            AdBlockStats.blocked.update { it + 1 }
                            BlockedHistory.record(view.context, host, BlockedSource.BROWSER)
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            isLoading = true
                            status = ""
                            if (url != null) address = url
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            isLoading = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            if (request.isForMainFrame) {
                                isLoading = false
                                status = "Không tải được trang: ${error.description}"
                            }
                        }
                    }
                    webView = this
                    loadUrl("https://www.youtube.com", BrowserPrivacyHeaders)
                }
            },
            modifier = Modifier.weight(1f),
            update = { webView = it }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }
}

private fun normalizeBrowserUrl(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null

    val url = if (value.contains(' ') || !value.contains('.')) {
        "https://www.youtube.com/results?search_query=${Uri.encode(value)}"
    } else if (value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)
    ) {
        value
    } else {
        "https://$value"
    }

    val uri = Uri.parse(url)
    return url.takeIf {
        (uri.scheme?.lowercase() == "http" || uri.scheme?.lowercase() == "https") &&
            !uri.host.isNullOrBlank()
    }
}

private fun startAdBlock(context: Context) {
    val intent = Intent(context, AdBlockVpnService::class.java)
        .setAction(AdBlockVpnService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopAdBlock(context: Context) {
    val intent = Intent(context, AdBlockVpnService::class.java)
        .setAction(AdBlockVpnService.ACTION_STOP)
    context.startService(intent)
}
