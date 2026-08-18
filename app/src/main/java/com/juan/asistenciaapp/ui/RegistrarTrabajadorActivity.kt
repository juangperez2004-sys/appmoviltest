package com.juan.asistenciaapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Surface
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.data.AttendanceDb
import com.juan.asistenciaapp.databinding.ActivityRegistrarBinding
import com.juan.asistenciaapp.face.BlazeFaceDetector
import com.juan.asistenciaapp.face.FaceRecognizer
import com.juan.asistenciaapp.face.FaceUtil
import com.juan.asistenciaapp.face.Fotos
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Registra un trabajador nuevo: se escribe su nombre y se le toma la foto
 * con la cámara. Al guardar se almacenan la foto y la "huella facial"
 * (embedding 512-d), de modo que el trabajador también es reconocido por
 * la cámara de asistencia (MainActivity).
 */
class RegistrarTrabajadorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegistrarBinding

    private var imageCapture: ImageCapture? = null
    private var camaraFrontal = true
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var recognizer: FaceRecognizer? = null
    private var detector: BlazeFaceDetector? = null

    // Capturas del trabajador. Se guardan los EMBEDDINGS de todas las fotos
    // para almacenar el PROMEDIO (una sola foto es frágil ante luz/ángulo en el
    // escaneo posterior). Se recomienda tomar 3 fotos con variación.
    private val capturas = mutableListOf<FloatArray>()
    private var fotoLista: Bitmap? = null

    // Foto capturada mientras el modelo aún cargaba: se procesa al terminar
    private var fotoPendiente: File? = null

    private val permisoCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { otorgado ->
        if (otorgado) {
            iniciarCamara()
        } else {
            Toast.makeText(
                this,
                getString(R.string.permiso_camara_necesario),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegistrarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.registrar_trabajador)

        // Los modelos tardan ~1 s en cargar: en segundo plano.
        // Cualquier fallo aquí (p. ej. emulador x86_64 sin la librería nativa de
        // MediaPipe) debe avisar en pantalla, NUNCA cerrar la app.
        cameraExecutor.execute {
            try {
                val reco = FaceRecognizer(this)
                val det = BlazeFaceDetector(this)
                synchronized(this) {
                    recognizer = reco
                    detector = det
                }
                // Si el usuario ya capturó una foto, procesarla ahora que hay modelo
                fotoPendiente?.let { archivo ->
                    fotoPendiente = null
                    procesarFoto(archivo)
                }
            } catch (e: Throwable) {
                // Throwable (no Exception): UnsatisfiedLinkError es un Error.
                Log.e("Asistencia", "No se pudo cargar el detector de rostros", e)
                runOnUiThread {
                    binding.btnTomarFoto.isEnabled = false
                    binding.tvEstado.text = getString(R.string.error_modelo_detalle)
                    binding.tvEstado.setTextColor(
                        ContextCompat.getColor(this@RegistrarTrabajadorActivity, R.color.rojo)
                    )
                }
            }
        }

        binding.btnTomarFoto.setOnClickListener { tomarFoto() }
        // Feedback táctil ligero: el botón de captura se "hunde" al presionar
        binding.btnTomarFoto.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(120).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            false
        }
        binding.btnGuardar.setOnClickListener { guardar() }
        binding.btnCambiarCamara.setOnClickListener {
            camaraFrontal = !camaraFrontal
            capturas.clear()
            fotoLista?.recycle()
            fotoLista = null
            binding.cardFoto.visibility = View.GONE
            binding.btnGuardar.isEnabled = false
            binding.tvEstado.text = getString(R.string.ayuda_foto)
            binding.tvEstado.setTextColor(ContextCompat.getColor(this, R.color.gris_texto))
            iniciarCamara()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            iniciarCamara()
        } else {
            permisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Esperar a que termine el procesamiento en curso antes de cerrar la
        // sesión de ONNX (cerrar durante una inferencia puede crashear la
        // librería nativa).
        cameraExecutor.shutdown()
        try {
            cameraExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        recognizer?.cerrar()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        return true
    }

    private fun iniciarCamara() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            try {
                val proveedor = futuro.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val rot = when (binding.root.display?.rotation) {
                    Surface.ROTATION_90 -> Surface.ROTATION_90
                    Surface.ROTATION_180 -> Surface.ROTATION_180
                    Surface.ROTATION_270 -> Surface.ROTATION_270
                    else -> Surface.ROTATION_0
                }
                imageCapture = ImageCapture.Builder()
                    .setTargetRotation(rot)
                    .build()
                val selector = if (camaraFrontal) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                proveedor.unbindAll()
                proveedor.bindToLifecycle(this, selector, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(
                    this, "No se pudo abrir la cámara: ${e.message}", Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tomarFoto() {
        val captura = imageCapture ?: return
        val archivo = File(cacheDir, "captura_temporal.jpg")
        val opciones = ImageCapture.OutputFileOptions.Builder(archivo).build()
        // Vuelve a su tamaño normal (por si quedó presionado al deshabilitarse)
        binding.btnTomarFoto.animate().cancel()
        binding.btnTomarFoto.scaleX = 1f
        binding.btnTomarFoto.scaleY = 1f
        binding.btnTomarFoto.isEnabled = false
        captura.takePicture(
            opciones,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    procesarFoto(archivo)
                }

                override fun onError(exc: ImageCaptureException) {
                    binding.btnTomarFoto.isEnabled = true
                    binding.tvEstado.text = getString(R.string.foto_error)
                    binding.tvEstado.setTextColor(ContextCompat.getColor(this@RegistrarTrabajadorActivity, R.color.rojo))
                }
            }
        )
    }

    /** Decodifica la foto, la endereza (EXIF), detecta el rostro y calcula la huella. */
    private fun procesarFoto(archivo: File) {
        cameraExecutor.execute {
            try {
                val bitmap = Fotos.decodificarEnderezada(archivo.absolutePath, 1600)
                val det = synchronized(this) { detector }
                val reco = synchronized(this) { recognizer }
                if (det == null || reco == null) {
                    // Modelo aún cargando: conservar la foto para procesarla luego
                    fotoPendiente = archivo
                    runOnUiThread {
                        binding.btnTomarFoto.isEnabled = true
                        binding.tvEstado.text = getString(R.string.cargando_modelo)
                        binding.tvEstado.setTextColor(ContextCompat.getColor(this@RegistrarTrabajadorActivity, R.color.gris_texto))
                    }
                    return@execute
                }

                // Huella + recorte del rostro (mismo pipeline que la actualización)
                val resultado = FaceUtil.procesarFotoTrabajador(det, reco, bitmap)
                bitmap.recycle()
                if (resultado == null) {
                    runOnUiThread {
                        binding.btnTomarFoto.isEnabled = true
                        binding.tvEstado.text = getString(R.string.foto_error)
                        binding.tvEstado.setTextColor(ContextCompat.getColor(this@RegistrarTrabajadorActivity, R.color.rojo))
                    }
                    return@execute
                }

                val recorte = resultado.recorte
                val emb = resultado.huella

                // Filtro de calidad del alta: si la foto está borrosa NO se acepta
                // (una mala foto ensuciaría la huella promediada).
                if (!FaceUtil.esNitidaAdaptativa(recorte)) {
                    runOnUiThread {
                        binding.btnTomarFoto.isEnabled = true
                        binding.tvEstado.text = getString(R.string.foto_borrosa)
                        binding.tvEstado.setTextColor(ContextCompat.getColor(this@RegistrarTrabajadorActivity, R.color.rojo))
                    }
                    recorte.recycle()
                    return@execute
                }

                runOnUiThread {
                    binding.btnTomarFoto.isEnabled = true
                    // Reemplaza la foto mostrada (recicla la anterior)
                    val vieja = fotoLista
                    fotoLista = recorte
                    capturas += emb
                    binding.imgFoto.setImageBitmap(recorte)
                    vieja?.recycle()
                    binding.cardFoto.visibility = View.VISIBLE
                    binding.cardFoto.scaleX = 0.8f
                    binding.cardFoto.scaleY = 0.8f
                    binding.cardFoto.alpha = 0f
                    binding.cardFoto.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(200)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    binding.btnGuardar.isEnabled = capturas.size >= FOTOS_MINIMAS
                    binding.tvEstado.text = when {
                        capturas.size >= FOTOS_RECOMENDADAS -> getString(R.string.foto_ok)
                        capturas.size >= FOTOS_MINIMAS -> getString(
                            R.string.foto_captura_n, capturas.size, FOTOS_RECOMENDADAS
                        )
                        else -> getString(R.string.foto_minimas, FOTOS_MINIMAS)
                    }
                    binding.tvEstado.setTextColor(ContextCompat.getColor(this@RegistrarTrabajadorActivity, R.color.verde))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnTomarFoto.isEnabled = true
                    binding.tvEstado.text = getString(R.string.foto_error)
                    binding.tvEstado.setTextColor(ContextCompat.getColor(this@RegistrarTrabajadorActivity, R.color.rojo))
                }
            } finally {
                if (fotoPendiente !== archivo) {
                    archivo.delete()
                }
            }
        }
    }

    private fun guardar() {
        val nombre = binding.etNombre.text?.toString()?.trim().orEmpty()
        if (nombre.isEmpty()) {
            Toast.makeText(this, R.string.nombre_vacio, Toast.LENGTH_SHORT).show()
            return
        }
        val fotos = capturas
        val foto = fotoLista
        if (fotos.isEmpty() || foto == null) {
            Toast.makeText(this, R.string.toma_foto_primero, Toast.LENGTH_SHORT).show()
            return
        }

        val db = AttendanceDb(this)
        if (db.existeTrabajador(nombre)) {
            Toast.makeText(this, R.string.ya_existe, Toast.LENGTH_LONG).show()
            return
        }
        val guardada = Fotos.guardar(this, nombre, foto)
        if (guardada == null) {
            Toast.makeText(this, R.string.error_guardar_foto, Toast.LENGTH_SHORT).show()
            return
        }
        // Promedio de las huellas capturadas, sin los outliers
        // (una foto mala no debe contaminar la huella final)
        val huella = FaceUtil.promediar(limpiarOutliers(fotos))
        if (db.insertarTrabajador(nombre, huella, LocalDate.now().toString())) {
            Toast.makeText(this, R.string.trabajador_guardado, Toast.LENGTH_SHORT).show()
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        } else {
            Toast.makeText(this, R.string.ya_existe, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Descarta las huellas que se alejan mucho del promedio (una foto mala con
     * luz/ángulo raro no debe contaminar la huella final). Si quedarían menos
     * de 2, se conservan todas.
     */
    private fun limpiarOutliers(capturas: List<FloatArray>): List<FloatArray> {
        if (capturas.size <= 2) return capturas
        val prom = FloatArray(512)
        for (e in capturas) {
            for (i in prom.indices) prom[i] += e[i]
        }
        for (i in prom.indices) prom[i] /= capturas.size
        val buenas = capturas.filter { coseno(it, prom) >= 0.6f }
        return if (buenas.size >= 2) buenas else capturas
    }

    private fun coseno(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // las huellas ya vienen normalizadas (norma 1)
    }

    companion object {
        /** Fotos recomendadas por trabajador para una huella robusta. */
        private const val FOTOS_RECOMENDADAS = 4
        /** Mínimo de fotos buenas para poder guardar al trabajador. */
        private const val FOTOS_MINIMAS = 3
        fun abrir(context: Context) {
            context.startActivity(Intent(context, RegistrarTrabajadorActivity::class.java))
            (context as? android.app.Activity)
                ?.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }
}
