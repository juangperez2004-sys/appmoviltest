package com.juan.asistenciaapp

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Diagnóstico de arranque: escribe en un archivo (Download/AsistenciaDiag/diag.txt
 * y en la carpeta propia de la app) cada paso de la carga de modelos y los
 * crashes Java. Si la app se cierra en el teléfono de otra persona, ese archivo
 * permite saber EXACTAMENTE dónde falló sin necesidad de conectarla a una PC.
 */
object Diag {

    private const val NOMBRE = "diag.txt"

    @Volatile
    private var contexto: Context? = null

    private val buffer = StringBuilder()

    /** Registra el inicio (solo una vez por proceso) con los datos del teléfono. */
    @Synchronized
    fun iniciar(context: Context) {
        if (buffer.isNotEmpty()) return
        contexto = context.applicationContext
        buffer.appendLine("=== Diagnóstico de arranque ===")
        buffer.appendLine("Modelo: ${Build.MANUFACTURER} ${Build.MODEL}")
        buffer.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        buffer.appendLine("Aplicacion: ${context.packageName}")
        buffer.appendLine("---")
        guardar()
    }

    /** Registra un paso completado (p. ej. "onnx_ok"). */
    @Synchronized
    fun marcar(paso: String) {
        buffer.appendLine(paso)
        guardar()
    }

    /** Registra un error capturado (excepción Java). */
    @Synchronized
    fun error(nombre: String, e: Throwable) {
        buffer.appendLine("$nombre: ${e.javaClass.simpleName}: ${e.message}")
        guardar()
    }

    @Synchronized
    private fun guardar() {
        val c = contexto ?: return
        val texto = buffer.toString()
        try {
            guardarMediaStore(c, texto)
        } catch (_: Exception) {
        }
        try {
            val dir = File(c.getExternalFilesDir(null), "diag").apply { mkdirs() }
            File(dir, NOMBRE).writeText(texto)
        } catch (_: Exception) {
        }
    }

    /** Escribe en Descargas/AsistenciaDiag (visible en el explorador de archivos). */
    private fun guardarMediaStore(c: Context, texto: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = c.contentResolver
        val valores = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, NOMBRE)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/AsistenciaDiag"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
            ?: return
        try {
            resolver.openOutputStream(uri)?.use {
                it.write(texto.toByteArray(Charsets.UTF_8))
            }
        } finally {
            val limpio = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, limpio, null, null)
        }
    }
}
