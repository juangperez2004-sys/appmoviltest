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
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.data.AttendanceDb
import com.juan.asistenciaapp.face.Fotos
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
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

    /** Dirección de este dispositivo: "asistencia://ip:puerto?n=nombre". */
    fun miUri(context: Context): String {
        val ip = SyncServidor.ipLocal() ?: "127.0.0.1"
        val nombre = URLEncoder.encode(SyncServidor.nombreDispositivo(context), "UTF-8")
        return "asistencia://$ip:${SyncServidor.PUERTO_HTTP}?n=$nombre"
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

    /** Extrae ip:puerto y nombre de una URI "asistencia://ip:puerto?n=nombre". */
    fun peerDeQr(uri: String): Dispositivo? {
        val limpiado = uri.removePrefix("asistencia://")
        val host = limpiado.substringBefore("?").substringBefore("/")
        val partes = host.split(":")
        if (partes.size != 2 || partes[1].isEmpty()) return null

        var nombre = "Dispositivo"
        val query = limpiado.substringAfter("?", "")
        for (par in query.split("&")) {
            val kv = par.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "n") {
                nombre = try {
                    java.net.URLDecoder.decode(kv[1], "UTF-8")
                } catch (_: Exception) {
                    kv[1]
                }
            }
        }
        return Dispositivo(partes[0] + ":" + SyncServidor.PUERTO_HTTP, nombre)
    }

    // ------------------------------------------------------------------
    //  Descubrimiento
    // ------------------------------------------------------------------

    /** Encuentra a los demás dispositivos: broadcast UDP + vinculados por QR. */
    fun descubrir(context: Context): Set<Dispositivo> {
        val encontrados = mutableSetOf<Dispositivo>()
        val propias = SyncServidor.ipsLocales()

        // Broadcast UDP de descubrimiento (los demás responden con su nombre).
        // El socket se ENLAZA a la IP del WiFi (no a 0.0.0.0): si hay datos
        // móviles activos, sin esto la ruta saliente puede usar la IP pública y
        // las respuestas (PONG) jamás regresan -> "sin dispositivos".
        try {
            val wifiIp = SyncServidor.ipLocal()
            val socket = if (wifiIp != null) {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(wifiIp, 0))
                }
            } else {
                DatagramSocket()
            }
            socket.broadcast = true
            val msg = SyncServidor.PING.toByteArray(Charsets.UTF_8)

            // Se manda a 255.255.255.255 (broadcast limitado) y al broadcast de
            // la subred (p. ej. 192.168.0.255): algunos routers solo aceptan uno.
            val destinos = mutableListOf(InetAddress.getByName("255.255.255.255"))
            wifiIp?.let {
                val parteRed = it.substringBeforeLast('.')
                if (parteRed.isNotEmpty()) {
                    try {
                        destinos += InetAddress.getByName("$parteRed.255")
                    } catch (_: Exception) {
                    }
                }
            }
            for (dest in destinos) {
                socket.send(DatagramPacket(msg, msg.size, dest, SyncServidor.PUERTO_UDP))
            }

            socket.soTimeout = 1500
            val buf = ByteArray(512)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                // Solo IPv4: una dirección IPv6 no es válida en "http://ip:puerto"
                if (p.address !is Inet4Address) continue
                val ip = p.address.hostAddress ?: continue
                if (ip in propias) continue // respuesta de uno mismo al broadcast
                val txt = String(p.data, 0, p.length, Charsets.UTF_8)
                if (txt.startsWith(SyncServidor.PONG)) {
                    val nombre = txt.removePrefix(SyncServidor.PONG).trim()
                        .ifEmpty { "Dispositivo" }
                    encontrados += Dispositivo("$ip:${SyncServidor.PUERTO_HTTP}", nombre)
                }
            }
            socket.close()
        } catch (_: Exception) {
            // timeout normal al terminar de escuchar
        }

        // Vinculados por QR (guardados como "nombre|ip:puerto"). Solo se usan
        // si su IP está en la MISMA red actual: un QR escaneado en otra red
        // apunta a una IP vieja que ya no existe, y ese "peer fantasma" hacía
        // que la app reportara "1 de 2 dispositivo(s)" sin razón.
        val redActual = SyncServidor.ipLocal()?.substringBeforeLast('.')
        for (entrada in SyncServidor.peersConocidos(context)) {
            val i = entrada.indexOf('|')
            val peer = if (i > 0) entrada.substring(i + 1) else entrada
            val nombre = if (i > 0) entrada.substring(0, i) else "Dispositivo"
            val ipPeer = peer.substringBefore(':')
            // Nunca sincronizar consigo mismo (aunque la IP del QR haya caducado
            // y hoy coincida con una IP propia)
            if (ipPeer in propias) continue
            // Red distinta -> IP caducada, se ignora
            if (redActual != null && ipPeer.substringBeforeLast('.') != redActual) continue
            encontrados += Dispositivo(peer, nombre)
        }

        Diag.marcar("sync: ipLocales=${propias} peers=${encontrados.map { it.peer }}")
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
     * Devuelve un mensaje amigable para el usuario administrativo.
     */
    fun sincronizarTodo(context: Context, actualizar: (String) -> Unit): String {
        Diag.iniciar(context.applicationContext)
        // Forzar que todo el tráfico TCP/HTTP vaya por el WiFi aunque haya
        // datos móviles activos (síntoma típico de Xiaomi: encuentra
        // dispositivos por UDP pero no conecta por TCP).
        SyncServidor.enlazarProcesoAWifi(context)
        return try {
            sincronizarTodoInterno(context, actualizar)
        } finally {
            SyncServidor.desenlazarProcesoDeRed(context)
        }
    }

    private fun sincronizarTodoInterno(context: Context, actualizar: (String) -> Unit): String {
        val peers = descubrir(context)
        if (peers.isEmpty()) {
            Diag.marcar("sync: sin dispositivos")
            val msg = context.getString(R.string.sync_sin_dispositivos)
            actualizar(msg)
            return msg
        }
        Diag.marcar("sync: ${peers.size} dispositivo(s): ${peers.joinToString(", ")}")
        actualizar(context.getString(R.string.sync_encontrados, peers.size))

        val db = AttendanceDb(context)
        val recibido = ConteoSync()
        val enviado = ConteoSync()
        var respondieron = 0
        var actualizados = 0

        // 1) Bajar de todos y fusionar
        for (d in peers) {
            actualizar(context.getString(R.string.sync_leyendo_dispositivo, d.nombre))
            val json = httpGet("http://${d.peer}/sync/datos")
            if (json == null) {
                Diag.marcar("sync: sin conexión con ${d.peer}")
                continue
            }
            if (json.trimStart().startsWith("{\"ok\":false")) {
                // El dispositivo SÍ respondió (HTTP 200), pero falló al generar
                // sus datos: no es un problema de conexión.
                val err = try {
                    JSONObject(json).optString("error", "desconocido")
                } catch (_: Exception) {
                    "respuesta no válida"
                }
                Diag.marcar("sync: error de ${d.peer}: $err")
                respondieron++
                continue
            }
            val c = SyncMerge.aplicarJson(context, db, json)
            Diag.marcar("sync: ${d.peer} → ${SyncMerge.codificarResumen(c)}")
            recibido += c
            respondieron++
        }

        // 2) Enviar a cada dispositivo NUESTRO dataset ya fusionado (PUSH por
        // POST). Antes se pedía a cada uno que lo descargara de nosotros
        // (GET /sync/orden?url=...): eso requería una conexión INVERSA
        // (peer -> nosotros) que en algunas redes con datos móviles activos
        // falla. Con el push solo hace falta la conexión en el sentido que ya
        // funciona (nosotros -> peer).
        val ip = SyncServidor.ipLocal()
        if (ip == null) {
            Diag.marcar("sync: sin IP local")
            val msg = context.getString(R.string.sync_sin_conexion)
            actualizar(msg)
            return msg
        }
        val miDataset = SyncEngine.datasetJson(context)
        val miUrl = "http://$ip:${SyncServidor.PUERTO_HTTP}/sync/datos"
        for (d in peers) {
            actualizar(context.getString(R.string.sync_actualizando_dispositivo, d.nombre))
            // 1) PUSH directo por POST (método nuevo, sin conexión inversa)
            var r = httpPost("http://${d.peer}/sync/datos", miDataset)
            // 2) Si el otro dispositivo es una versión ANTERIOR que no acepta
            //    el POST, se prueba el método viejo: que él descargue de nosotros.
            if (r == null) {
                Diag.marcar("sync: push POST no aceptado, probando método anterior con ${d.peer}")
                val url = "http://${d.peer}/sync/orden?url=${URLEncoder.encode(miUrl, "UTF-8")}"
                r = httpGet(url)
            }
            if (r != null) {
                enviado += SyncMerge.decodificarResumen(r)
                actualizados++
            }
            Diag.marcar("sync: push ${d.peer} ${if (r != null) "ok" else "falló"}")
        }

        val msg = construirResumen(context, peers.size, respondieron, actualizados, recibido, enviado)
        actualizar(msg)
        return msg
    }

    /** Arma el mensaje final amigable según lo que pasó en la ronda. */
    private fun construirResumen(
        context: Context,
        total: Int,
        respondieron: Int,
        actualizados: Int,
        recibido: ConteoSync,
        enviado: ConteoSync
    ): String {
        val res = context.resources
        val recibidoTxt = recibido.resumenFriendly()
        val enviadoTxt = enviado.resumenFriendly()
        val hayCambios = recibidoTxt.isNotEmpty() || enviadoTxt.isNotEmpty()
        val completo = respondieron == total && actualizados == total
        val lineas = mutableListOf<String>()

        when {
            respondieron == 0 -> {
                lineas += res.getString(R.string.sync_sin_conexion)
            }

            completo && !hayCambios -> {
                // Sin cambios y envío confirmado: mensaje corto y claro
                lineas += res.getString(R.string.sync_correcta)
            }

            completo -> {
                lineas += res.getString(R.string.sync_listo, total)
                if (recibidoTxt.isNotEmpty()) {
                    lineas += res.getString(R.string.sync_recibido, recibidoTxt)
                }
                if (enviadoTxt.isNotEmpty()) {
                    lineas += res.getString(R.string.sync_enviado, enviadoTxt)
                }
            }

            else -> {
                lineas += res.getString(R.string.sync_parcial, respondieron, total)
                if (recibidoTxt.isNotEmpty()) {
                    lineas += res.getString(R.string.sync_recibido, recibidoTxt)
                }
                if (enviadoTxt.isNotEmpty()) {
                    lineas += res.getString(R.string.sync_enviado, enviadoTxt)
                }
                if (actualizados < respondieron) {
                    lineas += res.getString(R.string.sync_envio_no_confirmado)
                }
            }
        }

        return lineas.joinToString("\n")
    }

    /** GET HTTP simple (con timeout). Registra el motivo del fallo en diag.txt. */
    private fun httpGet(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                Diag.marcar("sync: http $code para $url")
                null
            }
        } catch (e: Exception) {
            Diag.marcar("sync: fallo http $url -> ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "httpGet falló: $url (${e.message})")
            null
        }
    }

    /** POST HTTP con cuerpo JSON (PUSH de la sincronización). */
    private fun httpPost(url: String, cuerpo: String): String? {
        return try {
            val bytes = cuerpo.toByteArray(Charsets.UTF_8)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                Diag.marcar("sync: push http $code para $url")
                null
            }
        } catch (e: Exception) {
            Diag.marcar("sync: fallo push $url -> ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "httpPost falló: $url (${e.message})")
            null
        }
    }

    /** JSON con los datos de este dispositivo (usa la base local). */
    fun datasetJson(context: Context): String =
        SyncMerge.datasetJson(context, AttendanceDb(context))

    /** Descarga el JSON de una URL (otro dispositivo) y lo fusiona localmente. */
    fun aplicarDesdeUrl(context: Context, url: String): ConteoSync =
        SyncMerge.aplicarDesdeUrl(context, url)
}

