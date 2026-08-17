package com.juan.asistenciaapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.data.AttendanceDb
import com.juan.asistenciaapp.data.Registro
import com.juan.asistenciaapp.databinding.FragmentHistorialBinding
import com.juan.asistenciaapp.databinding.ItemRegistroBinding
import com.juan.asistenciaapp.export.Exporter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pestaña "Historial": registros de asistencia de hoy, igual que el panel del
 * PC, con buscador por nombre y botón para exportar el Excel del día.
 */
class HistorialFragment : Fragment() {

    private var _binding: FragmentHistorialBinding? = null
    private val binding get() = _binding!!

    private var todos = emptyList<Registro>()
    private var filtrados = emptyList<Registro>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistorialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvFechaHist.text = LocalDate.now()
            .format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy"))
            .replaceFirstChar { it.titlecase() }

        binding.btnExportar.setOnClickListener {
            try {
                Exporter.generarYCompartir(requireContext())
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error al exportar: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.etBuscar.doAfterTextChanged {
            aplicarFiltro()
        }

        cargar()
    }

    override fun onResume() {
        super.onResume()
        // Al volver de otra pantalla se refresca (nuevos registros del día)
        cargar()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun cargar() {
        val b = _binding ?: return
        todos = AttendanceDb(requireContext()).registradosDe(fechaHoy())
        b.tvTotalHist.text = getString(R.string.total_registros, todos.size)
        aplicarFiltro()
    }

    /** Aplica el texto del buscador a la lista completa. */
    private fun aplicarFiltro() {
        val b = _binding ?: return
        val q = b.etBuscar.text?.toString()?.trim()?.lowercase().orEmpty()
        filtrados = if (q.isEmpty()) {
            todos
        } else {
            todos.filter { it.nombre.lowercase().contains(q) }
        }

        b.tvVacio.visibility = if (filtrados.isEmpty()) View.VISIBLE else View.GONE
        b.tvVacio.text = if (todos.isEmpty()) {
            getString(R.string.sin_registros)
        } else {
            getString(R.string.sin_resultados)
        }

        if (b.recycler.adapter == null) {
            b.recycler.layoutManager = LinearLayoutManager(requireContext())
            b.recycler.adapter = Adapter(filtrados) { reg ->
                // Al tocar un registro se abre el detalle de esa persona
                // (foto + TODO su historial de asistencias)
                DetalleTrabajadorActivity.abrir(requireContext(), reg.nombre)
            }
            // Aparece la lista suavemente (una sola vez al cargar)
            b.recycler.alpha = 0f
            b.recycler.animate().alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            (b.recycler.adapter as Adapter).actualizar(filtrados)
        }
    }

    private fun fechaHoy(): String = LocalDate.now().toString()

    class Adapter(
        private var items: List<Registro>,
        private val alTocar: (Registro) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        fun actualizar(nuevos: List<Registro>) {
            items = nuevos
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemRegistroBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val binding: ItemRegistroBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(registro: Registro) {
                binding.tvHora.text = registro.hora
                binding.tvNombre.text = registro.nombre
                binding.root.setOnClickListener { alTocar(registro) }
            }
        }
    }
}
