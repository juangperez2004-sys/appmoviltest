package com.juan.asistenciaapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.databinding.ActivityEscanearQrBinding
import com.juan.asistenciaapp.sync.SyncEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Escanea el código QR de otro dispositivo usando la cámara (CameraX + ZXing)
 * y devuelve la URI escaneada como resultado (extra "qr").
 */
class EscanearQRActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEscanearQrBinding
    private var executor: ExecutorService? = null

    private val permisoCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { otorgado ->
        if (otorgado) {
            iniciarCamara()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEscanearQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.escanear_qr)

        executor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            iniciarCamara()
        } else {
            permisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        executor?.shutdown()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun iniciarCamara() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            val proveedor = futuro.get()
            val analisis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(640, 480))
                .build()
                .also { it.setAnalyzer(executor!!, ::analizar) }
            try {
                proveedor.unbindAll()
                proveedor.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    Preview.Builder().build().also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    },
                    analisis
                )
            } catch (e: Exception) {
                Log.e("Sync", "No se pudo abrir la cámara", e)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analizar(proxy: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            val ancho = proxy.width
            val alto = proxy.height
            val plano = proxy.planes[0]
            val buffer = plano.buffer
            val pixelStride = plano.pixelStride
            val rowStride = plano.rowStride

            val pixeles = IntArray(ancho * alto)
            buffer.rewind()
            val bytesFila = ByteArray(rowStride)
            var idx = 0
            for (fila in 0 until alto) {
                buffer.position(fila * rowStride)
                val disponibles = buffer.remaining().coerceAtMost(rowStride)
                buffer.get(bytesFila, 0, disponibles)
                var offset = 0
                for (col in 0 until ancho) {
                    val r = bytesFila[offset].toInt() and 0xFF
                    val g = bytesFila[offset + 1].toInt() and 0xFF
                    val b = bytesFila[offset + 2].toInt() and 0xFF
                    pixeles[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    offset += pixelStride
                }
            }
            bitmap = Bitmap.createBitmap(pixeles, ancho, alto, Bitmap.Config.ARGB_8888)

            val texto = SyncEngine.decodificarQr(bitmap)
            if (texto != null) {
                setResult(RESULT_OK, Intent().putExtra("qr", texto))
                finish()
            }
        } catch (_: Exception) {
        } finally {
            bitmap?.recycle()
            proxy.close()
        }
    }

    companion object {
        fun abrir(context: Context, launcher: ActivityResultLauncher<Intent>) {
            launcher.launch(Intent(context, EscanearQRActivity::class.java))
        }
    }
}
