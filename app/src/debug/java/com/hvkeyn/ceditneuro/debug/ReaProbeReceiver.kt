package com.hvkeyn.ceditneuro.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hvkeyn.ceditneuro.tools.ReaVideoTool
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Debug-only smoke probe for the REA-Video local pass. */
class ReaProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path").orEmpty()
        val out = intent.getStringExtra("out").orEmpty().ifBlank { "rea-probe/${File(path).nameWithoutExtension}" }
        val change = intent.getStringExtra("change").orEmpty()
        val workspace = Workspace(File("/storage/emulated/0/Vdo"))
        val result = runBlocking {
            ReaVideoTool(workspace).execute(buildJsonObject {
                put("path", path)
                put("out", out)
                put("change", change)
            })
        }
        Log.i("ReaProbe", "path=$path error=${result.isError}\n${result.content}")
    }
}
