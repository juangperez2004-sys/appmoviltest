package com.juan.asistenciaapp.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.juan.asistenciaapp.Diag
import com.juan.asistenciaapp.data.AttendanceDb
import com.juan.asistenciaapp.face.Fotos
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Motor de sincronización WiFi: descubrimiento de dispositivos, intercambio de
 * datos por HTTP local, generación/lectura de QR y fusión last-write-wins.
 */
object SyncEngine {

    private const val TAG = "Sync"

    // ------------------------------------------------------------------
    //  QR
    // ------------------------------------------------------------------

    /** Dirección de este dispositivo: "asistencia://ip:puerto". */
    fun miUri(context: Context): String {
        val ip = SyncServidor.ipLocal() ?: "127.0.0.1"
        return "asistencia://$ip:${SyncServidor.PUERTO_HTTP}"
    }

    fun generarQr(texto: String, lado: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matriz = QRCodeWriter()
            .encode(texto, BarcodeFormat.QR_CODE, lado, lado, hints)
        val px = IntArray(lado * lado)
        for (y in 0 until lado) {
            for (x in 0 until lado) {
                px[y * lado + x] = if (matriz.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(px, lado, lado, Bitmap.Config.RGB_565)
    }

    fun decodificarQr(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        return try {
            val fuente = RGBLuminanceSource(w, h, px)
            val resultado = MultiFormatReader().decodeWithState(
                BinaryBitmap(HybridBinarizer(fuente))
            )
            resultado?.text
        } catch (_: Exception) {
            null
        }
    }

    /** Extrae "ip:puerto" de una URI "asistencia://ip:puerto". */
    fun peerDeQr(uri: String): String? {
        val limpiado = uri.removePrefix("asistencia://")
        val parte = limpiado.substringBefore("/")
        val partes = parte.split(":")
        if (partes.size == 2 && partes[1].isNotEmpty()) {
            return partes[0] + ":" + SyncServidor.PUERTO_HTTP
        }
        return null
    }

    // ------------------------------------------------------------------
    //  Descubrimiento
    // ------------------------------------------------------------------

    /** Encuentra a los demás dispositivos: broadcast UDP + vinculados por QR. */
    fun descubrir(context: Context): Set<String> {
        val encontrados = mutableSetOf<String>()

        // Broadcast UDP de descubrimiento
        try {
            val socket = DatagramSocket()
            socket.broadcast = true
            val msg = SyncServidor.PING.toByteArray(Charsets.UTF_8)
            socket.send(
                DatagramPacket(
                    msg, msg.size,
                    InetAddress.getByName("255.255.255.255"),
                    SyncServidor.PUERTO_UDP
                )
            )
            socket.soTimeout = 1500
            val buf = ByteArray(512)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                val txt = String(p.data, 0, p.length, Charsets.UTF_8)
                if (txt.startsWith(SyncServidor.PONG)) {
                    encontrados += "${p.address.hostAddress}:${SyncServidor.PUERTO_HTTP}"
                }
            }
        } catch (_: Exception) {
            // timeout normal al terminar de escuchar
        }

        encontrados += SyncServidor.peersConocidos(context)

        // Nunca sincronizar consigo mismo
        SyncServidor.ipLocal()?.let { propio ->
            encontrados -= "$propio:${SyncServidor.PUERTO_HTTP}"
        }
        return encontrados
    }

    // ------------------------------------------------------------------
    //  Sincronización
    // ------------------------------------------------------------------

    /**
     * Sincroniza con todos los dispositivos encontrados:
     *  1) baja y fusiona de cada uno;
     *  2) pide a cada uno que baje de este (ya fusionado).
     * Así todos convergen a la misma información en una sola ronda.
     */
    fun sincronizarTodo(context: Context, actualizar: (String) -> Unit): String {
        Diag.iniciar(context.applicationContext)
        val peers = descubrir(context)
        if (peers.isEmpty()) {
            Diag.marcar("sync: sin dispositivos")
            actualizar("Sin dispositivos encontrados en la red WiFi")
            return "Sin dispositivos encontrados. Verifica que todos estén en el mismo WiFi " +
                "y con la app abierta. Si el router bloquea el descubrimiento, escanea el QR."
        }
        actualizar("Dispositivos encontrados: ${peers.size}")
        Diag.marcar("sync: ${peers.size} dispositivo(s): ${peers.joinToString(", ")}")

        val db = AttendanceDb(context)
        val partes = mutableListOf<String>()

        // 1) Bajar de todos y fusionar
        for (peer in peers) {
            actualizar("Conectando con $peer…")
            val json = httpGet("http://$peer/sync/datos")
            if (json == null) {
                Diag.marcar("sync: sin conexión con $peer")
                partes += "$peer: SIN CONEXIÓN (¿app abierta? ¿mismo WiFi? ¿el router permite que los equipos se hablen?)"
                continue
            }
            // Si el servidor respondió con un error explícito, mostrarlo
            if (json.trimStart().startsWith("{\"ok\":false")) {
                val err = try {
                    JSONObject(json).optString("error", "desconocido")
                } catch (_: Exception) {
                    "respuesta no válida"
                }
                Diag.marcar("sync: error de $peer: $err")
                partes += "$peer: error ($err)"
                continue
            }
            val resumen = SyncMerge.aplicarJson(context, db, json)
            Diag.marcar("sync: $peer → $resumen")
            partes += "$peer: $resumen"
        }

        // 2) Que todos bajen de este dispositivo (ya con todo fusionado)
        val ip = SyncServidor.ipLocal()
        if (ip == null) {
            Diag.marcar("sync: sin IP local")
            actualizar("Sin conexión WiFi para compartir datos")
            return partes.joinToString("\n") + "\n\nSin conexión WiFi para compartir datos."
        }
        val miUrl = "http://$ip:${SyncServidor.PUERTO_HTTP}/sync/datos"
        for (peer in peers) {
            actualizar("Actualizando $peer…")
            val url = "http://$peer/sync/orden?url=${URLEncoder.encode(miUrl, "UTF-8")}"
            val ok = httpGet(url) != null
            Diag.marcar("sync: actualizar $peer ${if (ok) "ok" else "falló"}")
            partes += "$peer: ${if (ok) "actualizado" else "no confirmado"}"
        }

        val texto = partes.joinToString("\n")
        actualizar(texto)
        return texto
    }

    /** GET HTTP simple (con timeout). */
    private fun httpGet(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "httpGet falló: $url (${e.message})")
            null
        }
    }

