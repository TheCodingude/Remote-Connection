package com.example.remote

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remote.ui.theme.RemoteTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enableEdgeToEdge()
        setContent {
            RemoteTheme {
                KeyboardScreen()
            }
        }
    }
}

/* ---------- TCP sender with connection state (preview-safe) ---------- */

private class TcpKeySender(
    private val host: String,
    private val port: Int,
    private val scope: CoroutineScope,
    private val enabled: Boolean = true
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var loopJob: Job? = null

    init {
        if (enabled) {
            loopJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (writer == null) tryConnect()
                    delay(2000)
                }
            }
        }
    }

    private fun tryConnect() {
        if (!enabled) return
        if (socket?.isConnected == true && writer != null) {
            _connected.value = true
            return
        }
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 1500)
            s.tcpNoDelay = true
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            _connected.value = true
        } catch (_: Exception) {
            safeClose()
            _connected.value = false
        }
    }

    fun send(line: String) {
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            if (writer == null) tryConnect()
            try {
                writer?.apply { write(line); write("\n"); flush() }
                    ?: run {
                        tryConnect()
                        writer?.apply { write(line); write("\n"); flush() }
                    }
            } catch (_: Exception) {
                _connected.value = false
                safeClose()
            }
        }
    }

    fun close() {
        if (!enabled) return
        loopJob?.cancel()
        scope.launch(Dispatchers.IO) { safeClose() }
    }

    private fun safeClose() {
        try { writer?.flush() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
        _connected.value = false
    }
}

@Composable
private fun rememberTcpSender(ip: String, port: Int): TcpKeySender {
    val scope = rememberCoroutineScope()
    val isPreview = LocalInspectionMode.current
    val sender = remember { TcpKeySender(ip, port, scope, enabled = !isPreview) }
    DisposableEffect(Unit) { onDispose { sender.close() } }
    return sender
}

/* ---------- Screen with fixed top connection indicator + keyboard + touchpad ---------- */

