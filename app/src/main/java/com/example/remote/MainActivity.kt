package com.example.remote

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
        hideSystemBars()

        setContent {
            RemoteTheme {
                LaunchedEffect(Unit) { hideSystemBars() }
                KeyboardScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
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
    val sender = remember(ip, port) { TcpKeySender(ip, port, scope, enabled = !isPreview) }
    DisposableEffect(sender) { onDispose { sender.close() } }
    return sender
}

/* ---------- Screen with top bar (status + edit) + keyboard/touchpad ---------- */

@Composable
fun KeyboardScreen(modifier: Modifier = Modifier) {
    var ip by rememberSaveable { mutableStateOf("10.0.0.118") }
    var port by rememberSaveable { mutableStateOf(7642) }
    var showEdit by rememberSaveable { mutableStateOf(false) }

    val sender = rememberTcpSender(ip = ip, port = port)
    val connected by sender.connected.collectAsState(initial = false)
    val send: (String) -> Unit = remember { { line -> sender.send(line) } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ConnectionIndicatorBar(
                connected = connected,
                endpoint = "$ip:$port",
                onEdit = { showEdit = true }
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
                        onRightClick = { send("mouse_click right") },
                        onScroll = { steps -> send("mouse_scroll $steps") }
                    )
                }
            }
        }
    }

    if (showEdit) {
        EditEndpointDialog(
            initialIp = ip,
            initialPort = port,
            onDismiss = { showEdit = false },
            onSave = { newIp, newPort ->
                ip = newIp
                port = newPort
                showEdit = false
            }
        )
    }
}

/* ---------- Top bar with status + edit button ---------- */

@Composable
private fun ConnectionIndicatorBar(
    connected: Boolean,
    endpoint: String,
    onEdit: () -> Unit
) {
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
            modifier = Modifier.height(28.dp)
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
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit connection")
            }
        }
        Divider(color = Color(0x22000000))
    }
}

/* ---------- Edit endpoint dialog ---------- */

