package com.shuddh.lab.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Controls for the bound camera: torch (the lab lamp), exposure, and AE/AWB lock. */
class CameraHandle {
    @Volatile
    var camera: Camera? = null
        private set
    var torchOn by mutableStateOf(false)
        private set
    var locked by mutableStateOf(false)
        private set
    var evIndex by mutableStateOf(0)
        private set
    var ready by mutableStateOf(false)
        private set

    internal fun attach(c: Camera?) {
        camera = c
        ready = c != null
        if (c != null) {
            c.cameraControl.enableTorch(torchOn)
            c.cameraControl.setExposureCompensationIndex(evIndex)
            applyLock(locked)
        }
    }

    fun torch(on: Boolean) {
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
    }

    /** Locks auto-exposure and auto-white-balance so frame brightness is a measurement, not a guess. */
    fun lock(on: Boolean) {
        locked = on
        applyLock(on)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyLock(on: Boolean) {
        val c = camera ?: return
        Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, on)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, on)
                .build(),
        )
    }

    fun exposure(index: Int) {
        evIndex = index
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    val evRange: IntRange
        get() = camera?.cameraInfo?.exposureState?.exposureCompensationRange?.let { it.lower..it.upper } ?: 0..0

    val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true
}

/**
 * 4:3 camera preview whose frames are delivered upright to [onFrame] on a background thread.
 * Preview and analysis share the same aspect ratio and FIT_CENTER, so a normalised rectangle
 * drawn by [overlay] covers exactly the same pixels the analyser reads.
 */
@Composable
fun CameraView(
    handle: CameraHandle,
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    widthFraction: Float = 0.78f,
    target: Size = Size(640, 480),
    overlay: DrawScope.() -> Unit = {},
    onFrame: (Bitmap) -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val frameCb by rememberUpdatedState(onFrame)
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lensFacing) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        var bound: Array<androidx.camera.core.UseCase> = emptyArray()
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val p = future.get()
            provider = p
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { img ->
                try {
                    val bmp = img.toBitmap()
                    val rot = img.imageInfo.rotationDegrees
                    val upright = if (rot == 0) bmp else Bitmap.createBitmap(
                        bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, false,
                    )
                    frameCb(upright)
                } catch (_: Throwable) {
                } finally {
                    img.close()
                }
            }
            runCatching {
                bound = arrayOf(preview, analysis)
                val cam = p.bindToLifecycle(
                    owner, CameraSelector.Builder().requireLensFacing(lensFacing).build(), preview, analysis,
                )
                handle.attach(cam)
            }.onFailure { handle.attach(null) }
        }, ContextCompat.getMainExecutor(ctx))

        onDispose {
            disposed = true
            handle.attach(null)
            if (bound.isNotEmpty()) provider?.unbind(*bound)
            executor.shutdown()
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Box(
            Modifier.fillMaxWidth(widthFraction).aspectRatio(3f / 4f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(Color.Black),
        ) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize(), onDraw = overlay)
            if (!handle.ready) {
                androidx.compose.material3.Text(
                    "Starting camera…", color = Color.White, modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                )
            }
        }
    }
}

/** Draws a normalised ROI rectangle with a label colour. */
fun DrawScope.roi(r: RectF, color: Color, stroke: Float = 3f) {
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(r.left * size.width, r.top * size.height),
        size = androidx.compose.ui.geometry.Size(r.width() * size.width, r.height() * size.height),
        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
    )
}
