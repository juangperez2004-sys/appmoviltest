package com.juan.asistenciaapp.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.juan.asistenciaapp.Diag
import com.juan.asistenciaapp.data.AttendanceDb
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.HashMap

/**
 * Servidor HTTP local + respuesta UDP de descubrimiento.
 *
 * Cada dispositivo corre un servidor NanoHTTPD en [PUERTO_HTTP] mientras la
 * app está abierta. Cuando uno presiona "Sincronizar", descubre a los demás
 * (broadcast UDP + dispositivos vinculados por QR) y todos intercambian datos
 * por HTTP local (mismo WiFi, sin internet).
 */
object SyncServidor {

    private const val TAG = "Sync"
    const val PUERTO_HTTP = 8555
    const val PUERTO_UDP = 8554
    const val PING = "ASISTENCIA_SYNC_PING"
    const val PONG = "ASISTENCIA_SYNC_PONG"

    private const val PREFS = "sync"
    private const val CLAVE_PEERS = "peers"
    private const val CLAVE_NOMBRE = "nombre_dispositivo"

    @Volatile
    private var http: NanoHTTPD? = null

    @Volatile
    private var udp: Thread? = null

    @Volatile
    private var socketUdp: DatagramSocket? = null

    @Volatile
    private var appContext: Context? = null

    /** Nombre con el que este dispositivo se muestra a los demás (configurable). */
    fun nombreDispositivo(context: Context): String {
        val guardado = context.getSharedPreferences(PREFS, 0)
            .getString(CLAVE_NOMBRE, null)
        if (!guardado.isNullOrBlank()) return guardado
        return Build.MODEL.ifBlank { "Celular" }
    }

