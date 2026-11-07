package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.kugou.KugouLoginManager
import com.music.bitchord.data.kugou.KugouLoginState
import com.music.bitchord.ui.components.QrCode
import kotlinx.coroutines.delay

/**
 * The KuGou Concept QR login dialog: device registration → live QR → poll
 * → session persisted. The QR auto-renews when KuGou expires it (~2min per
 * code), so nobody has to race the clock — the finding from the smoke, where
 * two codes died before the third got scanned in peace.
 *
 * Auto-dismisses a beat after success; the sources screen re-probes health on
 * its own, so the row will say 已登录 when the user next sees it.
 */
@Composable
fun KugouLoginDialog(
    manager: KugouLoginManager,
    onDismiss: () -> Unit,
) {
    val state by manager.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { manager.start() }
    DisposableEffect(Unit) { onDispose { manager.stop() } }

    LaunchedEffect(state) {
        if (state is KugouLoginState.Success) {
            delay(1500)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("酷狗概念版登录") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (val s = state) {
                    is KugouLoginState.Preparing -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在准备登录…", textAlign = TextAlign.Center)
                    }
                    is KugouLoginState.ShowingQr -> {
                        QrCode(content = s.qrContent, size = 220.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (s.scanned) "已扫码，请在手机上确认登录" else "用「酷狗音乐概念版」App 扫码登录",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "二维码 ${s.round}/30 · 过期自动更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is KugouLoginState.Success -> {
                        Text("登录成功 ✓", textAlign = TextAlign.Center)
                        Text(
                            "userid: ${s.userid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is KugouLoginState.Failed -> {
                        Text(
                            s.message,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { manager.start() }) { Text("重试") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {},
    )
}
