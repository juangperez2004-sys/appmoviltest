package com.juan.asistenciaapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.databinding.ActivitySincronizarBinding
import com.juan.asistenciaapp.sync.SyncEngine
import com.juan.asistenciaapp.sync.SyncServidor
import java.util.concurrent.Executors

/**
 * Pantalla de sincronización WiFi: muestra el QR de este dispositivo para
 * vincular otros, permite escanear el QR de otro dispositivo y dispara la
 * sincronización con todos los dispositivos de la red a la vez.
 */
class SincronizarActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySincronizarBinding
    private var ejecutando = false

    private val escanearQr = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.getStringExtra("qr")
            val peer = uri?.let { SyncEngine.peerDeQr(it) }
            if (peer != null) {
                SyncServidor.agregarPeer(this, peer)
                binding.tvEstado.text = getString(R.string.dispositivo_agregado, peer)
                mostrarDispositivos()
            } else {
                binding.tvEstado.text = getString(R.string.qr_no_valido)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySincronizarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.sincronizar_titulo)

        // El servidor local debe estar corriendo para que los demás nos encuentren
        SyncServidor.iniciar(applicationContext)

        val ip = SyncServidor.ipLocal()
        binding.tvMiDireccion.text = if (ip != null) {
            getString(R.string.mi_direccion, "$ip:${SyncServidor.PUERTO_HTTP}")
        } else {
            getString(R.string.sin_red)
        }

        binding.imgQr.setImageBitmap(SyncEngine.generarQr(SyncEngine.miUri(this), 512))

        binding.btnEscanear.setOnClickListener {
            EscanearQRActivity.abrir(this, escanearQr)
        }
        binding.btnSincronizar.setOnClickListener { sincronizarAhora() }

        mostrarDispositivos()
    }

    override fun onResume() {
        super.onResume()
        mostrarDispositivos()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        return true
    }

    private fun sincronizarAhora() {
        if (ejecutando) return
        ejecutando = true
        binding.btnSincronizar.isEnabled = false
        binding.tvEstado.text = getString(R.string.buscando_dispositivos)

        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val resumen = SyncEngine.sincronizarTodo(this) { msg ->
                runOnUiThread { binding.tvEstado.text = msg }
            }
            runOnUiThread {
                binding.tvEstado.text = resumen
                ejecutando = false
                binding.btnSincronizar.isEnabled = true
            }
            executor.shutdown()
        }
    }

    private fun mostrarDispositivos() {
        val peers = SyncServidor.peersConocidos(this)
        binding.tvDispositivos.text = if (peers.isEmpty()) {
            getString(R.string.sin_dispositivos)
        } else {
            getString(R.string.dispositivos_conocidos) + "\n" + peers.joinToString("\n")
        }
    }

    companion object {
        fun abrir(context: Context) {
            context.startActivity(
                Intent(context, SincronizarActivity::class.java)
            )
        }
    }
}
