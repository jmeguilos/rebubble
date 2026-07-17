package app.rebubble.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.rebubble.ui.theme.RebubbleTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * QR scan surface using zxing-embedded's activity-result [ScanContract].
 * Launches the scanner on first composition and reports the payload (or cancel) via [onResult].
 */
@Composable
fun QrScanScreen(
    onResult: (payload: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        onResult(result.contents)
    }

    LaunchedEffect(Unit) {
        launcher.launch(rebubbleQrScanOptions())
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

fun rebubbleQrScanOptions(): ScanOptions =
    ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(OnboardingCopy.SCAN_QR)
        .setBeepEnabled(false)
        .setOrientationLocked(true)

@Preview(showBackground = true)
@Composable
private fun QrScanPreview() {
    RebubbleTheme(dynamicColor = false) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}
