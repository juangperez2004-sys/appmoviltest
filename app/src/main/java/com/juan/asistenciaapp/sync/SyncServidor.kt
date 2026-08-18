package com.juan.asistenciaapp.sync

import android.content.Context
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

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
        } catch (e: Exception) {
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

    /** Dirección IPv4 local de la red WiFi (prefiere wlan/eth, evita datos móviles). */
    fun ipLocal(): String? {
        var respaldo: String? = null
        try {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val nombre = ni.name?.lowercase() ?: continue
                // Las interfaces de datos móviles no sirven para la red local
                if (nombre.contains("rmnet") || nombre.contains("radio") ||
                    nombre.contains("ppp") || nombre.contains("tun")
                ) {
                    continue
                }
                for (addr in ni.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                        ip.startsWith("172.")
                    ) {
                        if (nombre.contains("wlan") || nombre.contains("eth") ||
                            nombre.contains("wifi")
                        ) {
                            return ip
                        }
                        if (respaldo == null) respaldo = ip
                    }
                }
            }
        } catch (_: Exception) {
        }
        return respaldo
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
}
