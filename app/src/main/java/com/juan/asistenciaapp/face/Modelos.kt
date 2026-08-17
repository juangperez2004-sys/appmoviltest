package com.juan.asistenciaapp.face

import android.content.Context
import android.util.Log
import com.juan.asistenciaapp.Diag
import java.util.concurrent.Executors

/**
 * Carga los modelos de reconocimiento (FaceRecognizer + BlazeFaceDetector)
 * UNA sola vez por proceso y los comparte entre los fragments (la pestaña de
 * Asistencia se recrea al cambiar de pestaña, pero los modelos no se recargan).
 *
 * Si la librería nativa no está disponible (p. ej. emulador x86_64 sin
 * MediaPipe), se avisa por el callback con ok=false y la app NO se cierra.
 */
object Modelos {

    @Volatile
    var recognizer: FaceRecognizer? = null
        private set

    @Volatile
    var detector: BlazeFaceDetector? = null
        private set

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Asegura los modelos cargados y llama a [alListo] (en el hilo de fondo).
     * Si ya están listos, llama de inmediato.
     */
    fun cargar(context: Context, alListo: (ok: Boolean) -> Unit) {
        if (recognizer != null && detector != null) {
            alListo(true)
            return
        }
        Diag.iniciar(context.applicationContext)
        executor.execute {
            // Cada librería se intenta POR SEPARADO: si una falla, la otra
            // igual se intenta y el archivo de diagnóstico dice cuál crasheó.
            var ok = true

            if (recognizer == null) {
                Diag.marcar("onnx_inicio")
                try {
                    recognizer = FaceRecognizer(context.applicationContext)
                    Diag.marcar("onnx_ok")
                } catch (e: Throwable) {
                    // Throwable (no Exception): UnsatisfiedLinkError es un Error.
                    ok = false
                    Diag.error("onnx_error", e)
                    Log.e("Asistencia", "No se pudo cargar ONNX", e)
                }
            }

            if (detector == null) {
                Diag.marcar("mediapipe_inicio")
                try {
                    detector = BlazeFaceDetector(context.applicationContext)
                    Diag.marcar("mediapipe_ok")
                } catch (e: Throwable) {
                    ok = false
                    Diag.error("mediapipe_error", e)
                    Log.e("Asistencia", "No se pudo cargar MediaPipe", e)
                }
            }

            Diag.marcar(if (ok) "completo_ok" else "completo_error")
            alListo(ok)
        }
    }

    /** Cierra la sesión de ONNX (al salir de la app). */
    fun cerrar() {
        recognizer?.cerrar()
        recognizer = null
        detector = null
    }
}
