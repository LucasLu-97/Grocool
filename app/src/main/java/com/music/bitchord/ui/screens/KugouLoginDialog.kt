package com.music.bitchord.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.music.bitchord.data.kugou.KugouLoginManager
import com.music.bitchord.data.kugou.KugouLoginState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The KuGou Concept QR login dialog: device registration → live QR → poll
 * → session persisted. The QR auto-renews when KuGou expires it (~2min per
 * code), so nobody has to race the clock — the finding from the smoke, where
 * two codes died before the third got scanned in peace.
 *
 * The code renders as solid, square, black-on-white modules with ZXing's own
 * 4-module quiet zone baked into the matrix — deliberately NOT the chassis'
 * rounded-dot party QR, whose 14% inter-module gaps read fine off a desktop
 * monitor but failed detection when the KuGou scanner pointed at a phone
 * screen (moiré + gaps + rounded finders = no lock). Solid squares at
 * integer-aligned pixel edges is the shape the smoke's scan actually
 * succeeded against.
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
                        SolidQr(content = s.qrContent, size = 240.dp)
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

/**
 * A scan-first QR: solid black squares on white, ZXing's standard quiet zone
 * inside the matrix, modules drawn at integer-aligned edges so neighbouring
 * cells share exact boundaries (no hairline seams from float rounding).
 */
@Composable
private fun SolidQr(content: String, size: androidx.compose.ui.unit.Dp) {
    val density = LocalDensity.current
    val sidePx = with(density) { size.roundToPx() }

    val image by produceState<ImageBitmap?>(null, content, sidePx) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val matrix = QRCodeWriter().encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    256,
                    256,
                    mapOf(
                        // Default 4-module quiet zone, kept inside the matrix so
                        // the rendered image is scannable edge to edge.
                        EncodeHintType.MARGIN to 4,
                        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                        EncodeHintType.CHARACTER_SET to "UTF-8",
                    ),
                )
                val modules = matrix.width
                val bitmap = ImageBitmap(sidePx, sidePx)
                val side = sidePx.toFloat()
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(side, side)) {
                    fun edge(index: Int) = (sidePx * index / modules).toFloat()
                    for (y in 0 until modules) {
                        for (x in 0 until modules) {
                            if (matrix.get(x, y)) {
                                drawRect(
                                    color = Color.Black,
                                    topLeft = Offset(edge(x), edge(y)),
                                    size = Size(edge(x + 1) - edge(x), edge(y + 1) - edge(y)),
                                )
                            }
                        }
                    }
                }
                bitmap
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
