package com.juan.asistenciaapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.juan.asistenciaapp.MainActivity
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.data.AttendanceDb
import com.juan.asistenciaapp.databinding.FragmentAsistenciaBinding
import com.juan.asistenciaapp.face.FaceRecognizer
import com.juan.asistenciaapp.face.FaceUtil
import com.juan.asistenciaapp.face.Gallery
import com.juan.asistenciaapp.face.Match
import com.juan.asistenciaapp.face.Modelos
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pestaña "Asistencia": pase de lista por reconocimiento facial, equivalente al
 * pase_de_lista.py del PC.
 *
 * Lógica de umbrales y confirmación de fotogramas:
 *  - similitud >= 0.55 (clara): 3 fotogramas seguidos para registrar
 *  - similitud 0.40 a 0.55 (dudosa): 5 fotogramas seguidos
 *  - MARGEN ADAPTATIVO: coincidencias claras (sim >= 0.55) exigen margen >= 0.06
 *    (alto recall); las ambiguas (0.40-0.55) exigen margen >= 0.12 (evita
 *    falsos positivos: personas parecidas o desconocidos con un 2º candidato
 *    cerca).
 *  - El embedding se calcula sobre un PROMEDIO de PÍXELES de 8 recortes
 *    (anti-ruido de cámara) en lugar de promediar embeddings.
 *  - Cada trabajador se registra UNA sola vez por día.
 */
class AsistenciaFragment : Fragment() {

    private var _binding: FragmentAsistenciaBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: AttendanceDb
    private lateinit var gallery: Gallery

    // Por defecto se abre la cámara TRASERA (la frontal se usa con el botón "Cambiar cámara")
    private var camaraFrontal = false
    private lateinit var cameraExecutor: ExecutorService

    // Algunos celulares entregan el fotograma con los canales R/B intercambiados,
    // volteado en espejo o GIRO mal aplicado; se calibra una vez y se usan los
    // modos correctos (guardados por cámara frontal/trasera).
    private var canalesSwap = false
    private var modoEspejo = false
    private var rotacionModo = 0
    private var calibrado = false
    private var ultimoIntentoCalib = 0L
    private var mejorCandidato: String? = null

    // Diagnóstico: guarda los primeros recortes reales del modelo para poder
    // inspeccionar qué está viendo la app (solo depuración).
    private var diagGuardadas = 0

    // Promedio temporal de PÍXELES del recorte alineado: se promedian los
    // últimos PIX_FRAMES recortes en el dominio de imagen ANTES del modelo.
    // El ruido de cámara destruye la huella (medido: similitud 0.85 -> 0.32) y
    // promediar embeddings NO lo arregla (0.30 -> 0.35); promediar la imagen sí
    // (0.32 -> 0.66), porque el ruido se promedia en la entrada de la red.
    private val pixBuffer = ArrayDeque<IntArray>()
    private var ultimoCentroCara: PointF? = null

    // Buffers reutilizables (el análisis corre en un solo hilo): evita asignar
    // memoria nueva en cada fotograma -> menos trabajo del recolector = más fluido.
    private var bufPixeles: IntArray? = null
    private var bufBytesFila: ByteArray? = null
    private var bufAnchoFrame = 0
    private val bufR = IntArray(112 * 112)
    private val bufG = IntArray(112 * 112)
    private val bufB = IntArray(112 * 112)
    private val bufPromedio = IntArray(112 * 112)

    // 6 recortes bastan para el anti-ruido y llenan el promedio MÁS rápido
    // (arranque más corto -> rojo se acorta).
    private val PIX_FRAMES = 6

    // Un rostro más pequeño que esto (respecto al ancho del fotograma) no tiene
    // suficientes píxeles para una huella confiable: se pide acercarse.
    private val MIN_CARA = 0.12f

