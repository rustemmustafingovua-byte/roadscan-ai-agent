package ai.roadscan.app

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.roadscan.app.ui.RoadScanRoot
import ai.roadscan.app.ui.RoadScanTheme
import ai.roadscan.app.ui.ScanViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            RoadScanTheme {
                PermissionGate {
                    val viewModel: ScanViewModel = viewModel()
                    RoadScanRoot(viewModel)
                }
            }
        }
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
                    "Нужен доступ к камере для анализа дорожного покрытия и к геолокации для привязки дефектов.",
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
                    Text("Разрешить доступ")
                }
                if (locationGranted) {
                    Text(
                        "GPS разрешён",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
