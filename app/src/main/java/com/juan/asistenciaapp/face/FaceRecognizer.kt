package com.juan.asistenciaapp.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reconocedor facial: ejecuta el mismo modelo ONNX que el PC
 * (w600k_mbf.onnx, MobileFaceNet, entrada "input.1", 112x112 RGB,
 * normalización (px - 127.5) / 128) y devuelve el embedding 512-d normalizado.
 */
class FaceRecognizer(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelo = context.assets.open("w600k_mbf.onnx").use { it.readBytes() }
        session = env.createSession(modelo, OrtSession.SessionOptions())
    }

    /**
     * Embedding 512-d L2-normalizado del rostro recortado, o null si falla.
     * (El bitmap debe ser el recorte del rostro, cualquier tamaño; se redimensiona aquí.)
     *
     * @param swapRB si true, intercambia los canales R y B antes del modelo
     *               (algunos celulares entregan los colores en ese orden).
     */
    fun embedding(bitmap: Bitmap, swapRB: Boolean = false): FloatArray? {
        val tamanio = 112
        val resized = Bitmap.createScaledBitmap(bitmap, tamanio, tamanio, true)
        val pixeles = IntArray(tamanio * tamanio)
        resized.getPixels(pixeles, 0, tamanio, 0, 0, tamanio, tamanio)
        if (resized !== bitmap) {
            resized.recycle()
        }

        val n = tamanio * tamanio
        val buffer = ByteBuffer.allocateDirect(4 * 3 * n).order(ByteOrder.nativeOrder())
        val fb = buffer.asFloatBuffer()

        // NCHW: plano R, luego G, luego B (el bitmap ARGB ya viene en orden RGB)
        for (i in pixeles.indices) {
            val px = pixeles[i]
            val r = (px shr 16 and 0xFF) - 127.5f
            val g = (px shr 8 and 0xFF) - 127.5f
            val b = (px and 0xFF) - 127.5f
            if (swapRB) {
                fb.put(i, b / 128f)
                fb.put(i + n, g / 128f)
                fb.put(i + 2 * n, r / 128f)
            } else {
                fb.put(i, r / 128f)
                fb.put(i + n, g / 128f)
                fb.put(i + 2 * n, b / 128f)
            }
        }
        fb.rewind()

        val tensor = OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, tamanio.toLong(), tamanio.toLong()))
        val resultado = session.run(mapOf("input.1" to tensor))
        tensor.close()
        val salida = resultado[0] as OnnxTensor
        val vector = FloatArray(512)
        salida.floatBuffer.get(vector)
        resultado.close()

        // Normalización L2 (igual que en entrenar_modelo.py)
        var suma = 0f
        for (v in vector) suma += v * v
        val norma = kotlin.math.sqrt(suma)
        if (norma == 0f) return null
        for (i in vector.indices) vector[i] /= norma
        return vector
    }

    fun cerrar() {
        try {
            session.close()
        } catch (_: Exception) {
        }
    }
}