    // --- Umbrales (basados en pase_de_lista.py + datos medidos de la galería) ---
    // Con 440 trabajadores el solape entre "conocidos" y "desconocidos" está en
    // 0.40-0.60, así que la decisión usa DOS puertas:
    //   1) similitud mínima (zonaBaja): la deja pasar buscar().
    //   2) MARGEN mínimo para TODA aceptación: los verdaderos tienen margen
    //      amplio (media ~0.44 en la galería); los parecidos/desconocidos casi
    //      siempre tienen un 2º candidato cerca (margen pequeño) -> se rechazan.
    // Así se evitan los falsos positivos sin perder a los del borde.
    private val umbral = 0.55f       // similitud clara (3 fotogramas)
    private val zonaBaja = 0.40f     // piso absoluto (a buscar() solo se le pasa esto)
    // Margen ADAPTATIVO: en coincidencias claras (sim >= umbral) se exige poco
    // margen (alto recall, como la versión que "jalaba bien"); en las ambiguas
    // (0.40-0.55) se exige margen amplio (protección contra confundir personas).
    private val margenClaro = 0.06f
    private val margenDudoso = 0.12f
    // Mismo número de fotogramas para claras y dudosas: con el margen de
    // protección + promedio anti-ruido + estabilidad de nombre, 3 fotogramas
    // seguidos son suficientes para registrar sin falsos positivos (más rápido).
    private val framesClaro = 3
    private val framesDudoso = 3

    private val confirmaciones = HashMap<String, Int>()
    private val ultimoRegistroMsg = HashMap<String, Long>()
    private var registradosHoy = emptyList<String>()
    private var contadorHoy = 0

    // Tolerancia al parpadeo: un fotograma en "Desconocido" aislado NO reinicia
    // la confirmación (así el rojo->verde es rápido); solo se reinicia si el
    // rechazo persiste REINICIO_FALLOS fotogramas seguidos, o si el candidato
    // cambia a OTRA persona.
    private var fallosConsecutivos = 0
    private var ultimoNombreAceptado: String? = null
    private var cambiosNombre = 0
    private val REINICIO_FALLOS = 3

    // Duración mínima del mensaje de confirmación (verde): una vez mostrado,
    // se mantiene ese tiempo en pantalla aunque lleguen rechazos o el rostro se
    // pierda un instante (así se alcanza a leer).
    private val DURACION_MENSAJE = 1800L
    private var mostrarConfirmacionHasta = 0L

    private fun enVentanaConfirmacion(): Boolean =
        System.currentTimeMillis() < mostrarConfirmacionHasta

    // Límite de actualizaciones de UI: solo se repinta si cambia el estado
    // o cada ~150 ms (evita saturar el hilo principal a 30 fps)
    private var ultimoPostNombre: String? = null
    private var ultimoPostEstado = -1
    private var ultimoPostMs = 0L

    private val colorVerde = 0xFF22C55E.toInt()
    private val colorAmarillo = 0xFFFACC15.toInt()
    private val colorRojo = 0xFFEF4444.toInt()
    private val colorGris = 0xFF9CA3AF.toInt()

