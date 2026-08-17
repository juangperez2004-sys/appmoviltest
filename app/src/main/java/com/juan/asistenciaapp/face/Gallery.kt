package com.juan.asistenciaapp.face

import android.content.Context
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resultado de la búsqueda: nombre (null = desconocido), similitud coseno y el
 * mejor candidato (siempre informado, útil para diagnóstico cuando es Desconocido).
 *
 * margen = diferencia entre la mejor y la 2ª mejor similitud. En galerías
 * grandes (p. ej. 440 trabajadores) un top-1 con poco margen suele ser un
 * falso positivo, así que la decisión final lo usa para confirmar.
 */
data class Match(
    val nombre: String?,
    val similitud: Float,
    val mejorNombre: String?,
    val margen: Float
)

/** Trabajador registrado desde la app: nombre + huella facial (embedding 512-d). */
data class Dinamico(val nombre: String, val embedding: FloatArray)

/**
 * Galería de trabajadores: embeddings.bin (matriz 439x512 float32, little-endian)
 * y nombres.json, generados por entrenar_modelo.py en el PC; más los trabajadores
 * registrados desde la app (dinamicos), cuya huella se busca igual que la del PC.
 *
 * Los trabajadores del PC se pueden renombrar desde la app (renombres): el
 * embedding se guarda POR ÍNDICE, así que renombrar solo cambia la etiqueta
 * que se muestra y se devuelve en las coincidencias; el reconocimiento no cambia.
 *
 * Los trabajadores del PC también pueden tener una huella SOBRESCRITA
 * (embeddings.bin es inmutable dentro del APK): al actualizar la foto de un
 * trabajador del PC desde la app, su huella nueva se guarda en la base y aquí
 * tiene PRIORIDAD sobre la del APK.
 */
class Gallery(
    context: Context,
    dinamicos: List<Dinamico> = emptyList(),
    excluidos: Set<String> = emptySet(),
    renombres: Map<String, String> = emptyMap(),
    sobrescritas: Map<String, FloatArray> = emptyMap()
) {

    val nombres: List<String>
    val total: Int
    private val embeddings: FloatArray
    private val dinamicos: List<Dinamico>
    private val excluidos: Set<String>
    private val sobrescritas: Map<String, FloatArray>

    init {
        // Defensivo: si los assets fallan se degrada a galería vacía en vez de
        // cerrar la app (el arranque ocurre en el hilo principal).
        nombres = try {
            leerNombres(context).map { renombres[it] ?: it }
        } catch (e: Exception) {
            emptyList()
        }
        embeddings = try {
            leerEmbeddings(context)
        } catch (e: Exception) {
            FloatArray(0)
        }
        this.dinamicos = dinamicos
        this.excluidos = excluidos
        this.sobrescritas = sobrescritas
        total = nombres.count { it !in excluidos } + dinamicos.count { it.nombre !in excluidos }
    }

    private fun leerNombres(context: Context): List<String> {
        val texto = context.assets.open("nombres.json").use { it.readBytes() }
            .toString(Charsets.UTF_8)
        val arr = JSONArray(texto)
        return List(arr.length()) { arr.getString(it) }
    }

    private fun leerEmbeddings(context: Context): FloatArray {
        val bytes = context.assets.open("embeddings.bin").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(bytes)
        buffer.rewind()
        val fb = buffer.asFloatBuffer()
        val arreglo = FloatArray(fb.remaining())
        fb.get(arreglo)
        return arreglo
    }

    /**
     * Busca el trabajador más parecido con similitud coseno, primero en la
     * galería del PC y luego en los registrados desde la app.
     * Mismos umbrales que pase_de_lista.py: por debajo de zonaDudosa = Desconocido.
     * Además reporta el margen (mejor - 2ª mejor) para la decisión final.
     */
    fun buscar(embedding: FloatArray, umbral: Float, zonaDudosa: Float): Match {
        val dim = 512
        var mejorSim = Float.NEGATIVE_INFINITY
        var segundaSim = Float.NEGATIVE_INFINITY
        var mejorNombre: String? = null

        // Galería del PC (estática). Una huella SOBRESCRITA (recalculada desde
        // la app al actualizar la foto) gana sobre la del APK para ese nombre.
        for (i in 0 until nombres.size) {
            if (nombres[i] in excluidos) continue
            val sobrescrita = sobrescritas[nombres[i]]
            var dot = 0.0f
            if (sobrescrita != null) {
                for (j in 0 until dim) {
                    dot += embedding[j] * sobrescrita[j]
                }
            } else {
                val base = i * dim
                for (j in 0 until dim) {
                    dot += embedding[j] * embeddings[base + j]
                }
            }
            if (dot > mejorSim) {
                segundaSim = mejorSim
                mejorSim = dot
                mejorNombre = nombres[i]
            } else if (dot > segundaSim) {
                segundaSim = dot
            }
        }

        // Trabajadores registrados desde la app (dinámicos)
        for (d in dinamicos) {
            if (d.nombre in excluidos) continue
            var dot = 0.0f
            for (j in 0 until dim) {
                dot += embedding[j] * d.embedding[j]
            }
            if (dot > mejorSim) {
                segundaSim = mejorSim
                mejorSim = dot
                mejorNombre = d.nombre
            } else if (dot > segundaSim) {
                segundaSim = dot
            }
        }

        // Solo un candidato en la galería: el margen no tiene competencia
        val margen = if (segundaSim.isInfinite()) {
            Float.MAX_VALUE
        } else {
            mejorSim - segundaSim
        }

        if (mejorNombre == null || mejorSim < zonaDudosa) {
            return Match(null, mejorSim, mejorNombre, margen)
        }
        return Match(mejorNombre, mejorSim, mejorNombre, margen)
    }
}
