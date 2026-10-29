package ai.bench.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.bench.app.data.BenchError
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Mode
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Starts a session: in the project's checkout, in an existing task, or in a new task
 * (its own branch and worktree). The first message is the session's opening prompt.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewSessionSheet(store: BenchStore, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var projectId by remember { mutableStateOf(s.projects.firstOrNull()?.id) }
    var where by remember { mutableStateOf(0) } // 0 checkout, 1 new task, 2 existing task
    var taskId by remember { mutableStateOf<String?>(null) }
    var taskTitle by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(Mode.of(s.defaults.mode)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val tasks = s.tasks.filter { it.project_id == projectId && it.state == "active" }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bench.colors.surface1) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.lg).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text("New session", style = MaterialTheme.typography.titleMedium)
            if (s.projects.isEmpty()) {
                MutedText("Add a project from Bench on your computer first.")
                return@Column
            }
            MutedText("Project")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                FilterChip(projectId == null, { projectId = null; where = 0 }, { Text("Ask Bench") })
                s.projects.forEach { p -> FilterChip(projectId == p.id, { projectId = p.id; taskId = null }, { Text(p.name) }) }
            }
            if (projectId == null) MutedText("A conversation about the whole of Bench: what's running, what it costs, starting work in any project.")
            else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Checkout", "New task", "Task").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = where == i,
                        onClick = { where = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                        enabled = i != 2 || tasks.isNotEmpty(),
                    ) { Text(label) }
                }
            }
            if (projectId != null) when (where) {
                1 -> OutlinedTextField(taskTitle, { taskTitle = it }, Modifier.fillMaxWidth(), label = { Text("Task title") }, singleLine = true)
                2 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    tasks.forEach { t -> FilterChip(taskId == t.id, { taskId = t.id }, { Text(t.title) }) }
                }
                else -> MutedText("Works directly in the project's checked-out branch.")
            }
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("What should Claude do?") }, minLines = 3)
            MutedText("Permission mode")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Mode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, { Text(m.label) }) }
            }
            error?.let { Text(it, color = Bench.colors.bad, style = MaterialTheme.typography.bodySmall) }
            Button(
                enabled = !busy && (where != 1 || taskTitle.isNotBlank()) && (where != 2 || taskId != null),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val pid = projectId
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val session = if (where == 1 && pid != null) {
                                // The server starts the task's session and sends it the prompt.
                                store.api.createTask(pid, taskTitle.trim(), prompt, mode.wire)
                            } else {
                                store.api.createSession(pid, if (where == 2) taskId else null, mode.wire).also {
                                    if (prompt.isNotBlank()) store.api.send(it.id, prompt.trim())
                                }
                            }
                            store.applySession(session)
                            onCreated(session.id)
                        } catch (e: BenchError) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Start")
            }
        }
    }
}
