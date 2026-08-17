package com.juan.asistenciaapp.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facedetector.FaceDetectorResult

/** Rostro detectado: rectángulo (con margen) y puntos clave en píxeles del fotograma. */
data class Cara(
    val rect: RectF,
    /** Puntos clave de BlazeFace en píxeles: ojo derecho, ojo izquierdo, nariz y
     *  centro de la boca. (-1, -1) si el modelo no los entregó (no se puede alinear). */
    val ojoDerecho: PointF,
    val ojoIzquierdo: PointF,
    val nariz: PointF,
    val boca: PointF
)

/**
 * Detección de rostros con MediaPipe (BlazeFace), mucho más rápida y precisa
 * en celulares que el haarcascade del PC.
 */
class BlazeFaceDetector(context: Context) {

    private val detector: FaceDetector

    init {
        val opciones = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("face_detector.tflite")
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMinDetectionConfidence(0.5f)
            .build()
        detector = FaceDetector.createFromOptions(context, opciones)
    }

    /**
     * Devuelve la cara más grande del fotograma (con margen y puntos clave),
     * o null si no hay ninguna. Coordenadas en píxeles del bitmap.
     */
    fun detectarCara(bitmap: Bitmap): Cara? {
        val imagen = BitmapImageBuilder(bitmap).build()
        val resultado: FaceDetectorResult = detector.detect(imagen)

        var mejor: Detection? = null
        var mejorArea = 0f
        for (deteccion in resultado.detections()) {
            val caja = deteccion.boundingBox()
            val area = caja.width() * caja.height()
            if (area > mejorArea) {
                mejorArea = area
                mejor = deteccion
            }
        }
        if (mejor == null) return null

        val caja = mejor.boundingBox()
        // Margen alrededor del rostro para dar contexto a la red. La alineación
        // posterior (setPolyToPoly) normaliza el recorte, así que este margen
        // solo importa en el fallback y para dibujar/movimiento.
        val margenX = caja.width() * 0.25f
        val margenY = caja.height() * 0.30f
        val rect = RectF(
            (caja.left - margenX).coerceAtLeast(0f),
            (caja.top - margenY).coerceAtLeast(0f),
            (caja.right + margenX).coerceAtMost(bitmap.width.toFloat()),
            (caja.bottom + margenY).coerceAtMost(bitmap.height.toFloat())
        )

        // Puntos clave (normalizados 0-1) del modelo BlazeFace de corto alcance:
        // [0]=ojo derecho, [1]=ojo izquierdo, [2]=nariz, [3]=boca, [4-5]=orejas.
        val puntos = mejor.keypoints()
        if (puntos.isPresent && puntos.get().size >= 3) {
            val kp = puntos.get()
            fun px(k: NormalizedKeypoint): PointF =
                PointF(k.x() * bitmap.width, k.y() * bitmap.height)
            return Cara(
                rect, px(kp[0]), px(kp[1]), px(kp[2]),
                if (kp.size >= 4) px(kp[3]) else PointF(-1f, -1f)
            )
        }

        // Modelo sin puntos clave: no se puede alinear, solo el rectángulo
        val nulo = PointF(-1f, -1f)
        return Cara(rect, nulo, nulo, nulo, nulo)
    }
}
