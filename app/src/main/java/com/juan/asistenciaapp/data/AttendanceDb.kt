package com.juan.asistenciaapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.juan.asistenciaapp.face.Dinamico
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Base de datos local de asistencia.
 * Mismo formato que el CSV del PC: Fecha, Hora, Nombre.
 * Un trabajador solo se puede registrar UNA vez por día (UNIQUE fecha+nombre).
 *
 * v2: tabla "trabajadores" con los trabajadores registrados desde la app
 * (nombre, huella facial y fecha de registro). Su huella se suma a la
 * galería de reconocimiento junto con la del PC.
 *
 * v3: tabla "eliminados" con los trabajadores del PC eliminados definitivamente
 * (no se pueden borrar del APK, así que se excluyen de la lista y del
 * reconocimiento para siempre; solo reaparecerían reinstalando la app).
 *
 * v4: tabla "renombrados" con los cambios de nombre de los trabajadores del
 * PC (original en nombres.json -> nombre actual). Como el embedding se guarda
 * por índice, renombrar solo cambia la etiqueta; el reconocimiento no cambia.
 *
 * v5: tabla "huellas_actualizadas" con las huellas faciales RECALCULADAS de
 * los trabajadores del PC (embeddings.bin es inmutable dentro del APK). En la
 * búsqueda, una huella aquí tiene PRIORIDAD sobre la del APK, así que
 * actualizar la foto de un trabajador del PC desde la app también mejora el
 * reconocimiento.
 *
 * v6: sincronización WiFi. Se agrega "updated_at" (marca de tiempo de la última
 * modificación) a trabajadores, registros, renombrados y huellas_actualizadas,
 * y la tabla "borrados" (tombstones) para propagar las eliminaciones de
 * trabajadores entre dispositivos sin reintroducirlos. Las fusiones usan
 * last-write-wins (gana el updated_at más reciente).
 */
class AttendanceDb(context: Context) : SQLiteOpenHelper(context, "asistencia.db", null, 6) {

    companion object {
        private const val TABLA = "registros"
        private const val FECHA = "fecha"
        private const val HORA = "hora"
        private const val NOMBRE = "nombre"

        private const val TABLA_TRABAJADORES = "trabajadores"
        private const val TR_NOMBRE = "nombre"
        private const val TR_EMBEDDING = "embedding"
        private const val TR_FECHA = "fecha_registro"

        private const val TABLA_OCULTOS = "ocultos"
        private const val OC_NOMBRE = "nombre"

        private const val TABLA_RENOMBRADOS = "renombrados"
        private const val RN_ORIGINAL = "nombre_pc"
        private const val RN_NUEVO = "nombre_nuevo"

        private const val TABLA_HUELLAS = "huellas_actualizadas"
        private const val HH_NOMBRE = "nombre"
        private const val HH_EMBEDDING = "embedding"

        private const val TABLA_BORRADOS = "borrados"
        private const val BR_NOMBRE = "nombre"

        private const val UPDATED = "updated_at"
    }

