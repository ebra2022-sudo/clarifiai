package com.clarifiai.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clarifiai.app.ui.ClarityTheme
import com.clarifiai.app.ui.DashboardScreen
import com.clarifiai.app.ui.DashboardViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The UI is always light (liquid glass), so keep system bar icons dark regardless of the device theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            ClarityTheme {
                val vm: DashboardViewModel = viewModel()
                DashboardScreen(vm = vm, activity = this)
            }
        }
    }
}