@Composable
private fun EditEndpointDialog(
    initialIp: String,
    initialPort: Int,
    onDismiss: () -> Unit,
    onSave: (ip: String, port: Int) -> Unit
) {
    var ip by rememberSaveable { mutableStateOf(initialIp) }
    var portText by rememberSaveable { mutableStateOf(initialPort.toString()) }

    val portInt = portText.toIntOrNull()
    val portValid = portInt != null && portInt in 1..65535
    val ipValid = ip.isNotBlank()
    val canSave = ipValid && portValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    singleLine = true,
                    label = { Text("IP address / host") }
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { new ->
                        if (new.all { it.isDigit() } || new.isEmpty()) portText = new
                    },
                    singleLine = true,
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (!ipValid) Text("Enter a host/IP.", color = Color(0xFFC62828), fontSize = 12.sp)
                if (!portValid) Text("Port must be 1–65535.", color = Color(0xFFC62828), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(ip, portInt!!) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/* ---------- Touchpad panel with Hold-to-Scroll buttons ---------- */

@Composable
fun TouchpadPanel(
    modifier: Modifier = Modifier,
    onMove: (dx: Int, dy: Int) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (steps: Int) -> Unit
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

            // Bottom row: Left Click | [▲/▼ hold buttons] | Right Click
            val buttonGap = 8.dp
            val centerWidth = 56.dp
            val buttonWidth = (this@pad.maxWidth - buttonGap * 2 - centerWidth) / 2f

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(buttonGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClickButton(
                    label = "Left Click",
                    width = buttonWidth,
                    height = btnHeight,
                    onClick = onLeftClick
                )

                Column(
                    modifier = Modifier.width(centerWidth),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HoldScrollButton(
                        label = "▲",
                        width = centerWidth,
                        height = (btnHeight - 4.dp) / 2f,
                        step = -1,                 // up = negative steps
                        onScroll = onScroll
                    )
                    Spacer(Modifier.height(4.dp))
                    HoldScrollButton(
                        label = "▼",
                        width = centerWidth,
                        height = (btnHeight - 4.dp) / 2f,
                        step = +1,                 // down = positive steps
                        onScroll = onScroll
                    )
                }

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

@Composable
private fun HoldScrollButton(
    label: String,
    width: Dp,
    height: Dp,
    step: Int,
    onScroll: (Int) -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    val keyBg = Color(0xFF2B2B2B)
    val keyBorder = Color(0xFF444444)
    val keyText = Color.White
    val scope = rememberCoroutineScope()

    var pressed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun startRepeating() {
        if (job?.isActive == true) return
        job = scope.launch {
            // simple acceleration: start slower then speed up
            var delayMs = 80L
            while (isActive) {
                onScroll(step)
                delay(delayMs)
                if (delayMs > 20L) delayMs = (delayMs * 0.9f).toLong().coerceAtLeast(20L)
            }
        }
    }

    fun stopRepeating() {
        job?.cancel()
        job = null
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(keyBg)
            .border(BorderStroke(1.dp, keyBorder), shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        startRepeating()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            stopRepeating()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (pressed) keyText else keyText.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ---------- Click button helper ---------- */

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

/* ---------- ANSI TKL Keyboard (wider main keys + smaller nav/arrow) ---------- */

@Composable
fun TklAnsiKeyboard(
    modifier: Modifier = Modifier,
    onSend: (String) -> Unit
) {
    BoxWithConstraints(modifier) kb@{
        val totalW = this@kb.maxWidth

        val kbGap = 8.dp
        val mainW = totalW * 0.82f
        val navW  = totalW - mainW - kbGap

        val rowGap = 4.dp
        val keyH = 52.dp
        val navKeyH = 38.dp
        val arrowKeyH = 36.dp

        Row(Modifier.fillMaxSize()) {

            /* -------- Left: main keys -------- */
            Column(Modifier.width(mainW)) {

                // Function row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(rowGap)
                ) {
                    val fUnit = (mainW - rowGap * 14) / 15f
                    KeyCap("Esc", fUnit * 1f, keyH) { onSend("esc") }
                    (1..12).forEach { i ->
                        KeyCap("F$i", fUnit, keyH) { onSend("f$i") }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Row 1
                val r1 = RowSpec(totalUnits = 15f, count = 14)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    DualKey("`", "~", r1.unit(mainW,rowGap), keyH) { onSend("`") }
                    DualKey("1", "!", r1.unit(mainW,rowGap), keyH) { onSend("1") }
                    DualKey("2", "@", r1.unit(mainW,rowGap), keyH) { onSend("2") }
                    DualKey("3", "#", r1.unit(mainW,rowGap), keyH) { onSend("3") }
                    DualKey("4", "$", r1.unit(mainW,rowGap), keyH) { onSend("4") }
                    DualKey("5", "%", r1.unit(mainW,rowGap), keyH) { onSend("5") }
                    DualKey("6", "^", r1.unit(mainW,rowGap), keyH) { onSend("6") }
                    DualKey("7", "&", r1.unit(mainW,rowGap), keyH) { onSend("7") }
                    DualKey("8", "*", r1.unit(mainW,rowGap), keyH) { onSend("8") }
                    DualKey("9", "(", r1.unit(mainW,rowGap), keyH) { onSend("9") }
                    DualKey("0", ")", r1.unit(mainW,rowGap), keyH) { onSend("0") }
                    DualKey("-", "_", r1.unit(mainW,rowGap), keyH) { onSend("-") }
                    DualKey("=", "+", r1.unit(mainW,rowGap), keyH) { onSend("=") }
                    KeyCap("⌫", r1.unit(mainW,rowGap) * 2f, keyH) { onSend("backspace") }
                }

                // Row 2
                val r2 = RowSpec(totalUnits = 15f, count = 14)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    KeyCap("↹", r2.unit(mainW,rowGap) * 1.5f, keyH) { onSend("tab") }
                    "QWERTYUIOP".forEach { ch ->
                        LetterKey(ch, r2.unit(mainW,rowGap), keyH) { onSend(it) }
                    }
                    DualKey("[", "{", r2.unit(mainW,rowGap), keyH) { onSend("[") }
                    DualKey("]", "}", r2.unit(mainW,rowGap), keyH) { onSend("]") }
                    DualKey("\\", "|", r2.unit(mainW,rowGap) * 1.5f, keyH) { onSend("\\") }
                }

                // Row 3
                val r3 = RowSpec(totalUnits = 15f, count = 13)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    KeyCap("⇪", r3.unit(mainW,rowGap) * 1.75f, keyH) { onSend("capslock") }
                    "ASDFGHJKL".forEach { ch ->
                        LetterKey(ch, r3.unit(mainW,rowGap), keyH) { onSend(it) }
                    }
                    DualKey(";", ":", r3.unit(mainW,rowGap), keyH) { onSend(";") }
                    DualKey("'", "\"", r3.unit(mainW,rowGap), keyH) { onSend("'") }
                    KeyCap("⏎", r3.unit(mainW,rowGap) * 2.25f, keyH) { onSend("enter") }
                }

                // Row 4
                val r4 = RowSpec(totalUnits = 15f, count = 12)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    KeyCap("⇧", r4.unit(mainW,rowGap) * 2.25f, keyH) { onSend("shift") }
                    "ZXCVBNM".forEach { ch ->
                        LetterKey(ch, r4.unit(mainW,rowGap), keyH) { onSend(it) }
                    }
                    DualKey(",", "<", r4.unit(mainW,rowGap), keyH) { onSend(",") }
                    DualKey(".", ">", r4.unit(mainW,rowGap), keyH) { onSend(".") }
                    DualKey("/", "?", r4.unit(mainW,rowGap), keyH) { onSend("/") }
                    KeyCap("⇧", r4.unit(mainW,rowGap) * 2.75f, keyH) { onSend("shift") }
                }

                // Row 5
                val r5 = RowSpec(totalUnits = 15f, count = 8)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                    KeyCap("⎈", r5.unit(mainW,rowGap) * 1.25f, keyH) { onSend("ctrl") }
                    KeyCap("⊞", r5.unit(mainW,rowGap) * 1.25f, keyH) { onSend("winleft") }
                    KeyCap("⎇", r5.unit(mainW,rowGap) * 1.25f, keyH) { onSend("alt") }
                    KeyCap("␣", r5.unit(mainW,rowGap) * 6.25f, keyH) { onSend("space") }
                    KeyCap("⎇", r5.unit(mainW,rowGap) * 1.25f, keyH) { onSend("alt") }
                    KeyCap("⊞", r5.unit(mainW,rowGap) * 1.25f, keyH) { onSend("winright") }
                    KeyCap("☰", r5.unit(mainW,rowGap) * 1.25f, keyH) { onSend("apps") }
                    KeyCap("⎈", r5.unit(mainW,rowGap) * 1.25f, keyH) { onSend("ctrl") }
                }
            }

            Spacer(Modifier.width(kbGap))

            /* -------- Right: nav + arrows (smaller) -------- */
            Column(Modifier.width(navW), horizontalAlignment = Alignment.CenterHorizontally) {
                val navGap = 6.dp

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(navGap)) {
                    val u = (navW - navGap * 2) / 3f
                    KeyCap("Ins", u, navKeyH) { onSend("insert") }
                    KeyCap("Home", u, navKeyH) { onSend("home") }
                    KeyCap("PgUp", u, navKeyH) { onSend("pageup") }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(navGap)) {
                    val u = (navW - navGap * 2) / 3f
                    KeyCap("Del", u, navKeyH) { onSend("delete") }
                    KeyCap("End", u, navKeyH) { onSend("end") }
                    KeyCap("PgDn", u, navKeyH) { onSend("pagedown") }
                }

                Spacer(Modifier.height(8.dp))

                val arrowW = (navW - navGap * 2) / 3f
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Spacer(Modifier.width(arrowW))
                    KeyCap("↑", arrowW,  arrowKeyH) { onSend("up") }
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
