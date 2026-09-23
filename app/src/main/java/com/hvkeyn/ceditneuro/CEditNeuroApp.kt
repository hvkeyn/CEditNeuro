package com.hvkeyn.ceditneuro

import android.app.Application
import com.hvkeyn.ceditneuro.data.SettingsStore
import com.hvkeyn.ceditneuro.ui.WorkspaceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CEditNeuroApp : Application() {

    val settings: SettingsStore by lazy { SettingsStore(this) }

    /** Outlives the activity, so minimizing the app does not cancel the agent. */
    val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val workspaceModel: WorkspaceViewModel by lazy {
        WorkspaceViewModel(this, settings, agentScope)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CEditNeuroApp
            private set
    }
}