    private val permisoCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { otorgado ->
        if (otorgado) {
            iniciarCamara()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.permiso_camara_necesario),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAsistenciaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        db = AttendanceDb(requireContext())
        cargarCalibracion()
        gallery = construirGaleria()
        registradosHoy = db.registradosDe(fechaHoy()).map { it.nombre }
        contadorHoy = registradosHoy.size
        actualizarContador()

        binding.fechaHoy.text = LocalDate.now()
            .format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy"))
            .replaceFirstChar { it.titlecase() }

        binding.btnCambiarCamara.setOnClickListener {
            camaraFrontal = !camaraFrontal
            pixBuffer.clear()
            ultimoCentroCara = null
            reiniciarModos()
            iniciarCamara()
        }
        binding.btnVerRegistrados.setOnClickListener {
            (activity as? MainActivity)?.seleccionarPestana(R.id.tab_historial)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Los modelos se cargan UNA vez (Modelos) y se comparten entre pestañas.
        // Cualquier fallo (p. ej. emulador x86_64 sin MediaPipe) avisa en
        // pantalla y NUNCA cierra la app.
        Modelos.cargar(requireContext()) { ok ->
            view.post {
                if (ok) {
                    binding.tvNombre.text = getString(R.string.muestra_tu_rostro)
                } else {
                    binding.tvNombre.text = getString(R.string.error_modelo)
                    binding.tvEstado.text = getString(R.string.error_modelo_detalle)
                    binding.tvEstado.setTextColor(colorRojo)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            iniciarCamara()
        } else {
            permisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroyView() {
        // Esperar a que termine el análisis en curso antes de que el fragment
        // se destruya (los modelos son compartidos y no se cierran aquí).
        cameraExecutor.shutdown()
        try {
            cameraExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        _binding = null
        super.onDestroyView()
    }

    private fun fechaHoy(): String = LocalDate.now().toString()

    /** Galería fresca: lista del PC + trabajadores de la app, sin eliminados,
     *  con renombres y con las huellas SOBRESCRITAS de los trabajadores del PC
     *  cuya foto se actualizó desde la app (tienen prioridad sobre embeddings.bin). */
    private fun construirGaleria(): Gallery = Gallery(
        requireContext(),
        db.trabajadoresConEmbedding(),
        db.trabajadoresPcEliminados(),
        db.renombres(),
        db.huellasSobrescritas()
    )

    private fun actualizarContador() {
        binding.tvContador.text = getString(R.string.registrados_hoy, contadorHoy)
    }

    /**
     * Feedback al registrar: pulso de escala en el contador y destello en el
     * estado. Solo en el evento discreto de registro (1 vez por persona/día).
     */
    private fun pulso(v: View) {
        v.animate().cancel()
        v.scaleX = 0.85f
        v.scaleY = 0.85f
        v.animate().scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun destello(v: View) {
        v.animate().cancel()
        v.alpha = 0.35f
        v.animate().alpha(1f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun iniciarCamara() {
        if (_binding == null) return
        val futuro = ProcessCameraProvider.getInstance(requireContext())
        futuro.addListener({
            val proveedor = futuro.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val analisis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // Mayor resolución de análisis = más píxeles en el rostro = más precisión
                .setTargetResolution(Size(1280, 960))
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analizar) }
            val selector = if (camaraFrontal) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            try {
                proveedor.unbindAll()
                proveedor.bindToLifecycle(viewLifecycleOwner, selector, preview, analisis)
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "No se pudo abrir la cámara: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ------------------------------------------------------------------
    //  Análisis de cada fotograma (en hilo de segundo plano)
    // ------------------------------------------------------------------
    private fun analizar(proxy: ImageProxy) {
        val rotacion = proxy.imageInfo.rotationDegrees
        val bitmap = proxyToBitmap(proxy)
        var rotado: Bitmap? = null
        try {
            val det = Modelos.detector
            val reco = Modelos.recognizer
            if (det == null || reco == null) {
                return
            }

            // El sensor del teléfono entrega la imagen girada (90/180/270).
            // Hay que enderezarla ANTES de detectar/reconocer.
            rotado = rotar(bitmap, rotacion)

            val cara = det.detectarCara(rotado)
            if (cara == null) {
                confirmaciones.clear()
                fallosConsecutivos = 0
                ultimoNombreAceptado = null
                cambiosNombre = 0
                pixBuffer.clear()
                ultimoCentroCara = null
                // Si se acaba de confirmar (verde), se mantiene el mensaje un
                // instante aunque el rostro se pierda (da tiempo de leerlo).
                if (!enVentanaConfirmacion()) {
                    publicarEstado(null, 0f, ESTADO_ESPERA, null, rotado.width, rotado.height)
                }
                return
            }

            // Rostro demasiado pequeño/lejos: estirarlo a 112x112 daría una
            // huella basura que bajaría la precisión. Se pide acercarse.
            if (cara.rect.width() < rotado.width * MIN_CARA) {
                confirmaciones.clear()
                fallosConsecutivos = 0
                ultimoNombreAceptado = null
                cambiosNombre = 0
                pixBuffer.clear()
                ultimoCentroCara = null
                if (!enVentanaConfirmacion()) {
                    publicarEstado(null, 0f, ESTADO_ESPERA, null, rotado.width, rotado.height)
                }
                return
            }

            // Si el rostro saltó de posición, es otro momento (u otra persona):
            // se reinicia el promedio temporal. El umbral es amplio (50% del
            // ancho del rostro) para no reiniciar por pequeños movimientos, que
            // eran la causa del parpadeo verde->rojo.
            val centro = PointF(cara.rect.centerX(), cara.rect.centerY())
            ultimoCentroCara?.let { previo ->
                val dx = centro.x - previo.x
                val dy = centro.y - previo.y
                val umbralMov = cara.rect.width() * 0.50f
                if (dx * dx + dy * dy > umbralMov * umbralMov) {
                    pixBuffer.clear()
                }
            }
            ultimoCentroCara = centro

            // Recorte ALINEADO al formato del modelo: la alineación normaliza
            // posición/escala/rotación del rostro (pipeline de la versión que
            // reconocía bien); sin ella la similitud se desploma.
            val alineado = FaceUtil.alinearRostro(rotado, cara)

            var match: Match
            try {
                // Fotograma borroso (movido o desenfocado): su embedding ensucia
                // el promedio temporal y baja la similitud real. Se descarta sin
                // tocar la ventana: el estado en pantalla se mantiene.
                // Nitidez ADAPTATIVA a la luz: acepta tomas oscuras pero nítidas
                // y sigue rechazando el desenfoque real.
                if (!FaceUtil.esNitidaAdaptativa(alineado)) {
                    return
                }

                // Si el modo espejo/rotación está activo, el recorte se ajusta
                // antes de reconocer (el cuadro en pantalla no cambia: aparte).
                val entrada = aplicarModo(alineado, modoEspejo, rotacionModo)
                try {
                    var emb = embeddingPromedio(reco, entrada, canalesSwap)
                    if (emb == null) {
                        publicarEstado(null, 0f, ESTADO_ESPERA, null, rotado.width, rotado.height)
                        return
                    }
                    match = gallery.buscar(emb, umbral, zonaBaja)

                    // Calibración: si la coincidencia es mala, se prueban variantes
                    // de canales R/B, espejo y ROTACIÓN. Se adopta la mejor solo si
                    // gana por un margen real y supera 0.45.
                    if (!calibrado && match.similitud < 0.45f &&
                        System.currentTimeMillis() - ultimoIntentoCalib > 3000
                    ) {
                        ultimoIntentoCalib = System.currentTimeMillis()
                        val espejo = voltearHorizontal(alineado)
                        val rot90 = rotar(alineado, 90)
                        val rot180 = rotar(alineado, 180)
                        val rot270 = rotar(alineado, 270)
                        val espejo180 = voltearHorizontal(rot180)
                        try {
                            var mejorMatch = match
                            var mejorSwap = canalesSwap
                            var mejorEspejo = false
                            var mejorRot = 0

                            fun probar(entrada: Bitmap, swap: Boolean, espejado: Boolean, rot: Int) {
                                val e = reco.embedding(entrada, swap) ?: return
                                val m = gallery.buscar(e, umbral, zonaBaja)
                                if (m.similitud > mejorMatch.similitud) {
                                    mejorMatch = m
                                    mejorSwap = swap
                                    mejorEspejo = espejado
                                    mejorRot = rot
                                }
                            }

                            probar(alineado, true, false, 0)   // R/B intercambiados
                            probar(espejo, false, true, 0)     // espejo
                            probar(espejo, true, true, 0)      // espejo + R/B
                            probar(rot90, canalesSwap, false, 90)
                            probar(rot180, canalesSwap, false, 180)
                            probar(rot270, canalesSwap, false, 270)
                            probar(espejo180, canalesSwap, true, 180)   // espejo + 180

                            if (mejorMatch.similitud > match.similitud + 0.08f &&
                                mejorMatch.similitud > 0.45f
                            ) {
                                canalesSwap = mejorSwap
                                modoEspejo = mejorEspejo
                                rotacionModo = mejorRot
                                calibrado = true
                                guardarCalibracion()
                                // Recalcular el embedding con el modo adoptado y
                                // empezar el promedio con datos del modo correcto
                                val entradaFinal = aplicarModo(alineado, mejorEspejo, mejorRot)
                                val embFinal = reco.embedding(entradaFinal, canalesSwap)
                                entradaFinal.recycle()
                                if (embFinal != null) {
                                    // Reempezar el promedio de píxeles con el
                                    // recorte en el modo correcto
                                    pixBuffer.clear()
                                    emb = embeddingPromedio(reco, entradaFinal, canalesSwap)
                                    if (emb == null) {
                                        emb = embFinal
                                    }
                                    match = gallery.buscar(emb, umbral, zonaBaja)
                                }
                            }
                        } finally {
                            espejo.recycle()
                            rot90.recycle()
                            rot180.recycle()
                            rot270.recycle()
                            espejo180.recycle()
                        }
                    }

                    // El embedding ya es del recorte promedio (anti-ruido): se usa directo.

                    // Diagnóstico: guardar los primeros inputs reales del modelo
                    if (diagGuardadas < 5) {
                        diagGuardadas++
                        guardarDiagnostico(
                            entrada, rotado, cara.rect, match,
                            rotacion, camaraFrontal
                        )
                    }
                } finally {
                    if (entrada !== alineado) {
                        entrada.recycle()
                    }
                }
            } finally {
                alineado.recycle()
            }

            procesar(match, cara.rect, rotado.width, rotado.height)
        } catch (e: Exception) {
            // Nunca cerrar la app por un error de un fotograma; pero sí registrar
            Log.e(TAG, "Error procesando fotograma", e)
        } finally {
            if (rotado != null && rotado !== bitmap) {
                rotado.recycle()
            }
            bitmap.recycle()
            proxy.close()
        }
    }

    /**
     * Gira el fotograma a posición vertical (sin espejo: los embeddings se
     * generaron con fotos derechas de la webcam del PC).
     */
    private fun rotar(src: Bitmap, grados: Int): Bitmap {
        if (grados == 0) return src
        val matrix = Matrix().apply { postRotate(grados.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Guarda el input exacto del modelo (112x112) y un recorte de la cara para
     * diagnóstico. Solo los primeros fotogramas de cada sesión.
     */
    private fun guardarDiagnostico(
        entrada: Bitmap,
        frame: Bitmap,
        rect: RectF,
        match: Match?,
        rotacion: Int,
        frontal: Boolean
    ) {
        try {
            val dir = File(requireContext().getExternalFilesDir(null), "diag").apply { mkdirs() }
            val ts = System.currentTimeMillis()
            FileOutputStream(File(dir, "input_$ts.jpg")).use {
                entrada.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
            val l = rect.left.toInt().coerceIn(0, frame.width - 1)
            val t = rect.top.toInt().coerceIn(0, frame.height - 1)
            val r = (rect.right.toInt() + 1).coerceAtMost(frame.width)
            val b = (rect.bottom.toInt() + 1).coerceAtMost(frame.height)
            val cara = Bitmap.createBitmap(
                frame, l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1)
            )
            val h = (200f * cara.height / cara.width).toInt().coerceAtLeast(1)
            val mini = Bitmap.createScaledBitmap(cara, 200, h, true)
            FileOutputStream(File(dir, "cara_$ts.jpg")).use {
                mini.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
            if (mini !== cara) mini.recycle()
            cara.recycle()
            Log.i(
                TAG,
                "diag: frontal=$frontal rotacion=$rotacion frame=${frame.width}x${frame.height} " +
                    "cara=${rect.width().toInt()}x${rect.height().toInt()} " +
                    "sim=${match?.similitud} mejor=${match?.mejorNombre} swap=$canalesSwap " +
                    "espejo=$modoEspejo rotModo=$rotacionModo"
            )
        } catch (_: Exception) {
            // El diagnóstico nunca debe romper el reconocimiento
        }
    }

    /** Aplica al recorte el modo calibrado (espejo y/o rotación). */
    private fun aplicarModo(src: Bitmap, espejo: Boolean, rot: Int): Bitmap {
        var out = src
        if (espejo) {
            out = voltearHorizontal(out)
        }
        if (rot != 0) {
            out = rotar(out, rot)
        }
        return out
    }

    // ------------------------------------------------------------------
    //  Calibración persistida por cámara (canales R/B, espejo, rotación)
    // ------------------------------------------------------------------

    private fun claveCalibracion(): String =
        if (camaraFrontal) "calib_front" else "calib_back"

    /** Carga el modo calibrado para la cámara actual (si existe). */
    private fun cargarCalibracion() {
        val s = requireContext().getSharedPreferences("reconocimiento", 0)
            .getString(claveCalibracion(), null) ?: return
        val partes = s.split("|")
        if (partes.size == 3) {
            canalesSwap = partes[0] == "1"
            modoEspejo = partes[1] == "1"
            rotacionModo = partes[2].toIntOrNull() ?: 0
            calibrado = true
        }
    }

    /** Guarda el modo calibrado para la cámara actual. */
    private fun guardarCalibracion() {
        requireContext().getSharedPreferences("reconocimiento", 0).edit()
            .putString(claveCalibracion(), "$canalesSwap|$modoEspejo|$rotacionModo")
            .apply()
    }

    /** Al cambiar de cámara, usa el modo guardado de esa cámara (o recalibra). */
    private fun reiniciarModos() {
        canalesSwap = false
        modoEspejo = false
        rotacionModo = 0
        calibrado = false
        cargarCalibracion()
    }

    /**
     * Voltea el bitmap horizontalmente (efecto espejo). Se usa cuando el
     * celular entrega el fotograma invertido respecto a las fotos del PC.
     */
    private fun voltearHorizontal(src: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Convierte el ImageProxy (RGBA_8888) a Bitmap leyendo los bytes a mano.
     * No se usa ImageProxy.toBitmap() porque en algunos celulares entrega los
     * canales de color intercambiados (R/B) y el reconocimiento fallaría.
     */
    private fun proxyToBitmap(proxy: ImageProxy): Bitmap {
        val ancho = proxy.width
        val alto = proxy.height
        val plano = proxy.planes[0]
        val buffer = plano.buffer
        val pixelStride = plano.pixelStride
        val rowStride = plano.rowStride

        var pixeles = bufPixeles
        if (pixeles == null || bufAnchoFrame != ancho) {
            pixeles = IntArray(ancho * alto)
            bufPixeles = pixeles
            bufAnchoFrame = ancho
        }
        var bytesFila = bufBytesFila
        if (bytesFila == null || bytesFila.size != rowStride) {
            bytesFila = ByteArray(rowStride)
            bufBytesFila = bytesFila
        }

        buffer.rewind()
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
        // createBitmap(int[], ...) COPIA los píxeles, así que reusar el arreglo es seguro
        return Bitmap.createBitmap(pixeles, ancho, alto, Bitmap.Config.ARGB_8888)
    }

    private fun procesar(match: Match?, rect: RectF, ancho: Int, alto: Int) {
        val nombre = match?.nombre
        val sim = match?.similitud ?: 0f
        val margen = match?.margen ?: 0f
        mejorCandidato = match?.mejorNombre

        // Puerta doble para TODA aceptación: similitud mínima (ya filtrada por
        // buscar) Y margen adaptativo. El margen descarta falsos positivos en la
        // zona ambigua; en coincidencias claras se exige poco para no perder a
        // nadie (recall alto como la versión de referencia).
        val margenRequerido = if (sim >= umbral) margenClaro else margenDudoso
        if (nombre == null || margen < margenRequerido) {
            // Tolerancia al parpadeo: un rechazo aislado NO reinicia el progreso
            // NI parpadea la pantalla a rojo. Solo se reinicia y se muestra
            // "Desconocido" si el rechazo persiste REINICIO_FALLOS fotogramas
            // seguidos Y ya pasó la ventana del mensaje de confirmación (así el
            // verde de "ya registrado" se queda el tiempo de leerlo).
            fallosConsecutivos++
            if (fallosConsecutivos >= REINICIO_FALLOS && !enVentanaConfirmacion()) {
                confirmaciones.clear()
                ultimoNombreAceptado = null
                publicarEstado(null, sim, ESTADO_DESCONOCIDO, rect, ancho, alto)
            }
            return
        }
        fallosConsecutivos = 0

        // Tolerancia al parpadeo del candidato: si el top-1 cambia por UN solo
        // fotograma (oscila entre personas parecidas) NO se reinicia la racha;
        // solo un nombre distinto SOSTENIDO (>=2 fotogramas) reinicia. Así la
        // asistencia se registra aunque el nombre parpadee 1 frame.
        if (nombre != ultimoNombreAceptado) {
            cambiosNombre++
            if (cambiosNombre >= 2) {
                confirmaciones.clear()
                ultimoNombreAceptado = nombre
                cambiosNombre = 0
            }
        } else {
            cambiosNombre = 0
        }

        // Ya registrado HOY: aviso inmediato si la coincidencia es clara;
        // en zona dudosa se confirma con algunos fotogramas.
        if (registradosHoy.contains(nombre)) {
            val hacePoco = ultimoRegistroMsg[nombre]
                ?.let { System.currentTimeMillis() - it < 3000 } == true
            if (sim >= umbral) {
                confirmaciones.remove(nombre)
                if (!hacePoco) {
                    publicarEstado(nombre, sim, ESTADO_YA_REGISTRADO, rect, ancho, alto)
                }
            } else {
                confirmaciones[nombre] = (confirmaciones[nombre] ?: 0) + 1
                if (confirmaciones[nombre]!! >= framesDudoso) {
                    confirmaciones.remove(nombre)
                    if (!hacePoco) {
                        publicarEstado(nombre, sim, ESTADO_YA_REGISTRADO, rect, ancho, alto)
                    }
                } else {
                    publicarEstado(nombre, sim, ESTADO_VERIFICANDO, rect, ancho, alto)
                }
            }
            return
        }

        confirmaciones[nombre] = (confirmaciones[nombre] ?: 0) + 1
        val framesRequeridos = if (sim >= umbral) framesClaro else framesDudoso

        if (confirmaciones[nombre]!! >= framesRequeridos) {
            val hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            val nuevo = db.registrar(fechaHoy(), hora, nombre)
            if (nuevo) {
                registradosHoy = registradosHoy + nombre
                contadorHoy++
                ultimoRegistroMsg[nombre] = System.currentTimeMillis()
                view?.post { actualizarContador() }
                publicarEstado(nombre, sim, ESTADO_REGISTRADO, rect, ancho, alto)
            } else {
                publicarEstado(nombre, sim, ESTADO_YA_REGISTRADO, rect, ancho, alto)
            }
        } else {
            publicarEstado(nombre, sim, ESTADO_VERIFICANDO, rect, ancho, alto)
        }
    }

    /**
     * Promedia los últimos [PIX_FRAMES] recortes en el dominio de PÍXELES y
     * calcula el embedding del resultado. Promediar la IMAGEN antes del modelo
     * reduce el ruido de cámara (el promedio de embeddings no lo lograba), lo
     * que hace la huella mucho más estable en condiciones reales.
     */
    private fun embeddingPromedio(
        reco: FaceRecognizer,
        entrada: Bitmap,
        swap: Boolean
    ): FloatArray? {
        val w = entrada.width
        val h = entrada.height
        val nPx = w * h
        // Copia fresca: el ArrayDeque la conserva para el promedio temporal
        val px = IntArray(nPx)
        entrada.getPixels(px, 0, w, 0, 0, w, h)
        pixBuffer.addLast(px)
        while (pixBuffer.size > PIX_FRAMES) {
            pixBuffer.removeFirst()
        }
        val n = pixBuffer.size

        // Reutiliza los arreglos de suma si el tamaño coincide (112x112)
        val rSum: IntArray
        val gSum: IntArray
        val bSum: IntArray
        val prom: IntArray
        if (nPx == bufR.size) {
            rSum = bufR
            gSum = bufG
            bSum = bufB
            prom = bufPromedio
            java.util.Arrays.fill(rSum, 0)
            java.util.Arrays.fill(gSum, 0)
            java.util.Arrays.fill(bSum, 0)
        } else {
            rSum = IntArray(nPx)
            gSum = IntArray(nPx)
            bSum = IntArray(nPx)
            prom = IntArray(nPx)
        }
        for (p in pixBuffer) {
            for (i in 0 until nPx) {
                val c = p[i]
                rSum[i] += (c shr 16 and 0xFF)
                gSum[i] += (c shr 8 and 0xFF)
                bSum[i] += (c and 0xFF)
            }
        }
        for (i in 0 until nPx) {
            prom[i] = (0xFF shl 24) or ((rSum[i] / n) shl 16) or
                ((gSum[i] / n) shl 8) or (bSum[i] / n)
        }
        val promedio = Bitmap.createBitmap(prom, w, h, Bitmap.Config.ARGB_8888)
        val emb = reco.embedding(promedio, swap)
        promedio.recycle()
        return emb
    }

    // ------------------------------------------------------------------
    //  Actualización de la UI (hilo principal)
    // ------------------------------------------------------------------
    private fun publicarEstado(
        nombre: String?,
        sim: Float,
        estado: Int,
        rect: RectF?,
        anchoFrame: Int,
        altoFrame: Int
    ) {
        // Al confirmar (registrado / ya registrado) se abre la ventana durante
        // la cual el mensaje verde se mantiene aunque lleguen rechazos.
        if (estado == ESTADO_YA_REGISTRADO || estado == ESTADO_REGISTRADO) {
            mostrarConfirmacionHasta = System.currentTimeMillis() + DURACION_MENSAJE
        }

        // Límite de refresco: solo si cambió el estado o pasaron >= 150 ms
        val ahora = System.currentTimeMillis()
        val cambio = nombre != ultimoPostNombre || estado != ultimoPostEstado
        if (!cambio && ahora - ultimoPostMs < 150) {
            return
        }
        ultimoPostNombre = nombre
        ultimoPostEstado = estado
        ultimoPostMs = ahora

        view?.post {
            val b = _binding ?: return@post
            val caja = rect?.let { mapearCaja(it, anchoFrame, altoFrame) }
            val texto: String
            val color: Int
            when (estado) {
                ESTADO_REGISTRADO -> {
                    texto = "$nombre  ✓ " + getString(R.string.registrado)
                    color = colorVerde
                }
                ESTADO_YA_REGISTRADO -> {
                    texto = getString(R.string.ya_registrado)
                    color = colorVerde
                }
                ESTADO_VERIFICANDO -> {
                    texto = "$nombre  " + getString(R.string.verificando)
                    color = colorAmarillo
                }
                ESTADO_DESCONOCIDO -> {
                    texto = getString(R.string.desconocido)
                    color = colorRojo
                }
                else -> {
                    texto = getString(R.string.muestra_tu_rostro)
                    color = colorGris
                }
            }

            b.overlay.mostrar(caja, color, texto)
            if (estado == ESTADO_ESPERA || estado == ESTADO_DESCONOCIDO) {
                b.tvNombre.text = texto
                b.tvEstado.text = ""
            } else {
                b.tvNombre.text = nombre
                b.tvEstado.text = texto
            }
            b.tvEstado.setTextColor(color)
            // En Desconocido se muestra el mejor candidato para poder diagnosticar
            b.tvSim.text = if (estado == ESTADO_DESCONOCIDO && mejorCandidato != null) {
                "sim %.2f · ¿%s?".format(sim, mejorCandidato)
            } else {
                "sim %.2f".format(sim)
            }
            if (estado == ESTADO_REGISTRADO) {
                pulso(b.tvContador)
                destello(b.tvEstado)
            }
        }
    }

    /**
     * Convierte las coordenadas del fotograma (ya enderezado) a las de la vista
     * previa: solo escala y voltea si es frontal (la vista previa se muestra en
     * espejo con la cámara frontal).
     */
    private fun mapearCaja(rect: RectF, anchoFrame: Int, altoFrame: Int): RectF {
        val anchoVista = binding.previewView.width
        val altoVista = binding.previewView.height
        if (anchoVista == 0 || altoVista == 0) return RectF()

        var left = rect.left * anchoVista / anchoFrame
        val top = rect.top * altoVista / altoFrame
        var right = rect.right * anchoVista / anchoFrame
        val bottom = rect.bottom * altoVista / altoFrame
        if (camaraFrontal) {
            val espejo = anchoVista - right
            right = anchoVista - left
            left = espejo
        }
        return RectF(left, top, right, bottom)
    }

    companion object {
        private const val TAG = "Asistencia"
        private const val ESTADO_ESPERA = 0
        private const val ESTADO_VERIFICANDO = 1
        private const val ESTADO_REGISTRADO = 2
        private const val ESTADO_YA_REGISTRADO = 3
        private const val ESTADO_DESCONOCIDO = 4
    }
}
