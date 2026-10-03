package com.suhas.globaledgeai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhas.globaledgeai.ui.AppNavigation
import com.suhas.globaledgeai.ui.MainViewModel
import com.suhas.globaledgeai.ui.theme.GlobalEdgeTheme
import com.suhas.globaledgeai.worker.MarketScanService
import com.suhas.globaledgeai.diagnostics.DiagnosticLog

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val repo = (application as GlobalEdgeApplication).repository
        runCatching { MarketScanService.start(this) }.onFailure { DiagnosticLog.log(this,"APP","Visible foreground scanner start failed",it) }

        setContent {
            GlobalEdgeTheme {
                val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(repo))
                AppNavigation(vm)
            }
        }
    }
}
