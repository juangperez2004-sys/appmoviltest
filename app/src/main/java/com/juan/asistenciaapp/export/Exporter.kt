package com.juan.asistenciaapp.export

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.juan.asistenciaapp.R
import com.juan.asistenciaapp.data.AttendanceDb
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Genera el Excel (.xlsx) del día con la misma información que el CSV del PC
 * (columnas Fecha, Hora, Nombre) y abre el menú de compartir.
 *
 * El .xlsx se escribe a mano (ZIP con las partes XML mínimas) para no cargar
 * una librería pesada como Apache POI en un teléfono de gama baja. El formato
 * es el estándar Open XML: lo abren Excel, LibreOffice y Google Sheets.
 */
object Exporter {

    private const val MIME_XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun generarYCompartir(context: Context) {
        val fecha = LocalDate.now().toString()
        val registros = AttendanceDb(context).registradosDe(fecha)
        if (registros.isEmpty()) {
            Toast.makeText(context, R.string.sin_registros_exportar, Toast.LENGTH_SHORT).show()
            return
        }

        val carpeta = File(context.cacheDir, "exports").apply { mkdirs() }
        val archivo = File(carpeta, "asistencia_$fecha.xlsx")
        generarXlsx(archivo, registros.map { listOf(it.fecha, it.hora, it.nombre) })

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", archivo
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_XLSX
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Compartir asistencia")
        )
    }

    /** Escribe un .xlsx mínimo: encabezado en negrita (Fecha, Hora, Nombre) y los datos como texto. */
    private fun generarXlsx(archivo: File, filas: List<List<String>>) {
        val celdas = StringBuilder()
        // Encabezado con estilo 1 (negrita)
        celdas.append("<row>")
        for (encabezado in listOf("Fecha", "Hora", "Nombre")) {
            celdas.append("<c s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(escapar(encabezado))
                .append("</t></is></c>")
        }
        celdas.append("</row>")
        // Datos (sin estilo: texto plano)
        for (fila in filas) {
            celdas.append("<row>")
            for (v in fila) {
                celdas.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(escapar(v))
                    .append("</t></is></c>")
            }
            celdas.append("</row>")
        }

        val hoja = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$celdas</sheetData></worksheet>
        """.trimIndent()

        val workbook = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Asistencia" sheetId="1" r:id="rId1"/></sheets></workbook>
        """.trimIndent()

        val workbookRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>
        """.trimIndent()

        val rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
        """.trimIndent()

        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>
        """.trimIndent()

        // Estilos: 0 = normal, 1 = negrita (encabezado)
        val styles = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>
        """.trimIndent()

        ZipOutputStream(FileOutputStream(archivo)).use { zip ->
            fun agregar(nombre: String, contenido: String) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(contenido.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            agregar("[Content_Types].xml", contentTypes)
            agregar("_rels/.rels", rootRels)
            agregar("xl/workbook.xml", workbook)
            agregar("xl/_rels/workbook.xml.rels", workbookRels)
            agregar("xl/styles.xml", styles)
            agregar("xl/worksheets/sheet1.xml", hoja)
        }
    }

    private fun escapar(v: String): String =
        v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
