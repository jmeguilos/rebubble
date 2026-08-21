package app.rebubble.ui.onboarding

import com.journeyapps.barcodescanner.ScanOptions

/**
 * Shared scanner configuration for the onboarding QR flow.
 *
 * `OnboardingScreen` launches zxing-embedded's `ScanContract` directly with these options, so there
 * is no QR *screen* of our own — a `QrScanScreen` composable used to live here that launched the
 * scanner from a `LaunchedEffect` behind a spinner, but it had zero call sites and was removed.
 */
fun rebubbleQrScanOptions(): ScanOptions =
    ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(OnboardingCopy.SCAN_QR)
        .setBeepEnabled(false)
        .setOrientationLocked(true)
