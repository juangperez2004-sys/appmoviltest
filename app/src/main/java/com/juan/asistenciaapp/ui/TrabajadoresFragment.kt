package com.juan.asistenciaapp.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.widget.doAfterTextChanged
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.data.AttendanceDb
import com.juan.asistenciaapp.databinding.FragmentTrabajadoresBinding
import com.juan.asistenciaapp.databinding.ItemTrabajadorBinding
import com.juan.asistenciaapp.face.Fotos
import com.juan.asistenciaapp.face.Gallery

/**
 * Pestaña "Trabajadores": lista de todos los trabajadores (galería del PC +
 * registrados desde la app), con buscador, selección múltiple para borrar en
 * lote y botón flotante para registrar a uno nuevo.
 */
class TrabajadoresFragment : Fragment() {

    private var _binding: FragmentTrabajadoresBinding? = null
    private val binding get() = _binding!!

    private val todos = mutableListOf<Item>()
    private var filtrados = listOf<Item>()

    // Modo de selección múltiple (pulsación larga)
    private var modoSeleccion = false
    private val seleccionados = mutableSetOf<String>()

    private lateinit var callbackAtras: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrabajadoresBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // El menú de selección múltiple vive en el toolbar de la pestaña
        binding.toolbar.inflateMenu(R.menu.menu_trabajadores)
        binding.toolbar.setOnMenuItemClickListener { item -> manejarMenu(item) }
        // Los items de selección solo aparecen dentro del modo (ocultos al inicio)
        actualizarMenu()

