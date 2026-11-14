package com.example.hypillcounter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hypillcounter.ui.CameraScreen
import com.example.hypillcounter.ui.HistoryDetailScreen
import com.example.hypillcounter.ui.HistoryScreen
import com.example.hypillcounter.ui.PillCounterViewModel
import com.example.hypillcounter.ui.ResultScreen
import com.example.hypillcounter.ui.Screen
import com.example.hypillcounter.ui.theme.HyPillCounterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyPillCounterTheme {
                PillCounterApp(viewModel())
            }
        }
    }
}

@Composable
fun PillCounterApp(vm: PillCounterViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val msg = vm.message
    LaunchedEffect(msg) {
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.message = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        when (val s = vm.screen) {
            Screen.Camera -> CameraScreen(vm)
            Screen.Result -> ResultScreen(vm)
            Screen.History -> HistoryScreen(vm)
            is Screen.Detail -> HistoryDetailScreen(vm, s.record)
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 120.dp),
        )
    }
}