    /** Guarda el nombre personalizado de este dispositivo. */
    fun guardarNombreDispositivo(context: Context, nombre: String) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(CLAVE_NOMBRE, nombre.trim().ifEmpty { null })
            .apply()
    }

    /** Inicia el servidor HTTP y la respuesta UDP (idempotente). */
    @Synchronized
    fun iniciar(context: Context) {
        if (http != null) return
        val app = context.applicationContext
        appContext = app

        val s = object : NanoHTTPD(PUERTO_HTTP) {
            override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                val uri = session.uri ?: ""
                return when {
                    // Recibe los datos de otro dispositivo (PUSH por POST).
                    // El que sincroniza le manda su dataset fusionado directamente:
                    // así solo hace falta la conexión ENTRANTE, que es la que
                    // funciona en todas las redes.
                    uri == "/sync/datos" && session.method == NanoHTTPD.Method.POST -> {
                        try {
                            val archivos = HashMap<String, String>()
                            session.parseBody(archivos)
                            val ruta = archivos["postData"]
                            val cuerpo = if (ruta != null) File(ruta).readText() else ""
                            if (cuerpo.isBlank()) {
                                newFixedLengthResponse(
                                    NanoHTTPD.Response.Status.BAD_REQUEST,
                                    NanoHTTPD.MIME_PLAINTEXT,
                                    "cuerpo vacío"
                                )
                            } else {
                                val conteo = SyncMerge.aplicarJson(app, AttendanceDb(app), cuerpo)
                                newFixedLengthResponse(
                                    NanoHTTPD.Response.Status.OK,
                                    "application/json; charset=utf-8",
                                    "{\"ok\":true,\"resumen\":\"${SyncMerge.codificarResumen(conteo)}\"}"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "error recibiendo datos", e)
                            newFixedLengthResponse(
                                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                                "application/json; charset=utf-8",
                                "{\"ok\":false,\"error\":\"${e.message}\"}"
                            )
                        }
                    }

                    uri == "/sync/datos" -> {
                        try {
                            val json = SyncEngine.datasetJson(app)
                            newFixedLengthResponse(
                                NanoHTTPD.Response.Status.OK,
                                "application/json; charset=utf-8",
                                json
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error generando dataset", e)
                            newFixedLengthResponse(
                                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                                "application/json; charset=utf-8",
                                "{\"ok\":false,\"error\":\"${e.message}\"}"
                            )
                        }
                    }

                    uri == "/sync/orden" -> {
                        val url = session.parms["url"]
                        if (url.isNullOrEmpty()) {
                            newFixedLengthResponse(
                                NanoHTTPD.Response.Status.BAD_REQUEST,
                                NanoHTTPD.MIME_PLAINTEXT,
                                "url requerido"
                            )
                        } else {
                            try {
                                val conteo = SyncEngine.aplicarDesdeUrl(app, url)
                                newFixedLengthResponse(
                                    NanoHTTPD.Response.Status.OK,
                                    "application/json; charset=utf-8",
                                    "{\"ok\":true,\"resumen\":\"${SyncMerge.codificarResumen(conteo)}\"}"
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "orden falló", e)
                                newFixedLengthResponse(
                                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                                    "application/json; charset=utf-8",
                                    "{\"ok\":false,\"error\":\"${e.message}\"}"
                                )
                            }
                        }
                    }

                    else -> newFixedLengthResponse(
                        NanoHTTPD.Response.Status.NOT_FOUND,
                        NanoHTTPD.MIME_PLAINTEXT,
                        "no encontrado"
                    )
                }
            }
        }

        try {
            s.start(5000, false)
            http = s
            iniciarRespuestaUdp()
            Diag.marcar("sync: servidor HTTP en :$PUERTO_HTTP ok, ip=${ipLocal()}")
        } catch (e: Exception) {
            Diag.marcar("sync: NO se pudo iniciar el servidor HTTP: ${e.message}")
            Log.e(TAG, "No se pudo iniciar el servidor HTTP", e)
            http = null
        }
    }

    /** Detiene el servidor y el oyente UDP (al cerrar la app). */
    @Synchronized
    fun detener() {
        try {
            http?.stop()
        } catch (_: Exception) {
        }
        http = null
        try {
            socketUdp?.close()
        } catch (_: Exception) {
        }
        socketUdp = null
        udp?.interrupt()
        udp = null
    }

    /** Escucha pings de descubrimiento y responde con la dirección del dispositivo. */
    private fun iniciarRespuestaUdp() {
        if (udp != null) return
        udp = Thread {
            try {
                val socket = DatagramSocket(PUERTO_UDP)
                socket.broadcast = true
                socketUdp = socket
                val buf = ByteArray(512)
                while (!Thread.currentThread().isInterrupted) {
                    val paquete = DatagramPacket(buf, buf.size)
                    socket.receive(paquete)
                    val msg = String(paquete.data, 0, paquete.length, Charsets.UTF_8)
                    if (msg == PING) {
                        val nombre = appContext?.let { nombreDispositivo(it) } ?: Build.MODEL
                        val respuesta = "$PONG $nombre".toByteArray(Charsets.UTF_8)
                        socket.send(
                            DatagramPacket(
                                respuesta, respuesta.size,
                                paquete.address, paquete.port
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // socket cerrado o error normal al detener
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Dirección IPv4 local de la red (prefiere la privada del WiFi/LAN).
     *  Usa [ipsLocales] (sin filtros de nombre de interfaz): en algunos
     *  Xiaomi la interfaz WiFi no se llama "wlan0" y el filtro anterior
     *  devolvía null aunque el WiFi estuviera conectado. */
    fun ipLocal(): String? {
        val todas = ipsLocales()
        // Preferir direcciones privadas de red local (no localhost ni VPN)
        for (prefijo in listOf("192.168.", "10.", "172.")) {
            todas.firstOrNull { it.startsWith(prefijo) }?.let { return it }
        }
        return null
    }

    /** TODAS las direcciones IPv4 locales de este dispositivo (para filtrar
     *  "yo mismo" en el descubrimiento, aunque la IP haya cambiado o haya
     *  varias interfaces activas a la vez). */
    fun ipsLocales(): Set<String> {
        val set = mutableSetOf<String>()
        try {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address) {
                        addr.hostAddress?.let { set += it }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return set
    }

    /**
     * Enlaza TODOS los sockets del proceso a la red WiFi. Sin esto, si hay
     * datos móviles activos (o el "switch inteligente" del Xiaomi), el TCP
     * hacia otro dispositivo de la LAN sale por los datos y falla con
     * ConnectException, mientras que el UDP del descubrimiento sí se queda en
     * el WiFi (por eso "encuentra" dispositivos pero no conecta con ellos).
     */
    fun enlazarProcesoAWifi(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(n)
                    return
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Vuelve a usar la red por defecto (se llama al terminar de sincronizar). */
    fun desenlazarProcesoDeRed(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }

    /** Dispositivos vinculados por QR, guardados como "nombre|ip:puerto". */
    fun peersConocidos(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, 0)
            .getStringSet(CLAVE_PEERS, emptySet())
            ?.toSet() ?: emptySet()

    /** Vincula un dispositivo escaneado por QR (guarda su nombre y dirección). */
    fun agregarPeer(context: Context, d: Dispositivo) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val set = (prefs.getStringSet(CLAVE_PEERS, emptySet()) ?: emptySet()).toMutableSet()
        set += "${d.nombre}|${d.peer}"
        prefs.edit().putStringSet(CLAVE_PEERS, set).apply()
    }

    /** Borra todos los dispositivos vinculados por QR (sus IPs caducan al cambiar de red). */
    fun limpiarPeers(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit()
            .remove(CLAVE_PEERS)
            .apply()
    }
}
