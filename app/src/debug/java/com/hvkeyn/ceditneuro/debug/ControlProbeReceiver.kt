package com.hvkeyn.ceditneuro.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hvkeyn.ceditneuro.access.MobileAccessibilityService
import kotlinx.coroutines.runBlocking

/** Debug-only smoke probe: never shipped in release. */
class ControlProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val display = intent.getIntExtra("display", android.view.Display.DEFAULT_DISPLAY)
        val ref = intent.getStringExtra("ref").orEmpty()
        val action = intent.getStringExtra("action").orEmpty()
        if (ref.isNotBlank() && action.isNotBlank()) {
            val error = runBlocking { MobileAccessibilityService.actNow(ref, action, "") }
            Log.i("CEDiTProbe", "action=$action ref=$ref error=$error")
        }
        val snapshot = MobileAccessibilityService.snapshotNow(display)
        if (snapshot == null) {
            Log.i("CEDiTProbe", "display=$display connected=${MobileAccessibilityService.connected} snapshot=null")
            return
        }
        Log.i(
            "CEDiTProbe",
            "display=$display connected=true id=${snapshot.id} pkg=${snapshot.packageName} nodes=${snapshot.nodes.size}",
        )
        snapshot.nodes.take(8).forEach { node ->
            Log.i("CEDiTProbe", "${node.ref} ${node.role} ${node.label.take(80)} ${node.cx},${node.cy}")
        }
    }
}
