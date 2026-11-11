package com.example.adblock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
    var blocklistReady by remember { mutableStateOf(false) }
    var blocklistError by remember { mutableStateOf("") }

    LaunchedEffect(context) {
        try {
            withContext(Dispatchers.IO) { Blocklist.load(context) }
            blocklistReady = true
        } catch (e: IOException) {
            blocklistError = e.message ?: "Không thể tải danh sách chặn"
        }
    }

    if (showBrowser) {
        AdBlockBrowser(onClose = { showBrowser = false })
    } else {
        AdBlockScreen(
            blocklistReady = blocklistReady,
            blocklistError = blocklistError,
            onOpenBrowser = { showBrowser = true }
        )
    }
}

@Composable
private fun AdBlockScreen(
    blocklistReady: Boolean,
    blocklistError: String,
    onOpenBrowser: () -> Unit
) {
    val context = LocalContext.current
    val running by AdBlockStats.running.collectAsState()
    val blocked by AdBlockStats.blocked.collectAsState()
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("AdBlock", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (running) "Trạng thái: Đang chặn" else "Trạng thái: Đã tắt",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text("Đã chặn: $blocked yêu cầu", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))

        Button(onClick = {
            if (running) {
                stopAdBlock(context)
            } else {
                val intent = VpnService.prepare(context)
                if (intent != null) vpnLauncher.launch(intent) else startAdBlock(context)
            }
        }) {
            Text(if (running) "Tắt chặn quảng cáo" else "Bật chặn quảng cáo")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onOpenBrowser,
            enabled = blocklistReady
        ) {
            Text(if (blocklistReady) "Mở trình duyệt chặn quảng cáo" else "Đang tải danh sách chặn...")
        }

        if (blocklistError.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Không tải được danh sách chặn: $blocklistError")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = {
            status = "Đang cập nhật danh sách..."
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { Blocklist.update(context) }
                }
                status = result.fold(
                    onSuccess = { "Đã tải $it tên miền" },
                    onFailure = { "Lỗi: ${it.message}" }
                )
            }
        }) {
            Text("Cập nhật danh sách chặn")
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AdBlockBrowser(onClose: () -> Unit) {
    var address by remember { mutableStateOf("https://www.google.com") }
    var status by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val openAddress: () -> Unit = {
        val url = normalizeBrowserUrl(address)
        if (url == null) {
            status = "Địa chỉ không hợp lệ"
        } else {
            status = ""
            webView?.loadUrl(url)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Địa chỉ hoặc từ khóa") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = openAddress) {
                Text("Mở")
            }
        }

        Row {
            TextButton(
                onClick = { if (webView?.canGoBack() == true) webView?.goBack() },
                enabled = webView?.canGoBack() == true
            ) {
                Text("Quay lại")
            }
            TextButton(onClick = onClose) {
                Text("Đóng trình duyệt")
            }
        }

        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }

        AndroidView(
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
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
                            if (!Blocklist.isBlocked(host)) return null
                            AdBlockStats.blocked.update { it + 1 }
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
                            if (url != null) address = url
                        }
                    }
                    webView = this
                    loadUrl("https://www.google.com")
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
        "https://www.google.com/search?q=${Uri.encode(value)}"
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