/**
 * Conteo de cambios aplicados en una ronda de sincronización.
 * Se usa para mostrar mensajes amigables ("3 trabajadores nuevos", etc.).
 */
data class ConteoSync(
    var trabajadores: Int = 0,
    var asistencias: Int = 0,
    var renombres: Int = 0,
    var ocultos: Int = 0,
    var huellas: Int = 0,
    var borrados: Int = 0,
    var fotos: Int = 0
) {
    operator fun plus(o: ConteoSync) = ConteoSync(
        trabajadores + o.trabajadores,
        asistencias + o.asistencias,
        renombres + o.renombres,
        ocultos + o.ocultos,
        huellas + o.huellas,
        borrados + o.borrados,
        fotos + o.fotos
    )

    operator fun plusAssign(o: ConteoSync) {
        trabajadores += o.trabajadores
        asistencias += o.asistencias
        renombres += o.renombres
        ocultos += o.ocultos
        huellas += o.huellas
        borrados += o.borrados
        fotos += o.fotos
    }

    /** Texto breve y amigable con lo que cambió (vacío si no cambió nada). */
    fun resumenFriendly(): String {
        val partes = mutableListOf<String>()
        val eliminados = borrados + ocultos
        if (trabajadores > 0) partes += "$trabajadores trabajador(es)"
        if (asistencias > 0) partes += "$asistencias asistencia(s)"
        if (fotos > 0) partes += "$fotos foto(s)"
        if (eliminados > 0) partes += "$eliminados elimina(do)(s)"
        if (renombres > 0) partes += "$renombres renombrado(s)"
        if (huellas > 0) partes += "$huellas huella(s) actualizada(s)"
        return partes.joinToString(" · ")
    }
}

