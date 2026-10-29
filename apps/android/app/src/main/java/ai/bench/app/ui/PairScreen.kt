package ai.bench.app.ui

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

    Surface(color = Bench.colors.bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.lg, Alignment.CenterVertically),
        ) {
            Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                Text("Pair with Bench", style = MaterialTheme.typography.titleLarge)
                MutedText("On the machine running Bench, run `bench pair` or open Settings → Pair a device. Scan the code it shows, or paste its link.")
                Button(onClick = {
                    scan.launch(ScanOptions().setPrompt("Scan the Bench pairing code").setBeepEnabled(false).setOrientationLocked(false))
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, null, Modifier.padding(end = Space.sm))
                    Text("Scan pairing code")
                }
                HorizontalDivider(color = Bench.colors.line)
                OutlinedTextField(
                    link, { link = it }, Modifier.fillMaxWidth(),
                    label = { Text("Pairing link") },
                    placeholder = { Text("https://machine.tailnet.ts.net/#/pair/…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("This device's name") }, singleLine = true)
                OutlinedButton(onClick = { pair(link) }, enabled = !busy && link.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Pair")
                }
                error?.let { Text(it, color = Bench.colors.bad, style = MaterialTheme.typography.bodySmall) }
                MutedText("The link is good for ten minutes and works once. Your phone reaches the server over Tailscale (`bench share`).")
            }
        }
    }
}

/** Saves a config without pairing: used by Settings to re-point at a loopback server in an emulator. */
suspend fun useUnpaired(baseUrl: String) = BenchApp.instance.settings.save(ServerConfig(baseUrl.trimEnd('/'), null))
