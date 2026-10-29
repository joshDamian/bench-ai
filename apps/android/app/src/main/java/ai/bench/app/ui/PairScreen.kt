package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.bench.app.BenchApp
import ai.bench.app.data.BenchApi
import ai.bench.app.data.BenchError
import ai.bench.app.data.Pairing
import ai.bench.app.data.ServerConfig
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Space
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.launch

/**
 * The first screen: trade a `bench pair` code for this phone's own token. The QR is the
 * fast path; the link can also be pasted, which is how a tablet without a camera pairs.
 */
@Composable
fun PairScreen() {
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(Build.MODEL ?: "Android") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pair(input: String) {
        val parsed = Pairing.parse(input)
        if (parsed == null) {
            error = "That isn't a pairing link. It looks like https://your-machine.ts.net/#/pair/…"
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val config = BenchApi.pair(parsed, name.ifBlank { "Android" })
                BenchApp.instance.settings.save(config)
            } catch (e: BenchError) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { link = it; pair(it) }
    }

    var pasting by rememberSaveable { mutableStateOf(false) }
    val c = Bench.colors
    val scanNow = { scan.launch(ScanOptions().setPrompt("Scan the Bench pairing code").setBeepEnabled(false).setOrientationLocked(false)) }

    Surface(color = c.bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl, vertical = 32.dp),
        ) {
            Column(Modifier.weight(1f).widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
                Box(
                    Modifier.size(72.dp).background(c.surface3, RoundedCornerShape(22.dp)).border(1.dp, c.line, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.QrCodeScanner, null, tint = c.accent, modifier = Modifier.size(32.dp)) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connect to Bench", fontSize = 32.sp, lineHeight = 37.sp)
                    Text(
                        buildAnnotatedString {
                            append("On the machine running Bench, run ")
                            withStyle(SpanStyle(fontFamily = ai.bench.app.ui.theme.Mono, color = c.text.copy(alpha = 0.8f))) { append("bench pair") }
                            append(" and scan the code it shows.")
                        },
                        fontSize = 16.sp, lineHeight = 24.sp, color = c.muted,
                    )
                }
                if (pasting) {
                    OutlinedTextField(
                        link, { link = it }, Modifier.fillMaxWidth(),
                        label = { Text("Pairing link") },
                        placeholder = { Text("https://machine.tailnet.ts.net/#/pair/…") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("This phone's name") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(220.dp).background(c.surface1, RoundedCornerShape(28.dp)).border(1.dp, c.line, RoundedCornerShape(28.dp)).clickable { scanNow() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(140.dp).border(3.dp, c.accent.copy(alpha = 0.8f), RoundedCornerShape(20.dp)))
                        Text("Camera", fontSize = 13.sp, color = c.muted, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
                    }
                }
                error?.let { Text(it, color = c.bad, fontSize = 13.sp) }
            }
            Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pasting) {
                    BigButton(if (busy) "Connecting…" else "Connect", primary = true, height = 56.dp, enabled = !busy && link.isNotBlank()) { pair(link) }
                    TextButton(onClick = { pasting = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Scan a code instead", fontSize = 15.sp, color = c.text.copy(alpha = 0.8f)) }
                } else {
                    BigButton("Scan code", primary = true, height = 56.dp, enabled = !busy) { scanNow() }
                    TextButton(onClick = { pasting = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Paste a link instead", fontSize = 15.sp, color = c.text.copy(alpha = 0.8f)) }
                }
            }
        }
    }
}

/** Saves a config without pairing: used by Settings to re-point at a loopback server in an emulator. */
suspend fun useUnpaired(baseUrl: String) = BenchApp.instance.settings.save(ServerConfig(baseUrl.trimEnd('/'), null))
