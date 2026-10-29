package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.bench.app.BenchApp
import ai.bench.app.BuildConfigInfo
import ai.bench.app.data.BenchStore
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.BenchThemes
import ai.bench.app.ui.theme.Space
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(store: BenchStore, onBack: () -> Unit) {
    val s by store.state.collectAsState()
    val theme by BenchApp.instance.settings.theme.collectAsState("classic")
    val scope = rememberCoroutineScope()
    var confirmUnpair by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Bench.colors.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bench.colors.bg),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            Text("Server", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(store.api.config.baseUrl, style = MaterialTheme.typography.bodyMedium)
                MutedText(
                    listOfNotNull(
                        if (s.connected) "Connected" else "Not connected",
                        s.version?.let { "Bench $it" },
                        s.me?.device?.name?.let { "paired as $it" },
                    ).joinToString(" · "),
                )
            }

            Text("Theme", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                BenchThemes.forEach { t ->
                    Column(
                        Modifier.clickable { scope.launch { BenchApp.instance.settings.setTheme(t.id) } }.padding(Space.xs),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        Box(
                            Modifier.size(44.dp)
                                .background(t.colors.bg, CircleShape)
                                .border(if (t.id == theme) 3.dp else 1.dp, if (t.id == theme) Bench.colors.accent else Bench.colors.line, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(16.dp).background(t.colors.accent, CircleShape)) }
                        Text(t.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Text("Device", style = MaterialTheme.typography.titleSmall)
            MutedText("Unpairing forgets this phone's token here. To revoke it on the server too, use Settings → Devices in Bench, or `bench revoke`.")
            OutlinedButton(onClick = { confirmUnpair = true }) { Text("Unpair this phone", color = Bench.colors.bad) }

            MutedText("Bench for Android ${BuildConfigInfo.versionName}")
        }
    }

    if (confirmUnpair) AlertDialog(
        onDismissRequest = { confirmUnpair = false },
        title = { Text("Unpair?") },
        text = { Text("You'll need a new pairing code to connect again.") },
        confirmButton = { TextButton(onClick = { confirmUnpair = false; scope.launch { BenchApp.instance.settings.forget() } }) { Text("Unpair", color = Bench.colors.bad) } },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
    )
}