/** Un dispositivo encontrado: dirección "ip:puerto" y su nombre para mostrar. */
data class Dispositivo(
    val peer: String,
    val nombre: String
)

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

    /** Descarga el JSON de una URL y lo fusiona localmente. Devuelve qué cambió. */
    fun aplicarDesdeUrl(context: Context, url: String): ConteoSync {
        val json = httpGetDe(url)
            ?: throw IllegalStateException("No se pudo descargar los datos del otro dispositivo")
        return aplicarJson(context, AttendanceDb(context), json)
    }

    /**
     * Fusiona el dataset remoto en la base local. Reglas:
     *  - last-write-wins por updated_at en trabajadores, renombres y huellas;
     *  - asistencias y ocultos: solo se agregan (sin duplicados);
     *  - borrados: un tombstone más reciente elimina al trabajador local.
     * Devuelve el conteo de cambios aplicados.
     */
    fun aplicarJson(context: Context, db: AttendanceDb, json: String): ConteoSync {
        val root = JSONObject(json)
        val conteo = ConteoSync()

        // Tombstones primero: un borrado reciente evita reintroducir al trabajador
        val mapaBorrados = HashMap<String, Long>()
        val arrBorrados = root.optJSONArray("borrados") ?: JSONArray()
        for (i in 0 until arrBorrados.length()) {
            val o = arrBorrados.getJSONObject(i)
            val nombre = o.getString("nombre")
            val ts = o.getLong("ts")
            mapaBorrados[nombre] = ts
            if (db.aplicarBorradoSync(nombre, ts)) {
                conteo.borrados++
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
                conteo.trabajadores++
                val foto = fotosPorNombre[nombre]
                if (foto != null) {
                    try {
                        val bytes = Base64.decode(foto, Base64.NO_WRAP)
                        val archivo = Fotos.archivo(context, nombre)
                        archivo.parentFile?.mkdirs()
                        archivo.writeBytes(bytes)
                        conteo.fotos++
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
                conteo.asistencias++
            }
        }

        // Renombres del PC
        val arrRenombres = root.optJSONArray("renombres") ?: JSONArray()
        for (i in 0 until arrRenombres.length()) {
            val o = arrRenombres.getJSONObject(i)
            if (db.upsertRenombreSync(o.getString("or"), o.getString("nu"), o.getLong("ts"))) {
                conteo.renombres++
            }
        }

        // Eliminados del PC (union)
        val arrOcultos = root.optJSONArray("ocultos") ?: JSONArray()
        for (i in 0 until arrOcultos.length()) {
            if (db.agregarOcultoSync(arrOcultos.getString(i))) conteo.ocultos++
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
                conteo.huellas++
            }
        }

        return conteo
    }

    // ------------------------------------------------------------------
    //  Codificación del resumen (para que el otro dispositivo reporte qué aplicó)
    // ------------------------------------------------------------------

    /** Codifica el conteo en una cadena corta "t,a,r,o,h,b,f". */
    fun codificarResumen(c: ConteoSync): String =
        "${c.trabajadores},${c.asistencias},${c.renombres},${c.ocultos}," +
            "${c.huellas},${c.borrados},${c.fotos}"

    /** Decodifica el "resumen" de la respuesta JSON de /sync/orden. */
    fun decodificarResumen(json: String): ConteoSync {
        val c = ConteoSync()
        try {
            val s = JSONObject(json).optString("resumen", "")
            val p = s.split(",")
            if (p.size == 7) {
                c.trabajadores = p[0].toIntOrNull() ?: 0
                c.asistencias = p[1].toIntOrNull() ?: 0
                c.renombres = p[2].toIntOrNull() ?: 0
                c.ocultos = p[3].toIntOrNull() ?: 0
                c.huellas = p[4].toIntOrNull() ?: 0
                c.borrados = p[5].toIntOrNull() ?: 0
                c.fotos = p[6].toIntOrNull() ?: 0
            }
        } catch (_: Exception) {
        }
        return c
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
