package com.example.eqify.wear

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable

/** Paths and commands shared with the phone's WearBridge. */
object WearProtocol {
    const val STATUS_PATH = "/eqify/status"
    const val COMMAND_PATH = "/eqify/command"
    const val CMD_TOGGLE = "toggle"
    const val CMD_BASS = "bass"
    const val CMD_SLEEP_30 = "sleep30"
    const val CMD_SLEEP_CANCEL = "sleepcancel"
}

data class PhoneStatus(
    val status: String = "Connecting to your phone…",
    val bass: String = "Bass",
    val sleep: String = "",
    val on: Boolean = false,
    val sleeping: Boolean = false
)

/** EQify on the watch: a remote for the phone (FEATURES_BATCH4_PLAN.md W). */
class WearMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val status = mutableStateOf(PhoneStatus())
    private val note = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WearScreen(status.value, note.value, ::send) } }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        // Last status the phone published, if any.
        Wearable.getDataClient(this).dataItems.addOnSuccessListener { items ->
            items.forEach { if (it.uri.path == WearProtocol.STATUS_PATH) read(DataMapItem.fromDataItem(it)) }
            items.release()
        }
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WearProtocol.STATUS_PATH)
                read(DataMapItem.fromDataItem(e.dataItem))
        }
        events.release()
    }

    private fun read(item: DataMapItem) {
        val m = item.dataMap
        status.value = PhoneStatus(
            status = m.getString("status") ?: "EQify",
            bass = m.getString("bass") ?: "Bass",
            sleep = m.getString("sleep") ?: "",
            on = m.getBoolean("on"),
            sleeping = m.getBoolean("sleeping")
        )
        note.value = null
    }

    private fun send(command: String) {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) { note.value = "Phone not connected"; return@addOnSuccessListener }
            nodes.forEach { node ->
                Wearable.getMessageClient(this)
                    .sendMessage(node.id, WearProtocol.COMMAND_PATH, command.toByteArray(Charsets.UTF_8))
                    .addOnFailureListener { note.value = "Couldn't reach your phone"; Log.w("EQifyWear", "send failed", it) }
            }
        }.addOnFailureListener { note.value = "Phone not connected" }
    }
}

@Composable
private fun WearScreen(status: PhoneStatus, note: String?, send: (String) -> Unit) {
    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { Text("EQify", style = MaterialTheme.typography.title3) }
            item {
                Text(listOfNotNull(status.status, status.sleep.ifBlank { null }).joinToString("\n"),
                    style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
            }
            note?.let { item { Text(it, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.error) } }
            item {
                Chip(onClick = { send(WearProtocol.CMD_TOGGLE) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (status.on) "Pause EQ" else "Resume EQ") },
                    colors = ChipDefaults.primaryChipColors())
            }
            item {
                Chip(onClick = { send(WearProtocol.CMD_BASS) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(status.bass) }, secondaryLabel = { Text("Tap for the next step") },
                    colors = ChipDefaults.secondaryChipColors())
            }
            item {
                Chip(onClick = { send(if (status.sleeping) WearProtocol.CMD_SLEEP_CANCEL else WearProtocol.CMD_SLEEP_30) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (status.sleeping) "Cancel sleep timer" else "Sleep in 30 min") },
                    colors = ChipDefaults.secondaryChipColors())
            }
        }
    }
}
