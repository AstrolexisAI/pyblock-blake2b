package com.astrolexis.pyblock.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material3.Text
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Full-screen camera QR scanner (CameraX preview + zxing decode) with an on-brand
 * square framing overlay — a QR scanner, not a 1D-barcode laser line. Calls
 * [onResult] with the first decoded QR text, then dismisses via [onClose].
 */
@Composable
fun QrScanner(title: String = "SCAN A QR CODE", continuous: Boolean = false, onResult: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraFeed(onResult = onResult, continuous = continuous)
            FramingOverlay()
        } else {
            Text(stringResource(R.string.wallet_scan_permission),
                style = PyType.mono(13f), color = PyTheme.primaryDim, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(40.dp))
        }
        Column(Modifier.fillMaxWidth().padding(top = 44.dp)) {
            Text("✕", style = PyType.mono(26f), color = Color.White,
                modifier = Modifier.align(Alignment.End).padding(horizontal = 20.dp)
                    .clickableNoRipple { Haptics.tap(); onClose() })
            Spacer(Modifier.height(6.dp))
            Text(title, style = PyType.mono(15f), color = PyTheme.primary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Dims the area AROUND a clear square (4 bands, no BlendMode.Clear — Clear punches
 *  through the Dialog window and leaks the screen behind) + green corner brackets. */
@Composable
private fun FramingOverlay() {
    val frameColor = PyTheme.primary   // read in composable context, use in the draw lambda
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val side = minOf(w, h) * 0.7f
        val l = (w - side) / 2f; val t = (h - side) / 2f; val r = l + side; val b = t + side
        val dim = Color.Black.copy(alpha = 0.5f)
        // Four dim bands around the (undrawn → transparent) square window.
        drawRect(dim, Offset(0f, 0f), Size(w, t))
        drawRect(dim, Offset(0f, b), Size(w, h - b))
        drawRect(dim, Offset(0f, t), Size(l, side))
        drawRect(dim, Offset(r, t), Size(w - r, side))
        // Corner brackets (green), arcade-style.
        val len = side * 0.12f
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        fun corner(p: Path.() -> Unit) = drawPath(Path().apply(p), frameColor, style = stroke)
        corner { moveTo(l, t + len); lineTo(l, t); lineTo(l + len, t) }
        corner { moveTo(r - len, t); lineTo(r, t); lineTo(r, t + len) }
        corner { moveTo(l, b - len); lineTo(l, b); lineTo(l + len, b) }
        corner { moveTo(r - len, b); lineTo(r, b); lineTo(r, b - len) }
    }
}

@Composable
private fun CameraFeed(onResult: (String) -> Unit, continuous: Boolean = false) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView (not the default SurfaceView) — a SurfaceView-backed preview
            // renders black inside a Compose Dialog window. COMPATIBLE = TextureView.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var fired by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, QrAnalyzer { text ->
                // Continuous mode (animated BC-UR): stream every decoded part; the caller
                // dedupes + decides when it's complete. One-shot mode fires exactly once.
                if (continuous) onResult(text)
                else if (!fired) { fired = true; onResult(text) }
            })
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            executor.shutdown()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Decodes QR (only) from CameraX YUV frames with zxing. */
private class QrAnalyzer(val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        ))
    }
    private var frames = 0

    override fun analyze(image: ImageProxy) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        if (frames++ % 30 == 0) {
            android.util.Log.i("QrScanner",
                "frame ${frames}: ${image.width}x${image.height} fmt=${image.format} " +
                "rowStride=${plane.rowStride} pixStride=${plane.pixelStride} dataLen=${data.size}")
        }
        // rowStride is the true data width (may exceed the visible width by padding);
        // crop to the visible width×height. Try upright then inverted (dark-on-light
        // vs light-on-dark) — some screens/QRs render inverted to the sensor.
        val vw = minOf(image.width, plane.rowStride)
        val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, vw, image.height, false)
        val text = decode(source) ?: decode(source.invert())
        if (text != null) onQr(text)
        image.close()
    }

    private fun decode(src: com.google.zxing.LuminanceSource): String? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
