package com.juan.asistenciaapp.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.data.AttendanceDb
import com.juan.asistenciaapp.databinding.ActivityEditarTrabajadorBinding
import com.juan.asistenciaapp.face.FaceUtil
import com.juan.asistenciaapp.face.Fotos
import com.juan.asistenciaapp.face.Gallery
import com.juan.asistenciaapp.face.Modelos
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Actualiza un trabajador: cambia su nombre (corregir una letra, etc.) y/o su
 * foto (cámara o galería). Vale para los registrados desde la app y también
 * para los del PC: al renombrarlos solo cambia la etiqueta, el reconocimiento
 * facial sigue funcionando (el embedding se guarda por índice, no por nombre).
 *
 * Al actualizar la foto también se RECALCULA la huella facial (embedding) con
 * el mismo pipeline del alta: si no se hiciera, el reconocimiento seguiría
 * comparando contra la cara anterior (el bug de "ya no lo reconoce"). Se
 * recomiendan 2-3 fotos nuevas y sus huellas se promedian para más robustez.
 */
class EditarTrabajadorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditarTrabajadorBinding
    private var nombreOriginal = ""
    private var esDeApp = false
    private lateinit var fotoPicker: FotoPicker

    // Capturas de la foto nueva: EMBEDDING de cada toma (se promedian al guardar).
    private val capturas = mutableListOf<FloatArray>()
    // Última foto capturada (recorte del rostro): se guarda como foto del trabajador.
    private var fotoLista: Bitmap? = null

    // Modelos compartidos (Modelos.cargar es idempotente: si MainActivity ya
    // los cargó, el callback llega al instante). No se cierran aquí.
    @Volatile
    private var modelosListos = false
    // Fotos elegidas antes de que los modelos terminaran de cargar.
    private val fotosPendientes = mutableListOf<File>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditarTrabajadorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.actualizar_trabajador)

        nombreOriginal = intent.getStringExtra(EXTRA_NOMBRE).orEmpty()
        esDeApp = intent.getBooleanExtra(EXTRA_ES_DE_APP, false)
        if (nombreOriginal.isEmpty()) {
            finish()
            return
        }

        binding.etNombre.setText(nombreOriginal)
        // El renombrado es válido para todos; el aviso explica que el cambio
        // también se aplica al reconocimiento y al historial.
        binding.tvNota.visibility = View.VISIBLE
        binding.tvFotos.text = getString(R.string.ayuda_foto)

        mostrarFoto()

        // Los modelos se cargan UNA vez (Modelos) y se comparten entre
        // pantallas. Al quedar listos se procesan las fotos que llegaron
        // mientras cargaban.
        Modelos.cargar(this) { ok ->
            if (ok) {
                modelosListos = true
                executor.execute {
                    val pendientes = synchronized(fotosPendientes) {
                        fotosPendientes.toList().also { fotosPendientes.clear() }
                    }
                    for (archivo in pendientes) {
                        procesarFoto(archivo)
                    }
                }
            }
        }

        fotoPicker = FotoPicker(this) { archivo -> procesarFoto(archivo) }
        binding.btnCambiarFoto.setOnClickListener { fotoPicker.mostrar() }
        binding.btnGuardar.setOnClickListener { guardar() }
    }

    override fun onDestroy() {
        // Esperar a que termine el procesamiento en curso (los modelos son
        // compartidos y no se cierran aquí).
        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        super.onDestroy()
    }

    private fun mostrarFoto() {
        val foto: Bitmap? = Fotos.cargar(this, nombreOriginal, 240)
        if (foto != null) {
            binding.imgFoto.setImageBitmap(foto)
            binding.imgFoto.visibility = View.VISIBLE
            binding.tvIniciales.visibility = View.GONE
        } else {
            binding.tvIniciales.text = Avatar.iniciales(nombreOriginal)
            binding.tvIniciales.backgroundTintList =
                ColorStateList.valueOf(Avatar.color(nombreOriginal))
            binding.tvIniciales.visibility = View.VISIBLE
            binding.imgFoto.visibility = View.GONE
        }
    }

    /**
     * Procesa la foto elegida/tomada (en segundo plano): detecta el rostro,
     * calcula su huella y actualiza la vista previa con el contador de fotos.
     * Si el rostro no se detecta, la foto se descarta (como en el alta).
     */
    private fun procesarFoto(archivo: File) {
        executor.execute {
            try {
                val bitmap = Fotos.decodificarEnderezada(archivo.absolutePath, 1600)
                val det = Modelos.detector
                val reco = Modelos.recognizer
                if (!modelosListos || det == null || reco == null) {
                    // Modelo aún cargando: conservar la foto para procesarla luego
                    synchronized(fotosPendientes) { fotosPendientes += archivo }
                    runOnUiThread {
                        binding.tvFotos.text = getString(R.string.cargando_modelo)
                        binding.tvFotos.setTextColor(
                            ContextCompat.getColor(this@EditarTrabajadorActivity, R.color.gris_texto)
                        )
                    }
                    bitmap.recycle()
                    return@execute
                }

                // Huella + recorte del rostro (mismo pipeline que el alta)
                val resultado = FaceUtil.procesarFotoTrabajador(det, reco, bitmap)
                bitmap.recycle()
                if (resultado == null) {
                    archivo.delete()
                    runOnUiThread {
                        binding.tvFotos.text = getString(R.string.foto_error)
                        binding.tvFotos.setTextColor(
                            ContextCompat.getColor(this@EditarTrabajadorActivity, R.color.rojo)
                        )
                    }
                    return@execute
                }

                val recorte = resultado.recorte
                val huella = resultado.huella
                runOnUiThread {
                    val vieja = fotoLista
                    fotoLista = recorte
                    capturas += huella
                    binding.imgFoto.setImageBitmap(recorte)
                    binding.imgFoto.visibility = View.VISIBLE
                    binding.tvIniciales.visibility = View.GONE
                    vieja?.recycle()
                    binding.tvFotos.text = if (capturas.size >= FOTOS_RECOMENDADAS) {
                        getString(R.string.foto_ok)
                    } else {
                        getString(
                            R.string.foto_captura_n, capturas.size, FOTOS_RECOMENDADAS
                        )
                    }
                    binding.tvFotos.setTextColor(
                        ContextCompat.getColor(this@EditarTrabajadorActivity, R.color.verde)
                    )
                    binding.btnCambiarFoto.isEnabled = capturas.size < FOTOS_RECOMENDADAS
                    binding.btnCambiarFoto.text = getString(
                        if (capturas.isEmpty()) R.string.cambiar_foto else R.string.agregar_foto
                    )
                }
                archivo.delete()
            } catch (e: Exception) {
                // El procesamiento nunca debe romper la pantalla
                Log.e("Editar", "Error procesando foto", e)
                archivo.delete()
            }
        }
    }

    private fun guardar() {
        val nuevo = binding.etNombre.text?.toString()?.trim().orEmpty()
        if (nuevo.isEmpty()) {
            Toast.makeText(this, R.string.nombre_vacio, Toast.LENGTH_SHORT).show()
            return
        }

        val db = AttendanceDb(this)
        val renombra = nuevo != nombreOriginal
        if (renombra && nombreEnUso(db, nuevo)) {
            Toast.makeText(this, R.string.ya_existe, Toast.LENGTH_LONG).show()
            return
        }

        // 1) Foto nueva (se guarda con el nombre destino)
        if (capturas.isNotEmpty()) {
            val foto = fotoLista
            if (foto == null) {
                Toast.makeText(this, R.string.error_guardar_foto, Toast.LENGTH_LONG).show()
                return
            }
            if (Fotos.guardar(this, nuevo, foto) == null) {
                Toast.makeText(this, R.string.error_guardar_foto, Toast.LENGTH_LONG).show()
                return
            }
            // Si además se renombró, la foto vieja queda huérfana: se borra
            if (renombra) {
                Fotos.eliminar(this, nombreOriginal)
            }
        } else if (renombra) {
            // 2) Solo renombra: mover la foto al nuevo nombre (si la hay).
            //    Los del PC sin foto propia (solo en assets) la copian.
            Fotos.renombrar(this, nombreOriginal, nuevo)
            if (!Fotos.existe(this, nuevo)) {
                Fotos.copiarDeAsset(this, nombreOriginal, nuevo)
            }
        }

        // 3) Nombre nuevo en la base de datos (trabajador + historial).
        //    Se renombra ANTES de guardar la huella para que la fila ya exista
        //    con el nombre destino.
        if (renombra) {
            if (esDeApp) {
                db.renombrarTrabajador(nombreOriginal, nuevo)
            } else {
                db.renombrarPc(nombreOriginal, nuevo)
            }
        }

        // 4) Huella facial RECALCULADA con las fotos nuevas: los de la app
        //    reemplazan su embedding; los del PC guardan una sobrescritura con
        //    prioridad sobre la del APK. Sin esto, el reconocimiento seguiría
        //    comparando contra la cara anterior.
        if (capturas.isNotEmpty()) {
            val huella = FaceUtil.promediar(capturas)
            db.actualizarHuella(nuevo, huella, esDeApp)
        }

        Toast.makeText(this, R.string.trabajador_actualizado, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_NOMBRE, nuevo))
        finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /**
     * true si el nombre ya lo usa otro trabajador (de la app o del PC).
     * Se excluye el nombre actual del trabajador que se está editando.
     */
    private fun nombreEnUso(db: AttendanceDb, nuevo: String): Boolean {
        val ocupados = mutableSetOf<String>()
        ocupados += db.trabajadores().map { it.nombre }
        ocupados += Gallery(this, renombres = db.renombres()).nombres
        ocupados.remove(nombreOriginal)
        return nuevo in ocupados
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        return true
    }

    companion object {
        const val EXTRA_NOMBRE = "nombre"
        const val EXTRA_ES_DE_APP = "esDeApp"

        /** Fotos recomendadas para recalcular una huella robusta (como el alta). */
        private const val FOTOS_RECOMENDADAS = 3

        fun abrir(context: Context, nombre: String, esDeApp: Boolean) {
            context.startActivity(
                Intent(context, EditarTrabajadorActivity::class.java)
                    .putExtra(EXTRA_NOMBRE, nombre)
                    .putExtra(EXTRA_ES_DE_APP, esDeApp)
            )
            (context as? android.app.Activity)
                ?.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }
}
