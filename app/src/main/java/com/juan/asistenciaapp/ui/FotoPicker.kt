package com.juan.asistenciaapp.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.juan.asistenciaapp.R
import java.io.File
import java.io.FileOutputStream

/**
 * Selector de foto de un trabajador: cámara del teléfono o galería del
 * sistema. Entrega el archivo de la imagen capturada/elegida vía [onFoto].
 * Compartido entre el detalle y la edición del trabajador.
 */
class FotoPicker(
    private val activity: ComponentActivity,
    private val onFoto: (File) -> Unit
) {

    private var fotoTemporal: File? = null

    private val tomarFoto = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { exito ->
        val archivo = fotoTemporal
        if (exito && archivo != null) {
            onFoto(archivo)
        } else {
            fotoTemporal?.delete()
            fotoTemporal = null
        }
    }

    private val elegirDeGaleria = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            copiarYEntregar(uri)
        }
    }

    /** Muestra el diálogo con las opciones (tomar foto / elegir de la galería). */
    fun mostrar() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.titulo_editar_foto)
            .setItems(
                arrayOf(
                    activity.getString(R.string.tomar_foto),
                    activity.getString(R.string.elegir_de_galeria)
                )
            ) { _, cual ->
                if (cual == 0) lanzarCamara() else lanzarGaleria()
            }
            .show()
    }

    private fun lanzarCamara() {
        val dir = File(activity.cacheDir, "fotos").apply { mkdirs() }
        val archivo = File(dir, "foto_temporal.jpg")
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", archivo
        )
        fotoTemporal = archivo
        try {
            tomarFoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            fotoTemporal = null
            Toast.makeText(activity, R.string.sin_app_camara, Toast.LENGTH_LONG).show()
        }
    }

    private fun lanzarGaleria() {
        elegirDeGaleria.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /** Copia la imagen elegida a cacheDir/fotos y la entrega como archivo. */
    private fun copiarYEntregar(uri: Uri) {
        val dir = File(activity.cacheDir, "fotos").apply { mkdirs() }
        val archivo = File(dir, "from_galeria.jpg")
        try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(archivo).use { output -> input.copyTo(output) }
            } ?: return
            onFoto(archivo)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.error_guardar_foto, Toast.LENGTH_LONG).show()
        }
    }
}
