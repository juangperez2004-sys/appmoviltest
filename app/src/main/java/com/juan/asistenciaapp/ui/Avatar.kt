package com.juan.asistenciaapp.ui

/**
 * Avatar con iniciales de un trabajador: se usa tanto en la lista
 * (TrabajadoresActivity) como en el detalle (DetalleTrabajadorActivity)
 * cuando el trabajador no tiene foto.
 */
object Avatar {

    private val colores = intArrayOf(
        0xFF3B82F6.toInt(), 0xFF22C55E.toInt(), 0xFFF59E0B.toInt(),
        0xFFEF4444.toInt(), 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt(),
        0xFFEC4899.toInt(), 0xFF84CC16.toInt()
    )

    fun iniciales(nombre: String): String {
        val palabras = nombre.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            palabras.isEmpty() -> "?"
            palabras.size >= 2 -> palabras[0].take(1) + palabras[1].take(1)
            else -> palabras[0].take(2)
        }.uppercase()
    }

    fun color(nombre: String): Int =
        colores[(nombre.hashCode() and 0x7fffffff) % colores.size]
}
