package com.juan.asistenciaapp.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Utilidades compartidas de rostros entre la cámara de asistencia
 * (MainActivity) y el registro de trabajadores (RegistrarTrabajadorActivity).
 */
object FaceUtil {

    /**
     * Recorte del rostro ALINEADO al formato del modelo (112x112). La
     * alineación (setPolyToPoly a la referencia ArcFace) NORMALIZA posición,
     * escala y rotación del rostro: el recorte queda consistente sin importar
     * el tamaño exacto de la caja que devuelva el detector.
     *
     * Es el pipeline con el que la app reconoce bien (la versión de referencia).
     * Sin esta normalización la geometría del recorte varía según la cámara y
     * la similitud se desploma (~0.2). Si no hay puntos clave, cae al recorte
     * con margen redimensionado.
     */
    fun alinearRostro(frame: Bitmap, cara: Cara): Bitmap {
        val salida = 112

        if (cara.nariz.x >= 0f) {
            // Puntos de referencia del modelo insightface/arcface (salida 112x112)
            val ref = floatArrayOf(
                38.2946f, 51.6963f,   // ojo derecho
                73.5318f, 51.5014f,   // ojo izquierdo
                56.0252f, 71.7366f,   // nariz
                56.1396f, 92.2848f    // centro de la boca (comisuras del modelo)
            )
            val src = floatArrayOf(
                cara.ojoDerecho.x, cara.ojoDerecho.y,
                cara.ojoIzquierdo.x, cara.ojoIzquierdo.y,
                cara.nariz.x, cara.nariz.y
            )
            // 4º punto (boca) solo si MediaPipe lo entregó: mejora el ajuste
            val conBoca = cara.boca.x >= 0f
            val srcFinal = if (conBoca) src + floatArrayOf(cara.boca.x, cara.boca.y) else src
            val refFinal = if (conBoca) ref else ref.copyOf(6)
            val matriz = Matrix()
            if (matriz.setPolyToPoly(srcFinal, 0, refFinal, 0, if (conBoca) 4 else 3)) {
                val alineado = Bitmap.createBitmap(salida, salida, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(alineado)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(frame, matriz, paint)
                return alineado
            }
        }

        // Fallback: recorte con margen, redimensionado a 112x112
        val r = cara.rect
        val left = r.left.toInt().coerceIn(0, frame.width - 1)
        val top = r.top.toInt().coerceIn(0, frame.height - 1)
        val right = (r.right.toInt() + 1).coerceAtMost(frame.width)
        val bottom = (r.bottom.toInt() + 1).coerceAtMost(frame.height)
        val recorte = Bitmap.createBitmap(
            frame, left, top,
            (right - left).coerceAtLeast(1),
            (bottom - top).coerceAtLeast(1)
        )
        val escalado = Bitmap.createScaledBitmap(recorte, salida, salida, true)
        if (escalado !== recorte) {
            recorte.recycle()
        }
        return escalado
    }

    /**
     * Estima si un bitmap está enfocado (nítido) calculando la varianza del
     * Laplaciano sobre la luminancia: los bordes de un rostro enfocado generan
     * valores altos; uno borroso (movido o desenfocado) genera casi todos ceros.
     *
     * Se calcula sobre el recorte pequeño (112x112), así que cuesta microsegundos.
     * Umbral fijo: con valores 0-255, un rostro enfocado suele dar > 25; uno
     * borroso suele quedar por debajo de 10-12. Se usa 15.
     */
    fun esNitida(bitmap: Bitmap, umbral: Float = 15f): Boolean {
        val (varianza, _) = estadisticas(bitmap) ?: return false
        return varianza >= umbral
    }

    /**
     * Nitidez ADAPTATIVA A LA LUZ: el umbral del Laplaciano se escala con el
     * brillo medio del fotograma. En poca luz la varianza baja de forma natural
     * y un umbral fijo de 15 descartaría casi todos los fotogramas (no reconoce);
     * aquí se usa un umbral proporcional (min. 5) para aceptar tomas oscuras
     * pero nítidas y seguir rechazando desenfoque real.
     */
    fun esNitidaAdaptativa(bitmap: Bitmap): Boolean {
        val (varianza, lumaMedia) = estadisticas(bitmap) ?: return false
        val umbral = maxOf(5.0, lumaMedia * 0.15)
        return varianza >= umbral
    }

    /** Calcula (varianza del Laplaciano, luminancia media) de una sola pasada. */
    private fun estadisticas(bitmap: Bitmap): Pair<Double, Double>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return null
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        // Luminancia por píxel (precalcular: evita repetir el cálculo 4 veces/píxel)
        val luma = IntArray(w * h)
        var sumaLuma = 0L
        for (i in px.indices) {
            val p = px[i]
            val l = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            luma[i] = l
            sumaLuma += l
        }
        var suma = 0.0
        var sumaCuad = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            var i = y * w
            for (x in 1 until w - 1) {
                i++
                val c = luma[i]
                val lap = 4 * c - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]
                suma += lap
                sumaCuad += lap.toDouble() * lap
                n++
            }
        }
        if (n == 0) return null
        val media = suma / n
        val varianza = sumaCuad / n - media * media
        return varianza to (sumaLuma.toDouble() / luma.size)
    }

    // ------------------------------------------------------------------
    //  Pipeline compartido foto -> huella (alta y actualización)
    // ------------------------------------------------------------------

    /** Resultado de procesar la foto de un trabajador: huella + recorte para mostrar/guardar. */
    class FotoTrabajador(val huella: FloatArray, val recorte: Bitmap)

    /**
     * Detecta el rostro en el bitmap, calcula su huella (embedding) y devuelve
     * también un recorte del rostro (máx. [maxLado] px) para mostrar/guardar.
     * Devuelve null si no hay rostro o falla el modelo. Es el mismo pipeline
     * del alta: sin espejo ni swap de canales (las fotos se guardan derechas).
     */
    fun procesarFotoTrabajador(
        det: BlazeFaceDetector,
        reco: FaceRecognizer,
        bitmap: Bitmap,
        maxLado: Int = 600
    ): FotoTrabajador? {
        val cara = det.detectarCara(bitmap) ?: return null
        val alineado = alinearRostro(bitmap, cara)
        val huella = try {
            reco.embedding(alineado)
        } finally {
            alineado.recycle()
        } ?: return null
        val recorte = recortarCara(bitmap, cara, maxLado)
        return FotoTrabajador(huella, recorte)
    }

    /** Recorte del rostro (Cara.rect ya trae margen) limitado a maxLado px. */
    fun recortarCara(src: Bitmap, cara: Cara, maxLado: Int): Bitmap {
        val r = cara.rect
        val left = r.left.toInt().coerceIn(0, src.width - 1)
        val top = r.top.toInt().coerceIn(0, src.height - 1)
        val right = (r.right.toInt() + 1).coerceAtMost(src.width)
        val bottom = (r.bottom.toInt() + 1).coerceAtMost(src.height)
        val recorte = Bitmap.createBitmap(
            src, left, top,
            (right - left).coerceAtLeast(1),
            (bottom - top).coerceAtLeast(1)
        )
        val max = maxOf(recorte.width, recorte.height)
        if (max > maxLado) {
            val escala = maxLado.toFloat() / max
            val matrix = Matrix().apply { postScale(escala, escala) }
            val escalado = Bitmap.createBitmap(
                recorte, 0, 0, recorte.width, recorte.height, matrix, true
            )
            if (escalado !== recorte) {
                recorte.recycle()
            }
            return escalado
        }
        // Copia independiente: createBitmap con región COMPARTE los píxeles
        // con src, y src se recicla al terminar; sin copiar, la foto quedaría
        // inválida para guardar.
        return recorte.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * Promedio de embeddings capturados (re-normalizado L2). Con 2-3 fotos la
     * huella es mucho más estable ante luz/ángulo en el escaneo posterior.
     */
    fun promediar(vectores: List<FloatArray>): FloatArray {
        val n = vectores.size
        val prom = FloatArray(512)
        for (v in vectores) {
            for (i in prom.indices) prom[i] += v[i]
        }
        for (i in prom.indices) prom[i] /= n
        var suma = 0f
        for (v in prom) suma += v * v
        val norma = kotlin.math.sqrt(suma)
        if (norma > 0f) {
            for (i in prom.indices) prom[i] /= norma
        }
        return prom
    }
}
