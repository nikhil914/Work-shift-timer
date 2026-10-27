package com.arrivelog.domain.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** RFC 4180 CSV with a UTF-8 BOM, so Excel opens Arabic / accented notes correctly. */
object CsvWriter {
    fun write(table: ExportTable, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("﻿")
        w.write(table.headers.joinToString(",") { escape(it) })
        w.write("\r\n")
        table.rows.forEach { row ->
            w.write(row.joinToString(",") { cell -> escape(cell.text()) })
            w.write("\r\n")
        }
        w.flush()
    }

    private fun Cell.text(): String = when (this) {
        is Cell.Text -> value
        is Cell.Number -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        Cell.Empty -> ""
    }

    private fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}

/**
 * Minimal Office Open XML workbook: one sheet, inline strings, numbers as numbers, bold header.
 * Hand-written (a zip of five small XML files) instead of Apache POI, which would add megabytes
 * to a 2 MB app for one feature.
 */
object XlsxWriter {
    fun write(table: ExportTable, out: OutputStream, sheetName: String = "ArriveLog") {
        val zip = ZipOutputStream(out)
        fun entry(name: String, body: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", CONTENT_TYPES)
        entry("_rels/.rels", ROOT_RELS)
        entry("xl/workbook.xml", workbook(sheetName))
        entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        entry("xl/styles.xml", STYLES)
        entry("xl/worksheets/sheet1.xml", sheet(table))
        zip.finish()
    }

    private fun sheet(table: ExportTable): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<sheetData>")
        row(1, table.headers.map { Cell.Text(it) }, header = true)
        table.rows.forEachIndexed { i, cells -> row(i + 2, cells, header = false) }
        append("</sheetData></worksheet>")
    }

    private fun StringBuilder.row(index: Int, cells: List<Cell>, header: Boolean) {
        append("<row r=\"").append(index).append("\">")
        cells.forEachIndexed { col, cell ->
            val ref = columnName(col) + index
            val style = if (header) " s=\"1\"" else ""
            when (cell) {
                is Cell.Text -> append("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">")
                    .append(xml(cell.value)).append("</t></is></c>")
                is Cell.Number -> append("<c r=\"$ref\"$style><v>").append(cell.value).append("</v></c>")
                Cell.Empty -> Unit
            }
        }
        append("</row>")
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    internal fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** Escapes markup and drops control characters XML 1.0 cannot carry (they would corrupt the file). */
    private fun xml(v: String): String = buildString {
        v.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> append(c)
            }
        }
    }

    private fun workbook(sheetName: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="${xml(sheetName.take(31))}" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
            """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            """</Types>"""

    private const val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private const val WORKBOOK_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """</Relationships>"""

    /** Style 0 = default, style 1 = bold (header row). */
    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>""" +
            """</styleSheet>"""
}
