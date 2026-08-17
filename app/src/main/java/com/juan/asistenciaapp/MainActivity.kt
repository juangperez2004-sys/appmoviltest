package com.juan.asistenciaapp

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.juan.asistenciaapp.databinding.ActivityMainBinding
import com.juan.asistenciaapp.face.Modelos
import com.juan.asistenciaapp.sync.SyncServidor
import com.juan.asistenciaapp.ui.AsistenciaFragment
import com.juan.asistenciaapp.ui.HistorialFragment
import com.juan.asistenciaapp.ui.TrabajadoresFragment

/**
 * Pantalla contenedora: la barra de navegación inferior alterna entre las tres
 * secciones de la app (Asistencia, Trabajadores, Historial), cada una un
 * fragment. Los modelos de reconocimiento se cargan una sola vez (Modelos) y
 * se comparten entre pestañas.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Diagnóstico: si algo falla en el arranque, se guarda en
        // Descargas/AsistenciaDiag/diag.txt para poder inspeccionarlo sin PC.
        Diag.iniciar(applicationContext)
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                Diag.error("JavaCrash", e)
            } catch (_: Exception) {
            }
            previo?.uncaughtException(t, e)
                ?: run {
                    e.printStackTrace()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.barraNavegacion.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_asistencia -> mostrarFragmento(AsistenciaFragment())
                R.id.tab_trabajadores -> mostrarFragmento(TrabajadoresFragment())
                R.id.tab_historial -> mostrarFragmento(HistorialFragment())
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        binding.barraNavegacion.selectedItemId = R.id.tab_asistencia

        // Servidor local para que otros dispositivos nos encuentren al sincronizar
        SyncServidor.iniciar(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        SyncServidor.detener()
        Modelos.cerrar()
    }

    private fun mostrarFragmento(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.contenedor, fragment)
            .commit()
    }

    /** Cambia de pestaña (p. ej. "Ver registrados" desde Asistencia). */
    fun seleccionarPestana(id: Int) {
        if (binding.barraNavegacion.selectedItemId != id) {
            binding.barraNavegacion.selectedItemId = id
        }
    }
}
