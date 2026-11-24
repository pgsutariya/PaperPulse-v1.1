package com.example.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * In-app Broker OAuth WebView Dialog.
 * Automatically intercepts ANY broker redirect URL carrying `auth_token`, `request_token`,
 * `access_token`, `feed_token`, `code`, or the `paperpulse://broker-callback` scheme, captures
 * the token, closes the WebView, and returns immediately to PaperPulse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrokerOAuthWebViewDialog(
    brokerKey: String,
    brokerTitle: String,
    loginUrl: String,
    onTokenCaptured: (broker: String, token: String, feedToken: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(loginUrl) }
    var manualInput by remember { mutableStateOf("") }
    var captured by remember { mutableStateOf(false) }

    fun tryExtractAndComplete(rawUrlOrToken: String): Boolean {
        if (captured) return true
        val extracted = extractBrokerToken(rawUrlOrToken)
        if (extracted != null) {
            captured = true
            onTokenCaptured(brokerKey, extracted.first, extracted.second)
            return true
        }
        return false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("$brokerTitle 1-Click Login", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Auto-returns to PaperPulse once login finishes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("broker_oauth_close")) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close login")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                                }
                            },
                            modifier = Modifier.testTag("broker_oauth_external")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open in browser")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualInput,
                            onValueChange = { manualInput = it },
                            label = { Text("Or paste callback URL / token") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("broker_oauth_manual_input")
                        )
                        Button(
                            onClick = {
                                val trimmed = manualInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    if (!tryExtractAndComplete(trimmed)) {
                                        // Treat raw non-URL pasted text directly as the access/request token
                                        captured = true
                                        onTokenCaptured(brokerKey, trimmed, null)
                                    }
                                }
                            },
                            enabled = manualInput.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("broker_oauth_apply_button")
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Apply")
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    currentUrl = url
                                    if (tryExtractAndComplete(url)) {
                                        return true
                                    }
                                    if (url.startsWith("paperpulse://", ignoreCase = true)) {
                                        // Custom scheme redirect reached even without recognized query param -> return to app cleanly
                                        onDismiss()
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        tryExtractAndComplete(url)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    if (!url.isNullOrBlank()) {
                                        tryExtractAndComplete(url)
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    val url = request?.url?.toString().orEmpty()
                                    if (tryExtractAndComplete(url)) {
                                        return
                                    }
                                    isLoading = false
                                }
                            }
                            loadUrl(loginUrl)
                        }
                    }
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                    )
                }
            }
        }
    }
}

private fun extractBrokerToken(rawUrl: String): Pair<String, String?>? {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
    val token = uri.getQueryParameter("auth_token")
        ?: uri.getQueryParameter("request_token")
        ?: uri.getQueryParameter("access_token")
        ?: uri.getQueryParameter("code")
        ?: extractFragmentParam(uri.fragment, listOf("auth_token", "request_token", "access_token", "code"))
    val feedToken = uri.getQueryParameter("feed_token")
        ?: extractFragmentParam(uri.fragment, listOf("feed_token"))
    return if (!token.isNullOrBlank()) token.trim() to feedToken?.trim() else null
}

private fun extractFragmentParam(fragment: String?, keys: List<String>): String? {
    if (fragment.isNullOrBlank()) return null
    val pairs = fragment.split("&").mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx > 0) part.substring(0, idx) to Uri.decode(part.substring(idx + 1)) else null
    }.toMap()
    return keys.firstNotNullOfOrNull { k -> pairs[k]?.takeIf { it.isNotBlank() } }
}
