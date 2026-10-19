package com.example.altiyedi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var preview: PreviewView
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private var landmarker: PoseLandmarker? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val leftCounter = WristCounter()
    private val rightCounter = WristCounter()
    @Volatile private var total = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        setupLandmarker()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        preview = PreviewView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        countText = TextView(this).apply {
            text = "0"; textSize = 110f; setTextColor(Color.WHITE)
            setShadowLayer(12f, 0f, 0f, Color.BLACK); gravity = Gravity.CENTER
        }
        root.addView(countText, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = 80 })

        statusText = TextView(this).apply {
            text = "Başlat: üst gövden kadrajda olsun"; textSize = 16f
            setTextColor(Color.WHITE); setShadowLayer(8f, 0f, 0f, Color.BLACK); gravity = Gravity.CENTER
        }
        root.addView(statusText, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = 220 })

        val reset = Button(this).apply {
            text = "Sıfırla"
            setOnClickListener { total = 0; leftCounter.reset(); rightCounter.reset(); countText.text = "0" }
        }
        root.addView(reset, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 80 })
        setContentView(root)
    }

    private fun setupLandmarker() {
        try {
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setResultListener { r, i -> onResult(r, i) }
                .setErrorListener { e -> setStatus("Hata: ${e.message}") }
                .build()
            landmarker = PoseLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            countText.text = "!"
            setStatus("Model bulunamadı: app/src/main/assets/pose_landmarker_lite.task dosyasını ekle")
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { it.setAnalyzer(executor, ::analyze) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, prev, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val lm = landmarker
        if (lm == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees.toFloat()
        val bmp = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
        proxy.use { bmp.copyPixelsFromBuffer(it.planes[0].buffer) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        lm.detectAsync(BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis())
    }

    private fun vis(l: NormalizedLandmark) = l.visibility().orElse(0f)

    private fun onResult(r: PoseLandmarkerResult, img: MPImage) {
        val p = r.landmarks().firstOrNull()
        if (p == null || p.size < 25) { setStatus("Seni göremiyorum…"); return }
        val shY = (p[11].y() + p[12].y()) / 2f
        val hipsOk = vis(p[23]) > 0.5f && vis(p[24]) > 0.5f
        val scale = if (hipsOk) abs((p[23].y() + p[24].y()) / 2f - shY)
                    else abs(p[11].x() - p[12].x()) * 1.6f   // kalça görünmüyorsa omuz genişliğinden tahmin
        if (scale < 0.05f) { setStatus("Biraz geri çekil"); return }

        val now = SystemClock.uptimeMillis()
        var gained = 0
        if (vis(p[15]) > 0.5f && leftCounter.update(-p[15].y() / scale, now)) gained++
        if (vis(p[16]) > 0.5f && rightCounter.update(-p[16].y() / scale, now)) gained++
        if (gained > 0) {
            total += gained
            runOnUiThread { countText.text = total.toString() }
        }
        setStatus("Sayıyor…")
    }

    private fun setStatus(s: String) = runOnUiThread { statusText.text = s }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        landmarker?.close()
    }
}
