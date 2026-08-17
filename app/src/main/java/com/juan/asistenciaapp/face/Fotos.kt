package com.juan.asistenciaapp.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream

/**
 * Almacenamiento de las fotos de registro de los trabajadores.
 * Se guardan en la memoria interna de la app (filesDir/trabajadores)
 * con un nombre de archivo determinista por trabajador.
 *
 * También soporta las fotos del PC empaquetadas en assets/fotos/:
 * se copian ahí con el nombre del trabajador (p. ej. "Juan Perez.jpg")
 * y la app las usa cuando el trabajador no tiene foto propia.
 */
object Fotos {

    /** Nombre de archivo determinista: siempre el mismo para un mismo nombre. */
    fun nombreArchivo(nombre: String): String {
        val limpio = nombre.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifEmpty { "trabajador" }
        return "${limpio}_${nombre.hashCode() and 0x7fffffff}.jpg"
    }

    fun archivo(context: Context, nombre: String): File =
        File(File(context.filesDir, "trabajadores"), nombreArchivo(nombre))

    fun existe(context: Context, nombre: String): Boolean = archivo(context, nombre).exists()

    /** Renombra la foto de un trabajador (al cambiar su nombre). */
    fun renombrar(context: Context, viejo: String, nuevo: String) {
        val archivo = archivo(context, viejo)
        if (archivo.exists()) {
            archivo.renameTo(archivo(context, nuevo))
        }
    }

    /**
     * Copia la foto del trabajador (la propia o la del PC en assets) al nuevo
     * nombre. Se usa al renombrar a un trabajador del PC: su foto vive en
     * assets/fotos con el nombre original y no se puede renombrar ahí.
     */
    fun copiarDeAsset(context: Context, viejo: String, nuevo: String) {
        val foto = cargar(context, viejo, 800) ?: return
        guardar(context, nuevo, foto)
        foto.recycle()
    }

    /** Borra la foto de un trabajador. */
    fun eliminar(context: Context, nombre: String) {
        archivo(context, nombre).delete()
    }

    /** Guarda la foto en JPEG. Devuelve el archivo o null si falló. */
    fun guardar(context: Context, nombre: String, bitmap: Bitmap): File? {
        val carpeta = File(context.filesDir, "trabajadores").apply { mkdirs() }
        val archivo = File(carpeta, nombreArchivo(nombre))
        return try {
            FileOutputStream(archivo).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            archivo
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Carga la foto reducida a un tamaño razonable (null si no existe).
     * Prioridad: foto guardada en la app (filesDir) y luego la del PC
     * empaquetada en assets/fotos/.
     */
    fun cargar(context: Context, nombre: String, tamMax: Int = 200): Bitmap? {
        val archivo = archivo(context, nombre)
        if (archivo.exists()) {
            return decodificar(archivo.absolutePath, tamMax)
        }
        return cargarDeAsset(context, nombre, tamMax)
    }

    /**
     * Guarda la foto del trabajador desde un archivo de imagen (cámara o
     * galería): la decodifica enderezada (EXIF) y la guarda.
     * Devuelve true si se guardó.
     */
    fun guardarDesdeArchivo(context: Context, nombre: String, archivo: File): Boolean {
        val bmp = try {
            decodificarEnderezada(archivo.absolutePath, 800)
        } catch (_: Exception) {
            null
        } ?: return false
        val ok = guardar(context, nombre, bmp) != null
        bmp.recycle()
        return ok
    }

    /**
     * Decodifica un archivo de imagen con tamaño razonable (no satura la
     * memoria con la resolución completa del sensor) y aplica la rotación
     * EXIF para dejar la foto derecha. Usado por la cámara y la galería.
     */
    fun decodificarEnderezada(ruta: String, tamMax: Int = 1600): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ruta, bounds)
        var escala = 1
        while (bounds.outWidth / (escala * 2) >= tamMax &&
            bounds.outHeight / (escala * 2) >= tamMax
        ) {
            escala *= 2
        }
        val dec = BitmapFactory.Options().apply { inSampleSize = escala }
        val bmp = BitmapFactory.decodeFile(ruta, dec)
            ?: throw IllegalStateException("No se pudo decodificar la foto")
        return when (ExifInterface(ruta).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotar(bmp, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotar(bmp, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotar(bmp, 270f)
            else -> bmp
        }
    }

    /** Decodifica un archivo JPEG/PNG sin aplicar EXIF (fotos ya derechas). */
    private fun decodificar(ruta: String, tamMax: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ruta, opts)
        var escala = 1
        while (opts.outWidth / (escala * 2) >= tamMax &&
            opts.outHeight / (escala * 2) >= tamMax
        ) {
            escala *= 2
        }
        val dec = BitmapFactory.Options().apply { inSampleSize = escala }
        return BitmapFactory.decodeFile(ruta, dec)
    }

    /**
     * Foto del PC empaquetada en assets/fotos/. La búsqueda ignora
     * mayúsculas/minúsculas y espacios repetidos (los nombres del
     * nombres.json vienen en MAYÚSCULAS y Windows no distingue mayúsculas
     * al copiar los archivos).
     */
    private fun cargarDeAsset(context: Context, nombre: String, tamMax: Int): Bitmap? {
        val carpeta = try {
            context.assets.list("fotos")
        } catch (_: Exception) {
            null
        } ?: return null

        // mapa: nombre normalizado (minúsculas, espacios simples) -> ruta del asset
        val porNombre = HashMap<String, String>()
        for (archivo in carpeta) {
            val sinExt = archivo.substringBeforeLast('.')
            if (archivo.substringAfterLast('.', "").lowercase() !in setOf("jpg", "jpeg", "png")) {
                continue
            }
            porNombre[normalizar(sinExt)] = "fotos/$archivo"
        }
        val relativa = porNombre[normalizar(nombre)] ?: return null

        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(relativa).use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var escala = 1
            while (opts.outWidth / (escala * 2) >= tamMax &&
                opts.outHeight / (escala * 2) >= tamMax
            ) {
                escala *= 2
            }
            val dec = BitmapFactory.Options().apply { inSampleSize = escala }
            context.assets.open(relativa).use {
                BitmapFactory.decodeStream(it, null, dec)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizar(nombre: String): String =
        nombre.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun rotar(src: Bitmap, grados: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(grados) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src) {
            src.recycle()
        }
        return out
    }
}
