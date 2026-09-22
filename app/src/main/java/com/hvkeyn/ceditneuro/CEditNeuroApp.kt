package com.hvkeyn.ceditneuro

import android.app.Application
import com.hvkeyn.ceditneuro.data.SettingsStore

class CEditNeuroApp : Application() {

    val settings: SettingsStore by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CEditNeuroApp
            private set
    }
}
