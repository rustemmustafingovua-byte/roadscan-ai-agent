package ai.roadscan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.roadscan.app.ui.RoadScanRoot
import ai.roadscan.app.ui.RoadScanTheme
import ai.roadscan.app.ui.ScanViewModel

class MainActivity : ComponentActivity() {

    /** Видео, которым поделились из галереи: сразу отправляем в видеостудию. */
    private val sharedVideo = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedVideo.value = videoUri(intent)
        // Активация лицензии через deep link — для MDM/массового развёртывания:
        // roadscan://license?key=RSL1...
        applyLicenseIntent(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            RoadScanTheme {
                PermissionGate {
                    val viewModel: ScanViewModel = viewModel()
                    RoadScanRoot(
                        viewModel = viewModel,
                        incomingVideo = sharedVideo.value,
                        onIncomingVideoHandled = { sharedVideo.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLicenseIntent(intent)
        videoUri(intent)?.let { sharedVideo.value = it }
    }

    private fun videoUri(intent: Intent?): Uri? {
        val action = intent?.action ?: return null
        val type = intent.type
        return when {
            // Поделились видео из галереи.
            action == Intent.ACTION_SEND && type?.startsWith("video/") == true ->
                streamUri(intent)
            // Открыли видео файловым менеджером («Открыть через…»).
            action == Intent.ACTION_VIEW && (type?.startsWith("video/") == true || intent.dataString?.endsWith(".mp4") == true) ->
                intent.data
            else -> null
        }
    }

    private fun streamUri(intent: Intent): Uri? = runCatching {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    }.getOrNull() ?: runCatching {
        // Android 13+: типизированный доступ без ClassCastException на мусоре.
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    }.getOrNull()

    private fun applyLicenseIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "roadscan" || uri.host != "license") return
        val key = uri.getQueryParameter("key")?.takeIf { it.isNotBlank() } ?: return
        (application as RoadScanApp).container.settings.activate(key)
    }
}

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var locationGranted by remember {
        mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION))
    }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] == true || cameraGranted
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted && !asked) {
            asked = true
            launcher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    if (cameraGranted) {
        content()
    } else {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("RoadScan AI", style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.permission_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Button(
                    onClick = {
                        launcher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.permission_action))
                }
                if (locationGranted) {
                    Text(
                        stringResource(R.string.permission_gps_granted),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