@Composable
fun KeyboardScreen(modifier: Modifier = Modifier) {
    val sender = rememberTcpSender(ip = "10.0.0.118", port = 7642)
    val connected by sender.connected.collectAsState(initial = false)
    val send: (String) -> Unit = remember { { line -> sender.send(line) } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ConnectionIndicatorBar(
                connected = connected,
                endpoint = "10.0.0.118:7642"
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) box@{
            val totalW = this@box.maxWidth
            val gap = 10.dp

            // Wider main keyboard, narrower nav/arrow stack
            val leftW = totalW * 0.76f
            val rightW = totalW - leftW - 8.dp

            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(leftW).fillMaxHeight()) {
                    TklAnsiKeyboard(
                        modifier = Modifier.fillMaxSize(),
                        onSend = send
                    )
                }
                Spacer(Modifier.width(gap))
                Box(Modifier.width(rightW).fillMaxHeight()) {
                    TouchpadPanel(
                        modifier = Modifier.fillMaxSize(),
                        onMove = { dx, dy -> send("mouse_move $dx $dy") },
                        onLeftClick = { send("mouse_click left") },
                        onRightClick = { send("mouse_click right") }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionIndicatorBar(connected: Boolean, endpoint: String) {
    val color = if (connected) Color(0xFF2E7D32) else Color(0xFFC62828)
    val label = if (connected) "Connected" else "Disconnected"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(24.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$label • $endpoint",
                color = color,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        Divider(color = Color(0x22000000))
    }
}

/* ---------- Touchpad panel (no weight) ---------- */

@Composable
fun TouchpadPanel(
    modifier: Modifier = Modifier,
    onMove: (dx: Int, dy: Int) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit
) {
    val padShape = RoundedCornerShape(10.dp)
    val padBg = Color(0xFF151515)
    val padBorder = Color(0xFF3A3A3A)
    val textColor = Color.White
    val sensitivity = 0.7f
    val btnHeight = 44.dp
    val gap = 8.dp

    BoxWithConstraints(modifier.padding(8.dp)) pad@{
        val padHeight = (this@pad.maxHeight - btnHeight - gap).coerceAtLeast(64.dp)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Touchpad area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(padHeight)
                    .clip(padShape)
                    .background(padBg)
                    .border(BorderStroke(1.dp, padBorder), padShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { _, dragAmount ->
                                val dx = (dragAmount.x * sensitivity).roundToInt()
                                val dy = (dragAmount.y * sensitivity).roundToInt()
                                if (dx != 0 || dy != 0) onMove(dx, dy)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("Touchpad", color = textColor.copy(alpha = 0.35f), fontSize = 12.sp)
            }

            Spacer(Modifier.height(gap))

            // Buttons row — equal width without weight
            val buttonGap = 8.dp
            val buttonWidth = (this@pad.maxWidth - buttonGap) / 2f
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(buttonGap)
            ) {
                ClickButton(
                    label = "Left Click",
                    width = buttonWidth,
                    height = btnHeight,
                    onClick = onLeftClick
                )
                ClickButton(
                    label = "Right Click",
                    width = buttonWidth,
                    height = btnHeight,
                    onClick = onRightClick
                )
            }
        }
    }
}

/* ---------- Click buttons ---------- */

@Composable
private fun ClickButton(
    label: String,
    width: Dp,
    height: Dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    val keyBg = Color(0xFF2B2B2B)
    val keyBorder = Color(0xFF444444)
    val keyText = Color.White
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(keyBg)
            .border(BorderStroke(1.dp, keyBorder), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = keyText, fontWeight = FontWeight.SemiBold) }
}

/* ---------- ANSI TKL Keyboard (unit sizing, no weight) ---------- */

@Composable
fun TklAnsiKeyboard(
    modifier: Modifier = Modifier,
    onSend: (String) -> Unit
) {
    BoxWithConstraints(modifier) kb@{
        val totalW = this@kb.maxWidth

        // Wider keys on the left block
        val leftW = totalW * 0.76f
        val rightW = totalW - leftW - 8.dp

        val rowGap = 4.dp
        val keyH = 52.dp       // bigger main-block keys

        Row(Modifier.fillMaxSize()) {

            /* -------- Left block: main keys -------- */
            Column(Modifier.width(leftW)) {

                // Function row (approx equal width)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(rowGap)
                ) {
                    val fUnit = (leftW - rowGap * 14) / 15f
                    KeyCap("Esc", fUnit * 1f, keyH) { onSend("esc") }
                    (1..12).forEach { i ->
                        KeyCap("F$i", fUnit, keyH) { onSend("f$i") }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Row 1: ` 1..0 - = ⌫
                val r1 = RowSpec(totalUnits = 15f, count = 14)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    DualKey("`", "~", r1.unit(leftW,rowGap), keyH) { onSend("`") }
                    DualKey("1", "!", r1.unit(leftW,rowGap), keyH) { onSend("1") }
                    DualKey("2", "@", r1.unit(leftW,rowGap), keyH) { onSend("2") }
                    DualKey("3", "#", r1.unit(leftW,rowGap), keyH) { onSend("3") }
                    DualKey("4", "$", r1.unit(leftW,rowGap), keyH) { onSend("4") }
                    DualKey("5", "%", r1.unit(leftW,rowGap), keyH) { onSend("5") }
                    DualKey("6", "^", r1.unit(leftW,rowGap), keyH) { onSend("6") }
                    DualKey("7", "&", r1.unit(leftW,rowGap), keyH) { onSend("7") }
                    DualKey("8", "*", r1.unit(leftW,rowGap), keyH) { onSend("8") }
                    DualKey("9", "(", r1.unit(leftW,rowGap), keyH) { onSend("9") }
                    DualKey("0", ")", r1.unit(leftW,rowGap), keyH) { onSend("0") }
                    DualKey("-", "_", r1.unit(leftW,rowGap), keyH) { onSend("-") }
                    DualKey("=", "+", r1.unit(leftW,rowGap), keyH) { onSend("=") }
                    KeyCap("⌫", r1.unit(leftW,rowGap) * 2f, keyH) { onSend("backspace") }
                }

                // Row 2: ↹ Q..P [ ] \
                val r2 = RowSpec(totalUnits = 15f, count = 14)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    KeyCap("↹", r2.unit(leftW,rowGap) * 1.5f, keyH) { onSend("tab") }
                    "QWERTYUIOP".forEach { ch ->
                        LetterKey(ch, r2.unit(leftW,rowGap), keyH) { onSend(it) }
                    }
                    DualKey("[", "{", r2.unit(leftW,rowGap), keyH) { onSend("[") }
                    DualKey("]", "}", r2.unit(leftW,rowGap), keyH) { onSend("]") }
                    DualKey("\\", "|", r2.unit(leftW,rowGap) * 1.5f, keyH) { onSend("\\") }
                }

                // Row 3: ⇪ A..L ; ' ⏎
                val r3 = RowSpec(totalUnits = 15f, count = 13)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    KeyCap("⇪", r3.unit(leftW,rowGap) * 1.75f, keyH) { onSend("capslock") }
                    "ASDFGHJKL".forEach { ch ->
                        LetterKey(ch, r3.unit(leftW,rowGap), keyH) { onSend(it) }
                    }
                    DualKey(";", ":", r3.unit(leftW,rowGap), keyH) { onSend(";") }
                    DualKey("'", "\"", r3.unit(leftW,rowGap), keyH) { onSend("'") }
                    KeyCap("⏎", r3.unit(leftW,rowGap) * 2.25f, keyH) { onSend("enter") }
                }

                // Row 4: ⇧ Z..M , . / ⇧
                val r4 = RowSpec(totalUnits = 15f, count = 12)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    KeyCap("⇧", r4.unit(leftW,rowGap) * 2.25f, keyH) { onSend("shift") }
                    "ZXCVBNM".forEach { ch ->
                        LetterKey(ch, r4.unit(leftW,rowGap), keyH) { onSend(it) }
                    }
                    DualKey(",", "<", r4.unit(leftW,rowGap), keyH) { onSend(",") }
                    DualKey(".", ">", r4.unit(leftW,rowGap), keyH) { onSend(".") }
                    DualKey("/", "?", r4.unit(leftW,rowGap), keyH) { onSend("/") }
                    KeyCap("⇧", r4.unit(leftW,rowGap) * 2.75f, keyH) { onSend("shift") }
                }

                // Row 5: ⎈ ⊞ ⎇ [Space] ⎇ ⊞ ☰ ⎈
                val r5 = RowSpec(totalUnits = 15f, count = 8)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    KeyCap("⎈", r5.unit(leftW,rowGap) * 1.25f, keyH) { onSend("ctrl") }
                    KeyCap("⊞", r5.unit(leftW,rowGap) * 1.25f, keyH) { onSend("winleft") }
                    KeyCap("⎇", r5.unit(leftW,rowGap) * 1.25f, keyH) { onSend("alt") }
                    KeyCap("␣", r5.unit(leftW,rowGap) * 6.25f, keyH) { onSend("space") }
                    KeyCap("⎇", r5.unit(leftW,rowGap) * 1.25f, keyH) { onSend("alt") }
                    KeyCap("⊞", r5.unit(leftW,rowGap) * 1.25f, keyH) { onSend("winright") }
                    KeyCap("☰", r5.unit(leftW,rowGap) * 1.25f, keyH) { onSend("apps") }
                    KeyCap("⎈", r5.unit(leftW,rowGap) * 1.25f, keyH) { onSend("ctrl") }
                }
            }

            Spacer(Modifier.width(8.dp))

            /* -------- Right block: nav + arrows (smaller) -------- */
            Column(Modifier.width(rightW), horizontalAlignment = Alignment.CenterHorizontally) {
                val navGap = 6.dp
                val navKeyH = 38.dp
                val arrowKeyH = 36.dp

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(navGap)) {
                    val u = (rightW - navGap * 2) / 3f
                    KeyCap("Ins", u, navKeyH) { onSend("insert") }
                    KeyCap("Home", u, navKeyH) { onSend("home") }
                    KeyCap("PgUp", u, navKeyH) { onSend("pageup") }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(navGap)) {
                    val u = (rightW - navGap * 2) / 3f
                    KeyCap("Del", u, navKeyH) { onSend("delete") }
                    KeyCap("End", u, navKeyH) { onSend("end") }
                    KeyCap("PgDn", u, navKeyH) { onSend("pagedown") }
                }

                Spacer(Modifier.height(8.dp))

                val arrowW = (rightW - navGap * 2) / 3f
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Spacer(Modifier.width(arrowW))
                    KeyCap("↑", arrowW, arrowKeyH) { onSend("up") }
                    Spacer(Modifier.width(arrowW))
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(navGap)) {
                    KeyCap("←", arrowW, arrowKeyH) { onSend("left") }
                    KeyCap("↓", arrowW, arrowKeyH) { onSend("down") }
                    KeyCap("→", arrowW, arrowKeyH) { onSend("right") }
                }
            }
        }
    }
}

/* ---------- Keycap helpers ---------- */

private val KeyShape = RoundedCornerShape(6.dp)
private val KeyBg = Color(0xFF2B2B2B)
private val KeyBorder = Color(0xFF444444)
private val KeyText = Color(0xFFFFFFFF)

private data class RowSpec(val totalUnits: Float, val count: Int)
private fun RowSpec.unit(rowWidth: Dp, gap: Dp): Dp {
    return (rowWidth - gap * (count - 1)) / totalUnits
}

@Composable
private fun KeyCap(
    label: String,
    width: Dp,
    height: Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(KeyShape)
            .background(KeyBg)
            .border(BorderStroke(1.dp, KeyBorder), KeyShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = KeyText, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun LetterKey(
    upper: Char,
    width: Dp,
    height: Dp,
    onSendLower: (String) -> Unit
) = KeyCap(upper.toString(), width, height) { onSendLower(upper.lowercaseChar().toString()) }

@Composable
private fun DualKey(
    lower: String,
    upper: String,
    width: Dp,
    height: Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(KeyShape)
            .background(KeyBg)
            .border(BorderStroke(1.dp, KeyBorder), KeyShape)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxSize()) {
            Text(
                upper,
                fontSize = 11.sp,
                color = KeyText.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 2.dp)
            )
            Text(
                lower,
                fontSize = 16.sp,
                color = KeyText,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = 2.dp)
            )
        }
    }
}

/* ---------- Preview ---------- */

@Preview(showBackground = true, widthDp = 980, heightDp = 420)
@Composable
fun TklKeyboardPreview() {
    RemoteTheme {
        KeyboardScreen()
    }
}
