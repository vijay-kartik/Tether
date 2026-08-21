package space.pitchstone.app.tether

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import space.pitchstone.app.tether.ui.theme.TetherTheme
import space.pitchstone.tether.session.WhatsAppManager
import space.pitchstone.tether.ui.WhatsAppScreen

/**
 * Owns the single [WhatsAppManager] for the process.
 *
 * A ViewModel rather than an Activity field so the connection survives configuration changes —
 * recreating the manager on every rotation would tear down and re-establish the WhatsApp session.
 */
class WhatsAppViewModel(application: Application) : AndroidViewModel(application) {
    val manager: WhatsAppManager = WhatsAppManager(application).apply { start() }

    override fun onCleared() {
        manager.close()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TetherTheme {
                val viewModel: WhatsAppViewModel = viewModel()
                NotificationPermission()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WhatsAppScreen(
                        manager = viewModel.manager,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

/**
 * The SDK keeps the connection alive in a foreground service, which needs a visible notification.
 * On API 33+ that permission is granted at runtime, so ask once on first composition; denial is
 * not fatal — pairing and message observing still work, only the ongoing notification is missing.
 */
@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Nothing to do either way — see the note above. */ }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
