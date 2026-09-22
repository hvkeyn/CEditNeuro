package com.hvkeyn.ceditneuro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hvkeyn.ceditneuro.ui.WorkspaceScreen
import com.hvkeyn.ceditneuro.ui.WorkspaceViewModel
import com.hvkeyn.ceditneuro.ui.theme.CEditNeuroTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as CEditNeuroApp

        setContent {
            CEditNeuroTheme {
                val viewModel: WorkspaceViewModel = viewModel(
                    factory = WorkspaceViewModel.factory(app, app.settings),
                )
                WorkspaceScreen(viewModel)
            }
        }
    }
}