        // Atrás con selección activa: sale del modo en vez de cerrar la app
        callbackAtras = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                salirModoSeleccion()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callbackAtras)

        binding.etBuscar.doAfterTextChanged { texto ->
            val q = texto?.toString()?.trim()?.lowercase().orEmpty()
            filtrados = if (q.isEmpty()) {
                todos.toList()
            } else {
                todos.filter { it.nombre.lowercase().contains(q) }
            }
            (binding.recycler.adapter as Adapter).actualizar(filtrados)
            actualizarVacio()
        }

        binding.fabRegistrar.setOnClickListener {
            RegistrarTrabajadorActivity.abrir(requireContext())
        }

        cargar()
    }

    override fun onResume() {
        super.onResume()
        // Al volver de detalle/registrar la lista se refresca
        cargar()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** (Re)construye la lista: galería del PC + app, sin los eliminados. */
    private fun cargar() {
        val b = _binding ?: return
        todos.clear()
        val db = AttendanceDb(requireContext())
        val enApp = db.trabajadores().associateBy { it.nombre }
        val eliminados = db.trabajadoresPcEliminados()
        // La galería aplica los renombres de los trabajadores del PC
        val nombres = (Gallery(requireContext(), renombres = db.renombres()).nombres + enApp.keys)
            .distinct()
            .filter { it !in eliminados }
            .sortedBy { it.lowercase() }
        for (nombre in nombres) {
            todos += Item(nombre, esNuevo = enApp.containsKey(nombre))
        }

        b.tvTotal.text = getString(R.string.total_trabajadores, todos.size)

        filtrados = todos.toList()
        if (b.recycler.adapter == null) {
            b.recycler.layoutManager = LinearLayoutManager(requireContext())
            b.recycler.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
            b.recycler.adapter = Adapter(
                filtrados,
                alTocar = { item ->
                    if (modoSeleccion) {
                        alternarSeleccion(item)
                    } else {
                        DetalleTrabajadorActivity.abrir(requireContext(), item.nombre)
                    }
                },
                alMantener = { item -> entrarModoSeleccion(item) }
            )
            // Aparece la lista suavemente solo la primera vez que se construye
            b.recycler.alpha = 0f
            b.recycler.animate().alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            (b.recycler.adapter as Adapter).actualizar(filtrados)
        }
        actualizarVacio()
    }

    // ------------------------------------------------------------------
    //  Selección múltiple
    // ------------------------------------------------------------------

    /** Activa el modo selección (pulsación larga) y marca el trabajador tocado. */
    private fun entrarModoSeleccion(item: Item) {
        if (modoSeleccion) return
        modoSeleccion = true
        seleccionados.clear()
        seleccionados += item.nombre
        callbackAtras.isEnabled = true
        animarBuscador(false)
        actualizarModoSeleccion()
        // El botón "Seleccionar todo" aparece con un fade suave
        binding.toolbar.post {
            val btn = binding.toolbar.findViewById<View>(R.id.action_seleccionar_todo)
                ?: return@post
            btn.alpha = 0f
            btn.animate().alpha(1f)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Marca/desmarca un trabajador; si queda vacío, sale del modo. */
    private fun alternarSeleccion(item: Item) {
        if (!seleccionados.add(item.nombre)) {
            seleccionados.remove(item.nombre)
        }
        if (seleccionados.isEmpty()) {
            salirModoSeleccion()
        } else {
            actualizarModoSeleccion()
        }
    }

    private fun salirModoSeleccion() {
        modoSeleccion = false
        seleccionados.clear()
        callbackAtras.isEnabled = false
        actualizarModoSeleccion()
        animarBuscador(true)
    }

    /** Desvanece el buscador al entrar/salir del modo de selección. */
    private fun animarBuscador(visible: Boolean) {
        binding.etBuscar.animate().cancel()
        binding.etBuscar.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Selecciona todos los trabajadores visibles, o los desmarca si ya
     * estaban todos seleccionados (toggle).
     */
    private fun seleccionarODesmarcarTodo() {
        if (filtrados.isEmpty()) return
        val todosMarcados = filtrados.all { it.nombre in seleccionados }
        if (todosMarcados) {
            seleccionados.clear()
            salirModoSeleccion()
        } else {
            seleccionados += filtrados.map { it.nombre }
            actualizarModoSeleccion()
        }
    }

    /** Refresca la UI según el modo: casillas, título, botón papelera, buscador. */
    private fun actualizarModoSeleccion() {
        (binding.recycler.adapter as? Adapter)
            ?.actualizarSeleccion(modoSeleccion, seleccionados.toSet())

        binding.toolbar.title = if (modoSeleccion) {
            getString(R.string.seleccionados, seleccionados.size)
        } else {
            getString(R.string.ver_trabajadores)
        }
        binding.etBuscar.isEnabled = !modoSeleccion
        actualizarMenu()
    }

    private fun actualizarMenu() {
        val menu = binding.toolbar.menu
        menu.findItem(R.id.action_eliminar)?.isVisible = modoSeleccion
        menu.findItem(R.id.action_seleccionar_todo)?.apply {
            isVisible = modoSeleccion && filtrados.isNotEmpty()
            title = getString(
                if (modoSeleccion && filtrados.all { it.nombre in seleccionados }) {
                    R.string.deseleccionar_todo
                } else {
                    R.string.seleccionar_todo
                }
            )
        }
    }

    private fun manejarMenu(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sincronizar -> {
                SincronizarActivity.abrir(requireContext())
                true
            }
            R.id.action_seleccionar_todo -> {
                seleccionarODesmarcarTodo()
                true
            }
            R.id.action_eliminar -> {
                confirmarEliminarSeleccion()
                true
            }
            else -> false
        }
    }

    private fun confirmarEliminarSeleccion() {
        if (seleccionados.isEmpty()) return
        val n = seleccionados.size
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.eliminar_trabajadores)
            .setMessage(getString(R.string.confirmar_eliminar_lote, n))
            .setPositiveButton(R.string.eliminar) { _, _ -> eliminarSeleccionados() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Borra en lote: todos se eliminan definitivamente (+ historial). Los de
     * la app se borran de la BD; los del PC se excluyen para siempre.
     * Si un nombre existe en AMBAS (registrado en la app y en la galería del
     * PC), se cubren las dos: se quita de la BD y se oculta de la galería,
     * para que no reaparezca al recargar.
     */
    private fun eliminarSeleccionados() {
        val db = AttendanceDb(requireContext())
        val enApp = db.trabajadores().map { it.nombre }.toSet()
        val nombresPc = Gallery(requireContext(), renombres = db.renombres()).nombres.toSet()
        val paraEliminar = mutableListOf<String>()
        val paraPc = mutableListOf<String>()
        for (nombre in seleccionados) {
            Fotos.eliminar(requireContext(), nombre)
            if (nombre in nombresPc) paraPc += nombre
            if (nombre in enApp) paraEliminar += nombre
        }
        db.eliminarTrabajadoresConHistorial(paraEliminar)
        db.eliminarPcConHistorial(paraPc)

        Toast.makeText(
            requireContext(),
            getString(R.string.trabajadores_eliminados, seleccionados.size),
            Toast.LENGTH_SHORT
        ).show()
        salirModoSeleccion()
        cargar()
    }

    private fun actualizarVacio() {
        binding.tvVacio.visibility = if (filtrados.isEmpty()) View.VISIBLE else View.GONE
        binding.tvVacio.text = if (todos.isEmpty()) {
            getString(R.string.sin_trabajadores)
        } else {
            getString(R.string.sin_resultados)
        }
    }

    data class Item(val nombre: String, val esNuevo: Boolean)

    class Adapter(
        private var items: List<Item>,
        private val alTocar: (Item) -> Unit,
        private val alMantener: (Item) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        var modoSeleccion = false
        var seleccionados: Set<String> = emptySet()

        /** Actualiza la lista animando los cambios (agregar/eliminar suave). */
        fun actualizar(nuevos: List<Item>) {
            val diff = DiffUtil.calculateDiff(DiffCallback(items, nuevos))
            items = nuevos
            diff.dispatchUpdatesTo(this)
        }

        private class DiffCallback(
            private val viejos: List<Item>,
            private val nuevos: List<Item>
        ) : DiffUtil.Callback() {
            override fun getOldListSize(): Int = viejos.size
            override fun getNewListSize(): Int = nuevos.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                viejos[oldPos].nombre == nuevos[newPos].nombre
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                viejos[oldPos] == nuevos[newPos]
        }

        fun actualizarSeleccion(modo: Boolean, seleccion: Set<String>) {
            modoSeleccion = modo
            seleccionados = seleccion
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemTrabajadorBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val binding: ItemTrabajadorBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: Item) {
                binding.tvNombre.text = item.nombre
                binding.badgeNuevo.visibility = if (item.esNuevo) View.VISIBLE else View.GONE

                val foto: Bitmap? = Fotos.cargar(binding.root.context, item.nombre)
                if (foto != null) {
                    binding.imgFoto.setImageBitmap(foto)
                    binding.imgFoto.visibility = View.VISIBLE
                    binding.tvIniciales.visibility = View.GONE
                } else {
                    binding.tvIniciales.text = Avatar.iniciales(item.nombre)
                    binding.tvIniciales.backgroundTintList = ColorStateList.valueOf(
                        Avatar.color(item.nombre)
                    )
                    binding.tvIniciales.visibility = View.VISIBLE
                    binding.imgFoto.visibility = View.GONE
                }

                // Casilla y resaltado según el modo de selección.
                // La casilla NO es clickeable por sí sola: su estado lo maneja
                // la selección. Si el toque se lo comiera el checkbox, el item
                // se marcaría visualmente pero no entraría en la selección y no
                // se borraría al eliminar en lote.
                val marcado = item.nombre in seleccionados
                binding.chkSeleccion.visibility =
                    if (modoSeleccion) View.VISIBLE else View.GONE
                binding.chkSeleccion.isChecked = marcado
                binding.chkSeleccion.isClickable = false
                binding.chkSeleccion.isFocusable = false
                binding.root.setBackgroundResource(
                    if (modoSeleccion && marcado) {
                        R.drawable.item_seleccionado_fondo
                    } else {
                        R.drawable.item_registro_fondo
                    }
                )

                binding.root.setOnClickListener { alTocar(item) }
                binding.root.setOnLongClickListener {
                    alMantener(item)
                    true
                }
            }
        }
    }
}