    /** JSON con los datos de este dispositivo (usa la base local). */
    fun datasetJson(context: Context): String =
        SyncMerge.datasetJson(context, AttendanceDb(context))

    /** Descarga el JSON de una URL (otro dispositivo) y lo fusiona localmente. */
    fun aplicarDesdeUrl(context: Context, url: String) {
        SyncMerge.aplicarDesdeUrl(context, url)
    }
}

/**
 * Serialización y fusión del conjunto de datos de la sincronización.
 */
object SyncMerge {

    /** JSON con todos los datos dinámicos de este dispositivo (+ fotos en base64). */
    fun datasetJson(context: Context, db: AttendanceDb): String {
        val root = JSONObject()

        val trabajadores = JSONArray()
        for (t in db.trabajadoresParaSync()) {
            trabajadores.put(
                JSONObject()
                    .put("nombre", t.nombre)
                    .put("fecha", t.fechaRegistro)
                    .put("emb", if (t.embedding == null) JSONObject.NULL else floatsA64(t.embedding))
                    .put("ts", t.updatedAt)
            )
        }
        root.put("trabajadores", trabajadores)

        val registros = JSONArray()
        for (r in db.registrosParaSync()) {
            registros.put(
                JSONObject()
                    .put("fecha", r.fecha)
                    .put("hora", r.hora)
                    .put("nombre", r.nombre)
                    .put("ts", r.updatedAt)
            )
        }
        root.put("registros", registros)

        val renombres = JSONArray()
        for (r in db.renombresParaSync()) {
            renombres.put(
                JSONObject()
                    .put("or", r.original)
                    .put("nu", r.nuevo)
                    .put("ts", r.updatedAt)
            )
        }
        root.put("renombres", renombres)

        root.put("ocultos", JSONArray(db.trabajadoresPcEliminados().toList()))

        val huellas = JSONArray()
        for (h in db.huellasParaSync()) {
            huellas.put(
                JSONObject()
                    .put("nombre", h.nombre)
                    .put("emb", floatsA64(h.embedding))
                    .put("ts", h.updatedAt)
            )
        }
        root.put("huellas", huellas)

        val borrados = JSONArray()
        for ((nombre, ts) in db.borradosParaSync()) {
            borrados.put(JSONObject().put("nombre", nombre).put("ts", ts))
        }
        root.put("borrados", borrados)

        // Fotos de los trabajadores de la app (los del PC vienen en assets)
        val fotos = JSONArray()
        for (t in db.trabajadoresParaSync()) {
            val f = Fotos.archivo(context, t.nombre)
            if (f.exists()) {
                try {
                    fotos.put(
                        JSONObject()
                            .put("nombre", t.nombre)
                            .put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                    )
                } catch (_: Exception) {
                }
            }
        }
        root.put("fotos", fotos)

        return root.toString()
    }

