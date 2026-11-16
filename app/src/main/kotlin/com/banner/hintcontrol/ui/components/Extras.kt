package com.banner.hintcontrol.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Outlined field that opens a menu; avoids the experimental exposed-dropdown API. */
@Composable
fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    text: (T) -> String = { it.toString() },
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text(selected), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Filled.ArrowDropDown, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(text(o)) },
                    onClick = {
                        open = false
                        onSelect(o)
                    },
                    trailingIcon = if (o == selected) ({ Icon(Icons.Filled.Check, null) }) else null,
                )
            }
        }
    }
}

/** Black-on-white QR code (kept light in dark mode so cameras read it). */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val bitmap = remember(content) {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        bmp.asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = "QR code",
        filterQuality = FilterQuality.None,
        modifier = modifier.size(size).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp),
    )
}

/** Wi-Fi join string understood by phone cameras (ZXing "WIFI:" format). */
fun wifiQrText(ssid: String, password: String?, security: String, hidden: Boolean): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
    val type = if (password.isNullOrEmpty()) "nopass" else if (security.contains("WPA3") && !security.contains("WPA2")) "SAE" else "WPA"
    return buildString {
        append("WIFI:T:").append(type).append(";S:").append(esc(ssid)).append(';')
        if (!password.isNullOrEmpty()) append("P:").append(esc(password)).append(';')
        if (hidden) append("H:true;")
        append(';')
    }
}

/** Renders GitHub release notes: headings, bullets, > quotes, **bold** and `code`. */
@Composable
fun MarkdownLite(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(
                        line.drop(level).trim(),
                        style = if (level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                line.startsWith(">") -> {
                    Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(inline(line.drop(1).trim()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = (line.length - line.trimStart().length) / 2
                    Row(Modifier.padding(start = (indent * 14).dp, top = 2.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(inline(line.trimStart().drop(2)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> Text(inline(line), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end < 0) { append(s.substring(i)); return@buildAnnotatedString }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end < 0) { append(s.substring(i)); return@buildAnnotatedString }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            s[i] == '[' && s.indexOf("](", i) > 0 && s.indexOf(')', s.indexOf("](", i)) > 0 -> {
                val mid = s.indexOf("](", i)
                val end = s.indexOf(')', mid)
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(s.substring(i + 1, mid)) }
                i = end + 1
            }
            else -> {
                append(s[i])
                i++
            }
        }
    }
}
