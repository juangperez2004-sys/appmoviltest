package com.juan.asistenciaapp.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.data.AttendanceDb
import com.juan.asistenciaapp.data.Registro
import com.juan.asistenciaapp.databinding.ActivityDetalleTrabajadorBinding
import com.juan.asistenciaapp.databinding.ItemAsistenciaBinding
import com.juan.asistenciaapp.face.FaceUtil
import com.juan.asistenciaapp.face.Fotos
import com.juan.asistenciaapp.face.Modelos
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Detalle de un trabajador: foto (o avatar de iniciales), origen
 * (galería del PC o registrado en la app con su fecha) y el historial
 * de asistencias. Permite cambiar la foto, actualizar (nombre + foto)
 * y eliminar (borra a los de la app; oculta a los del PC).
 */
class DetalleTrabajadorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetalleTrabajadorBinding
    private lateinit var fotoPicker: FotoPicker
    private var nombre = ""
    private var esDeApp = false
    private var primeraCarga = true

    // Modelos compartidos (idempotente: si MainActivity ya los cargó,
    // el callback llega al instante). No se cierran aquí.
    @Volatile
    private var modelosListos = false
    // Si se cambia la foto antes de que los modelos estén listos, se
    // recalculará la huella cuando estén.
    private var huellaPendiente = false
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val editarResultado = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode == RESULT_OK) {
            resultado.data?.getStringExtra(EditarTrabajadorActivity.EXTRA_NOMBRE)
                ?.takeIf { it.isNotBlank() }
                ?.let { nuevo ->
                    nombre = nuevo
                    recargar()
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetalleTrabajadorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        nombre = intent.getStringExtra(EXTRA_NOMBRE).orEmpty()
        if (nombre.isEmpty()) {
            finish()
            return
        }
        supportActionBar?.title = nombre
        binding.tvNombre.text = nombre
        esDeApp = AttendanceDb(this).trabajadores().associateBy { it.nombre }
            .containsKey(nombre)
        recargar()

        fotoPicker = FotoPicker(this) { archivo -> procesarFotoNueva(archivo) }
        binding.btnCambiarFoto.setOnClickListener { fotoPicker.mostrar() }
        binding.btnActualizar.setOnClickListener {
            editarResultado.launch(
                Intent(this, EditarTrabajadorActivity::class.java)
                    .putExtra(EditarTrabajadorActivity.EXTRA_NOMBRE, nombre)
                    .putExtra(EditarTrabajadorActivity.EXTRA_ES_DE_APP, esDeApp)
            )
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        binding.btnEliminar.setOnClickListener { confirmarEliminar() }

        // Los modelos se cargan UNA vez (Modelos) y se comparten. Al quedar
        // listos se recalculará la huella si la foto cambió mientras cargaban.
        Modelos.cargar(this) { ok ->
            if (ok) {
                modelosListos = true
                if (huellaPendiente) {
                    huellaPendiente = false
                    recalcularHuella()
                }
            }
        }
    }

    /** Recarga foto, origen e historial (tras cambiar nombre o foto). */
    private fun recargar() {
        supportActionBar?.title = nombre
        binding.tvNombre.text = nombre
        mostrarFoto()

        val enApp = AttendanceDb(this).trabajadores().associateBy { it.nombre }
        esDeApp = enApp.containsKey(nombre)
        binding.tvOrigen.text = enApp[nombre]?.let {
            getString(R.string.registrado_en_app, it.fechaRegistro)
        } ?: getString(R.string.galeria_pc)

        val registros = AttendanceDb(this).registrosDeNombre(nombre)
        binding.tvTotal.text = getString(R.string.total_asistencias, registros.size)
        binding.tvVacio.visibility = if (registros.isEmpty()) View.VISIBLE else View.GONE
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = Adapter(registros)
        // Aparece el historial suavemente solo la primera vez que se carga
        if (primeraCarga) {
            primeraCarga = false
            binding.recycler.alpha = 0f
            binding.recycler.animate().alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Muestra la foto del trabajador (o avatar) y el texto del botón según tenga o no foto. */
    private fun mostrarFoto() {
        val foto: Bitmap? = Fotos.cargar(this, nombre, 240)
        if (foto != null) {
            binding.imgFoto.setImageBitmap(foto)
            binding.imgFoto.visibility = View.VISIBLE
            binding.tvIniciales.visibility = View.GONE
            binding.btnCambiarFoto.text = getString(R.string.cambiar_foto)
        } else {
            binding.tvIniciales.text = Avatar.iniciales(nombre)
            binding.tvIniciales.backgroundTintList =
                ColorStateList.valueOf(Avatar.color(nombre))
            binding.tvIniciales.visibility = View.VISIBLE
            binding.imgFoto.visibility = View.GONE
            binding.btnCambiarFoto.text = getString(R.string.agregar_foto)
        }
    }

    private fun procesarFotoNueva(archivo: File) {
        val ok = Fotos.guardarDesdeArchivo(this, nombre, archivo)
        archivo.delete()
        if (!ok) {
            Toast.makeText(this, R.string.error_guardar_foto, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.foto_guardada, Toast.LENGTH_SHORT).show()
        mostrarFoto()
        // Recalcular la huella facial con la foto nueva (mismo bug que en
        // "Actualizar": la foto es solo para mostrar; el reconocimiento
        // compara contra el embedding, que sin este paso queda obsoleto).
        recalcularHuella()
    }

    /**
     * Recalcula la huella del trabajador con la foto recién guardada: detecta
     * el rostro, alinea y calcula el embedding (en segundo plano). Si el modelo
     * aún no está listo, se marca como pendiente y se procesará cuando llegue.
     * La huella se actualiza tanto para trabajadores de la app (fleha del DB)
     * como del PC (sobrescritura con prioridad sobre embeddings.bin).
     */
    private fun recalcularHuella() {
        val det = Modelos.detector
        val reco = Modelos.recognizer
        if (!modelosListos || det == null || reco == null) {
            huellaPendiente = true
            return
        }
        executor.execute {
            try {
                // La foto recién guardada (ya enderezada) se usa para la huella
                val foto = Fotos.cargar(this, nombre, 1600) ?: return@execute
                try {
                    val resultado = FaceUtil.procesarFotoTrabajador(det, reco, foto)
                    if (resultado != null) {
                        resultado.recorte.recycle()
                        AttendanceDb(this).actualizarHuella(
                            nombre, resultado.huella, esDeApp
                        )
                    }
                } finally {
                    foto.recycle()
                }
            } catch (e: Exception) {
                // Nunca romper la app; la foto sí quedó guardada
                Log.e("Detalle", "Error recalculando huella", e)
            }
        }
    }

    private fun confirmarEliminar() {
        val db = AttendanceDb(this)
        val mensaje = if (esDeApp) {
            getString(R.string.confirmar_eliminar_app, nombre)
        } else {
            getString(R.string.confirmar_eliminar_pc, nombre)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.eliminar_trabajador)
            .setMessage(mensaje)
            .setPositiveButton(R.string.eliminar) { _, _ ->
                Fotos.eliminar(this, nombre)
                if (esDeApp) {
                    db.eliminarTrabajador(nombre)
                } else {
                    // Los del PC no se pueden borrar del APK: se excluyen para siempre
                    db.eliminarPcConHistorial(listOf(nombre))
                }
                Toast.makeText(this, R.string.trabajador_eliminado, Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        return true
    }

    class Adapter(private val items: List<Registro>) :
        RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemAsistenciaBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class VH(private val binding: ItemAsistenciaBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(registro: Registro) {
                binding.tvFecha.text = formatearFecha(registro.fecha)
                binding.tvHora.text = registro.hora
            }
        }
    }

    companion object {
        private const val EXTRA_NOMBRE = "nombre"

        fun abrir(context: Context, nombre: String) {
            context.startActivity(
                Intent(context, DetalleTrabajadorActivity::class.java)
                    .putExtra(EXTRA_NOMBRE, nombre)
            )
            (context as? android.app.Activity)
                ?.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        /** yyyy-MM-dd → "Lunes 14 de agosto de 2026" (si no se puede, la fecha tal cual). */
        private fun formatearFecha(iso: String): String = try {
            LocalDate.parse(iso)
                .format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy"))
                .replaceFirstChar { it.titlecase() }
        } catch (_: Exception) {
            iso
        }
    }
}