    /** Descarga el JSON de una URL y lo fusiona localmente. */
    fun aplicarDesdeUrl(context: Context, url: String) {
        val json = httpGetDe(url)
            ?: throw IllegalStateException("No se pudo descargar los datos del otro dispositivo")
        aplicarJson(context, AttendanceDb(context), json)
    }

    /**
     * Fusiona el dataset remoto en la base local. Reglas:
     *  - last-write-wins por updated_at en trabajadores, renombres y huellas;
     *  - asistencias y ocultos: solo se agregan (sin duplicados);
     *  - borrados: un tombstone más reciente elimina al trabajador local.
     */
    fun aplicarJson(context: Context, db: AttendanceDb, json: String): String {
        val root = JSONObject(json)
        var agregados = 0
        var registros = 0
        var renombres = 0
        var ocultos = 0
        var huellas = 0
        var borrados = 0
        var fotos = 0

        // Tombstones primero: un borrado reciente evita reintroducir al trabajador
        val mapaBorrados = HashMap<String, Long>()
        val arrBorrados = root.optJSONArray("borrados") ?: JSONArray()
        for (i in 0 until arrBorrados.length()) {
            val o = arrBorrados.getJSONObject(i)
            val nombre = o.getString("nombre")
            val ts = o.getLong("ts")
            mapaBorrados[nombre] = ts
            if (db.aplicarBorradoSync(nombre, ts)) {
                borrados++
                Fotos.eliminar(context, nombre)
            }
        }

        // Fotos recibidas
        val fotosPorNombre = HashMap<String, String>()
        val arrFotos = root.optJSONArray("fotos") ?: JSONArray()
        for (i in 0 until arrFotos.length()) {
            val o = arrFotos.getJSONObject(i)
            fotosPorNombre[o.getString("nombre")] = o.getString("data")
        }

        // Trabajadores de la app
        val arrTrabajadores = root.optJSONArray("trabajadores") ?: JSONArray()
        for (i in 0 until arrTrabajadores.length()) {
            val o = arrTrabajadores.getJSONObject(i)
            val nombre = o.getString("nombre")
            val ts = o.getLong("ts")
            val borradoTs = mapaBorrados[nombre]
            if (borradoTs != null && borradoTs >= ts) continue
            val emb = if (o.isNull("emb")) null else a64AFlotes(o.getString("emb"))
            if (db.upsertTrabajadorSync(nombre, o.optString("fecha", ""), emb, ts)) {
                agregados++
                val foto = fotosPorNombre[nombre]
                if (foto != null) {
                    try {
                        val bytes = Base64.decode(foto, Base64.NO_WRAP)
                        val archivo = Fotos.archivo(context, nombre)
                        archivo.parentFile?.mkdirs()
                        archivo.writeBytes(bytes)
                        fotos++
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // Asistencias (inmutables: solo se agregan si no existen)
        val arrRegistros = root.optJSONArray("registros") ?: JSONArray()
        for (i in 0 until arrRegistros.length()) {
            val o = arrRegistros.getJSONObject(i)
            if (db.upsertRegistroSync(
                    o.getString("fecha"), o.getString("hora"),
                    o.getString("nombre"), o.getLong("ts")
                )
            ) {
                registros++
            }
        }

        // Renombres del PC
        val arrRenombres = root.optJSONArray("renombres") ?: JSONArray()
        for (i in 0 until arrRenombres.length()) {
            val o = arrRenombres.getJSONObject(i)
            if (db.upsertRenombreSync(o.getString("or"), o.getString("nu"), o.getLong("ts"))) {
                renombres++
            }
        }

        // Eliminados del PC (union)
        val arrOcultos = root.optJSONArray("ocultos") ?: JSONArray()
        for (i in 0 until arrOcultos.length()) {
            if (db.agregarOcultoSync(arrOcultos.getString(i))) ocultos++
        }

        // Huellas sobrescritas
        val arrHuellas = root.optJSONArray("huellas") ?: JSONArray()
        for (i in 0 until arrHuellas.length()) {
            val o = arrHuellas.getJSONObject(i)
            if (db.upsertHuellaSync(
                    o.getString("nombre"),
                    a64AFlotes(o.getString("emb")),
                    o.getLong("ts")
                )
            ) {
                huellas++
            }
        }

        return "trabajadores +$agregados · asistencias +$registros · renombres $renombres · " +
            "eliminados $borrados · fotos $fotos · huellas $huellas"
    }

    // ------------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------------

    private fun httpGetDe(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("Sync", "httpGetDe falló: $url (${e.message})")
            null
        }
    }

    fun floatsA64(v: FloatArray): String {
        val bb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        val out = ByteArray(v.size * 4)
        bb.rewind()
        bb.get(out)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun a64AFlotes(s: String): FloatArray {
        val bytes = Base64.decode(s, Base64.NO_WRAP)
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }
}
