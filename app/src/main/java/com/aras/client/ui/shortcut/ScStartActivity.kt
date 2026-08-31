package com.aras.client.ui.shortcut

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.aras.client.core.CoreServiceManager
import com.aras.client.core.LauncherManager
import com.aras.client.ui.base.BaseComponentActivity

class ScStartActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        LaunchedEffect(Unit) {
            moveTaskToBack(true)
            if (!CoreServiceManager.isRunning()) {
                LauncherManager.startServiceFromToggle(this@ScStartActivity)
            }
            finish()
        }
    }
}