    private val ahora: Long get() = System.currentTimeMillis()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLA (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$FECHA TEXT NOT NULL, " +
                "$HORA TEXT NOT NULL, " +
                "$NOMBRE TEXT NOT NULL, " +
                "$UPDATED INTEGER DEFAULT 0, " +
                "UNIQUE($FECHA, $NOMBRE))"
        )
        crearTablaTrabajadores(db)
        crearTablaOcultos(db)
        crearTablaRenombrados(db)
        crearTablaHuellas(db)
        crearTablaBorrados(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            crearTablaTrabajadores(db)
        }
        if (oldVersion < 3) {
            crearTablaOcultos(db)
        }
        if (oldVersion < 4) {
            crearTablaRenombrados(db)
        }
        if (oldVersion < 5) {
            crearTablaHuellas(db)
        }
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE $TABLA ADD COLUMN $UPDATED INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLA_TRABAJADORES ADD COLUMN $UPDATED INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLA_RENOMBRADOS ADD COLUMN $UPDATED INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLA_HUELLAS ADD COLUMN $UPDATED INTEGER DEFAULT 0")
            } catch (_: Exception) {
            }
            crearTablaBorrados(db)
        }
    }

    private fun crearTablaTrabajadores(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLA_TRABAJADORES (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$TR_NOMBRE TEXT NOT NULL UNIQUE, " +
                "$TR_EMBEDDING BLOB, " +
                "$TR_FECHA TEXT NOT NULL, " +
                "$UPDATED INTEGER DEFAULT 0)"
        )
    }

    private fun crearTablaOcultos(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLA_OCULTOS (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$OC_NOMBRE TEXT NOT NULL UNIQUE)"
        )
    }

    private fun crearTablaRenombrados(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLA_RENOMBRADOS (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$RN_ORIGINAL TEXT NOT NULL UNIQUE, " +
                "$RN_NUEVO TEXT NOT NULL, " +
                "$UPDATED INTEGER DEFAULT 0)"
        )
    }

    private fun crearTablaHuellas(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLA_HUELLAS (" +
                "$HH_NOMBRE TEXT NOT NULL PRIMARY KEY, " +
                "$HH_EMBEDDING BLOB NOT NULL, " +
                "$UPDATED INTEGER DEFAULT 0)"
        )
    }

    private fun crearTablaBorrados(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLA_BORRADOS (" +
                "$BR_NOMBRE TEXT NOT NULL PRIMARY KEY, " +
                "$UPDATED INTEGER NOT NULL)"
        )
    }

    /**
     * Registra la asistencia. Devuelve true si fue un registro nuevo
     * y false si el trabajador ya estaba registrado ese día.
     */
    fun registrar(fecha: String, hora: String, nombre: String): Boolean {
        val valores = ContentValues().apply {
            put(FECHA, fecha)
            put(HORA, hora)
            put(NOMBRE, nombre)
            put(UPDATED, ahora)
        }
        val resultado = writableDatabase.insertWithOnConflict(
            TABLA, null, valores, SQLiteDatabase.CONFLICT_IGNORE
        )
        return resultado != -1L
    }

    /** Registros de un día, ordenados por hora (igual que el CSV del PC). */
    fun registradosDe(fecha: String): List<Registro> {
        val lista = mutableListOf<Registro>()
        readableDatabase.query(
            TABLA,
            arrayOf(FECHA, HORA, NOMBRE),
            "$FECHA = ?",
            arrayOf(fecha),
            null,
            null,
            "$HORA ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista += Registro(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2)
                )
            }
        }
        return lista
    }

    /** Historial de asistencias de un trabajador (todas las fechas), del más reciente al más antiguo. */
    fun registrosDeNombre(nombre: String): List<Registro> {
        val lista = mutableListOf<Registro>()
        readableDatabase.query(
            TABLA,
            arrayOf(FECHA, HORA, NOMBRE),
            "$NOMBRE = ?",
            arrayOf(nombre),
            null,
            null,
            "$FECHA DESC, $HORA ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista += Registro(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2)
                )
            }
        }
        return lista
    }

    // ------------------------------------------------------------------
    //  Trabajadores del PC eliminados (v3): no se pueden borrar del APK,
    //  así que se excluyen de la lista y del reconocimiento para siempre.
    // ------------------------------------------------------------------

    /** Nombres de los trabajadores del PC eliminados (filtran lista y reconocimiento). */
    fun trabajadoresPcEliminados(): Set<String> {
        val set = mutableSetOf<String>()
        readableDatabase.query(
            TABLA_OCULTOS, arrayOf(OC_NOMBRE), null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                set += cursor.getString(0)
            }
        }
        return set
    }

    // ------------------------------------------------------------------
    //  Edición de trabajadores (v3)
    // ------------------------------------------------------------------

    /**
     * Cambia el nombre de un trabajador: actualiza la fila de la tabla de
     * trabajadores y TODOS sus registros de asistencia (fecha/hora/nombre).
     */
    fun renombrarTrabajador(viejo: String, nuevo: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val c1 = ContentValues().apply {
                put(TR_NOMBRE, nuevo)
                put(UPDATED, ahora)
            }
            db.update(TABLA_TRABAJADORES, c1, "$TR_NOMBRE = ?", arrayOf(viejo))
            val c2 = ContentValues().apply {
                put(NOMBRE, nuevo)
                put(UPDATED, ahora)
            }
            db.update(TABLA, c2, "$NOMBRE = ?", arrayOf(viejo))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Elimina al trabajador de la tabla de trabajadores y su historial de asistencias. */
    fun eliminarTrabajador(nombre: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            marcarBorrado(db, nombre)
            db.delete(TABLA_TRABAJADORES, "$TR_NOMBRE = ?", arrayOf(nombre))
            db.delete(TABLA, "$NOMBRE = ?", arrayOf(nombre))
            db.delete(TABLA_HUELLAS, "$HH_NOMBRE = ?", arrayOf(nombre))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Elimina en lote: borra a los trabajadores de la tabla de trabajadores
     * Y su historial de asistencias completo (para limpieza de temporada).
     */
    fun eliminarTrabajadoresConHistorial(nombres: Collection<String>) {
        if (nombres.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (nombre in nombres) {
                marcarBorrado(db, nombre)
                db.delete(TABLA_TRABAJADORES, "$TR_NOMBRE = ?", arrayOf(nombre))
                db.delete(TABLA, "$NOMBRE = ?", arrayOf(nombre))
                db.delete(TABLA_HUELLAS, "$HH_NOMBRE = ?", arrayOf(nombre))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Tombstone: registra que un trabajador fue eliminado (para la sincronización). */
    private fun marcarBorrado(db: SQLiteDatabase, nombre: String) {
        val ts = ahora
        db.insertWithOnConflict(
            TABLA_BORRADOS, null,
            ContentValues().apply {
                put(BR_NOMBRE, nombre)
                put(UPDATED, ts)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Elimina en lote a trabajadores del PC (no se pueden borrar del APK:
     * se excluyen para siempre) y borra su historial de asistencias.
     */
    fun eliminarPcConHistorial(nombres: Collection<String>) {
        if (nombres.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (nombre in nombres) {
                val valores = ContentValues().apply { put(OC_NOMBRE, nombre) }
                db.insertWithOnConflict(
                    TABLA_OCULTOS, null, valores, SQLiteDatabase.CONFLICT_REPLACE
                )
                db.delete(TABLA, "$NOMBRE = ?", arrayOf(nombre))
                db.delete(TABLA_HUELLAS, "$HH_NOMBRE = ?", arrayOf(nombre))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------------
    //  Renombres de trabajadores del PC (v4)
    // ------------------------------------------------------------------

    /**
     * Cambia el nombre de un trabajador del PC: guarda el mapeo (nombre
     * original en nombres.json -> nombre actual) y actualiza TODOS sus
     * registros de asistencia. El embedding no cambia (la huella se busca por
     * índice), así que el reconocimiento facial sigue funcionando.
     */
    fun renombrarPc(viejo: String, nuevo: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Si "viejo" ya era un nombre renombrado, se actualiza esa fila;
            // si no, se inserta el mapeo original -> nuevo.
            val c1 = ContentValues().apply {
                put(RN_NUEVO, nuevo)
                put(UPDATED, ahora)
            }
            val filas = db.update(TABLA_RENOMBRADOS, c1, "$RN_NUEVO = ?", arrayOf(viejo))
            if (filas == 0) {
                val v = ContentValues().apply {
                    put(RN_ORIGINAL, viejo)
                    put(RN_NUEVO, nuevo)
                    put(UPDATED, ahora)
                }
                db.insertWithOnConflict(
                    TABLA_RENOMBRADOS, null, v, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            // Mapeos identidad (X -> X) ya no aportan nada: se limpian
            db.delete(TABLA_RENOMBRADOS, "$RN_ORIGINAL = $RN_NUEVO", null)

            // Historial de asistencias con el nombre viejo -> nuevo
            val c2 = ContentValues().apply {
                put(NOMBRE, nuevo)
                put(UPDATED, ahora)
            }
            db.update(TABLA, c2, "$NOMBRE = ?", arrayOf(viejo))

            // La huella sobreescrita (si existe) se mueve al nuevo nombre
            val c3 = ContentValues().apply { put(HH_NOMBRE, nuevo) }
            db.update(TABLA_HUELLAS, c3, "$HH_NOMBRE = ?", arrayOf(viejo))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Mapa de renombres del PC: nombre original en nombres.json -> nombre actual. */
    fun renombres(): Map<String, String> {
        val mapa = HashMap<String, String>()
        readableDatabase.query(
            TABLA_RENOMBRADOS, arrayOf(RN_ORIGINAL, RN_NUEVO), null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                mapa[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return mapa
    }

    // ------------------------------------------------------------------
    //  Trabajadores registrados desde la app (v2)
    // ------------------------------------------------------------------

    /**
     * Guarda un trabajador registrado desde la app con su huella facial.
     * Devuelve false si ya existía con ese nombre.
     */
    fun insertarTrabajador(nombre: String, embedding: FloatArray?, fecha: String): Boolean {
        val valores = ContentValues().apply {
            put(TR_NOMBRE, nombre)
            put(TR_EMBEDDING, embedding?.let(::floatsABytes))
            put(TR_FECHA, fecha)
            put(UPDATED, ahora)
        }
        val resultado = writableDatabase.insertWithOnConflict(
            TABLA_TRABAJADORES, null, valores, SQLiteDatabase.CONFLICT_IGNORE
        )
        return resultado != -1L
    }

    fun existeTrabajador(nombre: String): Boolean {
        readableDatabase.query(
            TABLA_TRABAJADORES,
            arrayOf(TR_NOMBRE),
            "$TR_NOMBRE = ?",
            arrayOf(nombre),
            null, null, null
        ).use { return it.moveToFirst() }
    }

    /** Todos los trabajadores registrados desde la app, ordenados por nombre. */
    fun trabajadores(): List<Trabajador> {
        val lista = mutableListOf<Trabajador>()
        readableDatabase.query(
            TABLA_TRABAJADORES,
            arrayOf(TR_NOMBRE, TR_FECHA),
            null, null, null, null,
            "$TR_NOMBRE COLLATE NOCASE ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista += Trabajador(cursor.getString(0), cursor.getString(1))
            }
        }
        return lista
    }

    /** Trabajadores con huella facial, para sumarlos a la galería de reconocimiento. */
    fun trabajadoresConEmbedding(): List<Dinamico> {
        val lista = mutableListOf<Dinamico>()
        readableDatabase.query(
            TABLA_TRABAJADORES,
            arrayOf(TR_NOMBRE, TR_EMBEDDING),
            "$TR_EMBEDDING IS NOT NULL",
            null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista += Dinamico(cursor.getString(0), bytesAFlotantes(cursor.getBlob(1)))
            }
        }
        return lista
    }

    private fun floatsABytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        val out = ByteArray(v.size * 4)
        bb.rewind()
        bb.get(out)
        return out
    }

    private fun bytesAFlotantes(b: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    // ------------------------------------------------------------------
    //  Huellas faciales actualizadas (v5)
    //  Al actualizar la foto de un trabajador se recalcula su huella:
    //   - los de la app: se reemplaza el BLOB de la tabla trabajadores
    //   - los del PC: se guarda en huellas_actualizadas, con prioridad sobre
    //     embeddings.bin al buscar
    // ------------------------------------------------------------------

    /** Reemplaza la huella facial de un trabajador registrado desde la app. */
    fun actualizarEmbedding(nombre: String, embedding: FloatArray?): Int {
        val valores = ContentValues().apply {
            put(TR_EMBEDDING, embedding?.let(::floatsABytes))
            put(UPDATED, ahora)
        }
        return writableDatabase.update(
            TABLA_TRABAJADORES, valores, "$TR_NOMBRE = ?", arrayOf(nombre)
        )
    }

    /**
     * Guarda (o reemplaza) la huella que SOBRESCRIBE a la del APK
     * (trabajadores del PC, cuyo embedding vive en assets/embeddings.bin).
     */
    fun guardarHuellaSobrescrita(nombre: String, embedding: FloatArray) {
        val valores = ContentValues().apply {
            put(HH_NOMBRE, nombre)
            put(HH_EMBEDDING, floatsABytes(embedding))
            put(UPDATED, ahora)
        }
        writableDatabase.insertWithOnConflict(
            TABLA_HUELLAS, null, valores, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Huellas sobreescritas (trabajadores del PC actualizados), por nombre actual. */
    fun huellasSobrescritas(): Map<String, FloatArray> {
        val mapa = HashMap<String, FloatArray>()
        readableDatabase.query(
            TABLA_HUELLAS, arrayOf(HH_NOMBRE, HH_EMBEDDING), null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                mapa[cursor.getString(0)] = bytesAFlotantes(cursor.getBlob(1))
            }
        }
        return mapa
    }

    /**
     * Actualiza la huella de un trabajador según su origen: los de la app
     * reemplazan su fila; los del PC guardan una sobrescritura con prioridad.
     */
    fun actualizarHuella(nombre: String, embedding: FloatArray, esDeApp: Boolean) {
        if (esDeApp) {
            actualizarEmbedding(nombre, embedding)
        } else {
            guardarHuellaSobrescrita(nombre, embedding)
        }
    }

    // ------------------------------------------------------------------
    //  Sincronización WiFi (v6): lectura/escritura del conjunto de datos
    // ------------------------------------------------------------------

    /** Trabajador de la app para sincronizar: nombre, fecha, huella y marca de tiempo. */
    class TrabajadorSync(
        val nombre: String,
        val fechaRegistro: String,
        val embedding: FloatArray?,
        val updatedAt: Long
    )

    /** Registro de asistencia para sincronizar. */
    class RegistroSync(
        val fecha: String,
        val hora: String,
        val nombre: String,
        val updatedAt: Long
    )

    /** Renombre de trabajador del PC para sincronizar. */
    class RenombreSync(
        val original: String,
        val nuevo: String,
        val updatedAt: Long
    )

    /** Huella sobrescrita (PC) para sincronizar. */
    class HuellaSync(
        val nombre: String,
        val embedding: FloatArray,
        val updatedAt: Long
    )

    fun trabajadoresParaSync(): List<TrabajadorSync> {
        val lista = mutableListOf<TrabajadorSync>()
        readableDatabase.query(
            TABLA_TRABAJADORES,
            arrayOf(TR_NOMBRE, TR_FECHA, TR_EMBEDDING, UPDATED),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista += TrabajadorSync(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getBlob(2)?.let(::bytesAFlotantes),
                    cursor.getLong(3)
                )
            }
        }
        return lista
    }

    fun registrosParaSync(): List<RegistroSync> {
        val lista = mutableListOf<RegistroSync>()
        readableDatabase.query(
            TABLA,
            arrayOf(FECHA, HORA, NOMBRE, UPDATED),
            null, null, null, null,
            "$FECHA ASC, $HORA ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista += RegistroSync(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getLong(3)
                )
            }
        }
        return lista
    }

    fun renombresParaSync(): List<RenombreSync> {
        val lista = mutableListOf<RenombreSync>()
        readableDatabase.query(
            TABLA_RENOMBRADOS,
            arrayOf(RN_ORIGINAL, RN_NUEVO, UPDATED),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista += RenombreSync(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getLong(2)
                )
            }
        }
        return lista
    }

    fun huellasParaSync(): List<HuellaSync> {
        val lista = mutableListOf<HuellaSync>()
        readableDatabase.query(
            TABLA_HUELLAS,
            arrayOf(HH_NOMBRE, HH_EMBEDDING, UPDATED),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista += HuellaSync(
                    cursor.getString(0),
                    bytesAFlotantes(cursor.getBlob(1)),
                    cursor.getLong(2)
                )
            }
        }
        return lista
    }

    /** Tombstones (trabajadores eliminados) para sincronizar. */
    fun borradosParaSync(): List<Pair<String, Long>> {
        val lista = mutableListOf<Pair<String, Long>>()
        readableDatabase.query(
            TABLA_BORRADOS,
            arrayOf(BR_NOMBRE, UPDATED),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista += cursor.getString(0) to cursor.getLong(1)
            }
        }
        return lista
    }

    /**
     * Inserta (o actualiza si el remoto es más reciente) un trabajador de la
     * app. Devuelve true si el remoto ganó (insertado o sobrescrito).
     */
    fun upsertTrabajadorSync(
        nombre: String,
        fecha: String,
        embedding: FloatArray?,
        updatedAt: Long
    ): Boolean {
        val db = writableDatabase
        val valores = ContentValues().apply {
            put(TR_NOMBRE, nombre)
            put(TR_FECHA, fecha)
            put(TR_EMBEDDING, embedding?.let(::floatsABytes))
            put(UPDATED, updatedAt)
        }
        val insertado = db.insertWithOnConflict(
            TABLA_TRABAJADORES, null, valores, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (insertado) return true
        val filas = db.update(
            TABLA_TRABAJADORES, valores,
            "$TR_NOMBRE = ? AND $UPDATED < ?",
            arrayOf(nombre, updatedAt.toString())
        )
        return filas > 0
    }

    /** Registro de asistencia: solo se inserta si no existe (inmutable, sin duplicados). */
    fun upsertRegistroSync(fecha: String, hora: String, nombre: String, updatedAt: Long): Boolean {
        val valores = ContentValues().apply {
            put(FECHA, fecha)
            put(HORA, hora)
            put(NOMBRE, nombre)
            put(UPDATED, updatedAt)
        }
        val resultado = writableDatabase.insertWithOnConflict(
            TABLA, null, valores, SQLiteDatabase.CONFLICT_IGNORE
        )
        return resultado != -1L
    }

    /**
     * Aplica un renombre del PC si es más reciente que el local. Replica el
     * efecto de renombrarPc: actualiza el mapeo, la asistencia y la huella.
     */
    fun upsertRenombreSync(original: String, nuevo: String, updatedAt: Long): Boolean {
        val db = writableDatabase

        // ¿Existe el mapeo con ese original? si sí y es más nuevo, actualizarlo
        val c1 = ContentValues().apply {
            put(RN_NUEVO, nuevo)
            put(UPDATED, updatedAt)
        }
        var filas = db.update(
            TABLA_RENOMBRADOS, c1,
            "$RN_ORIGINAL = ? AND $UPDATED < ?",
            arrayOf(original, updatedAt.toString())
        )
        if (filas == 0 && !existeRenombre(original)) {
            // ¿original era a su vez un nombre ya renombrado? -> avanzar ese mapeo
            filas = db.update(
                TABLA_RENOMBRADOS, c1,
                "$RN_NUEVO = ? AND $UPDATED < ?",
                arrayOf(original, updatedAt.toString())
            )
            if (filas == 0 && !existeRenombreNuevo(original)) {
                val v = ContentValues().apply {
                    put(RN_ORIGINAL, original)
                    put(RN_NUEVO, nuevo)
                    put(UPDATED, updatedAt)
                }
                db.insertWithOnConflict(
                    TABLA_RENOMBRADOS, null, v, SQLiteDatabase.CONFLICT_REPLACE
                )
                filas = 1
            }
        }
        if (filas == 0) return false

        db.delete(TABLA_RENOMBRADOS, "$RN_ORIGINAL = $RN_NUEVO", null)

        val c2 = ContentValues().apply {
            put(NOMBRE, nuevo)
            put(UPDATED, updatedAt)
        }
        db.update(TABLA, c2, "$NOMBRE = ?", arrayOf(original))

        val c3 = ContentValues().apply {
            put(HH_NOMBRE, nuevo)
            put(UPDATED, updatedAt)
        }
        db.update(TABLA_HUELLAS, c3, "$HH_NOMBRE = ?", arrayOf(original))
        return true
    }

    private fun existeRenombre(original: String): Boolean {
        readableDatabase.query(
            TABLA_RENOMBRADOS, arrayOf(RN_ORIGINAL),
            "$RN_ORIGINAL = ?", arrayOf(original), null, null, null
        ).use { return it.moveToFirst() }
    }

    private fun existeRenombreNuevo(nuevo: String): Boolean {
        readableDatabase.query(
            TABLA_RENOMBRADOS, arrayOf(RN_NUEVO),
            "$RN_NUEVO = ?", arrayOf(nuevo), null, null, null
        ).use { return it.moveToFirst() }
    }

    /** Huella sobrescrita del PC: inserta o actualiza si el remoto es más nuevo. */
    fun upsertHuellaSync(nombre: String, embedding: FloatArray, updatedAt: Long): Boolean {
        val db = writableDatabase
        val valores = ContentValues().apply {
            put(HH_NOMBRE, nombre)
            put(HH_EMBEDDING, floatsABytes(embedding))
            put(UPDATED, updatedAt)
        }
        val insertado = db.insertWithOnConflict(
            TABLA_HUELLAS, null, valores, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (insertado) return true
        val filas = db.update(
            TABLA_HUELLAS, valores,
            "$HH_NOMBRE = ? AND $UPDATED < ?",
            arrayOf(nombre, updatedAt.toString())
        )
        return filas > 0
    }

    /**
     * Excluye a un trabajador del PC (union): si no estaba oculto, lo oculta y
     * borra su asistencia y huella sobrescrita local.
     */
    fun agregarOcultoSync(nombre: String): Boolean {
        val db = writableDatabase
        val valores = ContentValues().apply { put(OC_NOMBRE, nombre) }
        val insertado = db.insertWithOnConflict(
            TABLA_OCULTOS, null, valores, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (insertado) {
            db.delete(TABLA, "$NOMBRE = ?", arrayOf(nombre))
            db.delete(TABLA_HUELLAS, "$HH_NOMBRE = ?", arrayOf(nombre))
        }
        return insertado
    }

    /**
     * Aplica una eliminación remota (tombstone) si es más reciente que la local:
     * borra trabajador, asistencia y huella sobrescrita.
     */
    fun aplicarBorradoSync(nombre: String, updatedAt: Long): Boolean {
        val localTs = borradoTs(nombre)
        if (localTs != null && localTs >= updatedAt) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                TABLA_BORRADOS, null,
                ContentValues().apply {
                    put(BR_NOMBRE, nombre)
                    put(UPDATED, updatedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.delete(TABLA_TRABAJADORES, "$TR_NOMBRE = ?", arrayOf(nombre))
            db.delete(TABLA, "$NOMBRE = ?", arrayOf(nombre))
            db.delete(TABLA_HUELLAS, "$HH_NOMBRE = ?", arrayOf(nombre))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    private fun borradoTs(nombre: String): Long? {
        readableDatabase.query(
            TABLA_BORRADOS, arrayOf(UPDATED),
            "$BR_NOMBRE = ?", arrayOf(nombre), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }
}


