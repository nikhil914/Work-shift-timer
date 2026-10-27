package com.arrivelog.domain.export

import com.arrivelog.domain.day.DayChip
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.testing.DUBAI
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.SUN
import com.arrivelog.domain.testing.TUE
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ExportTest {

    private val labels = ExportLabels(
        headers = ExportColumn.entries.associateWith { it.name.lowercase() },
        chip = { chip ->
            when (chip) {
                DayChip.OnTime -> "On time"
                is DayChip.Late -> "Late"
                is DayChip.Status -> chip.status.name
                else -> chip.toString()
            }
        },
        source = { it.name },
        yes = "yes",
    )

    private val records = listOf(
        WorkDay(MON, arrivalAt = MON.at(7, 55), arrivalSource = SignalSource.GEOFENCE, exitAt = MON.at(16, 25), exitSource = SignalSource.GEOFENCE, zoneId = DUBAI),
        WorkDay(TUE, arrivalAt = TUE.at(8, 12), arrivalSource = SignalSource.WIFI, zoneId = DUBAI, note = "traffic, \"bad\"", arrivalEdited = true),
    )

    private fun table() = WorkLogExport.table(MON, SUN, records, ScheduleSettings(), DUBAI, SUN.at(20, 0), labels)

    private fun col(c: ExportColumn) = ExportColumn.entries.indexOf(c)

    @Test
    fun `one row per day with times, decimal hours, lateness and the edited flag`() {
        val t = table()
        assertThat(t.rows).hasSize(7)
        val mon = t.rows[0]
        assertThat(mon[col(ExportColumn.ARRIVAL)]).isEqualTo(Cell.Text("07:55"))
        assertThat(mon[col(ExportColumn.EXIT)]).isEqualTo(Cell.Text("16:25"))
        assertThat(mon[col(ExportColumn.HOURS)]).isEqualTo(Cell.Number(8.5))
        assertThat(mon[col(ExportColumn.STATUS)]).isEqualTo(Cell.Text("On time"))
        val tue = t.rows[1]
        assertThat(tue[col(ExportColumn.LATE_MINUTES)]).isEqualTo(Cell.Number(12.0))
        assertThat(tue[col(ExportColumn.HOURS)]).isEqualTo(Cell.Empty) // no exit: not guessed
        assertThat(tue[col(ExportColumn.EDITED)]).isEqualTo(Cell.Text("yes"))
        assertThat(t.rows[5][col(ExportColumn.STATUS)]).isEqualTo(Cell.Text(DayStatus.WEEKEND.name))
    }

    @Test
    fun `days after today are left out`() {
        val t = WorkLogExport.table(MON, SUN, records, ScheduleSettings(), DUBAI, TUE.at(12, 0), labels)
        assertThat(t.rows.map { (it[0] as Cell.Text).value }).containsExactly(MON.toString(), TUE.toString()).inOrder()
    }

    @Test
    fun `csv quotes commas and quotes and starts with a BOM`() {
        val out = ByteArrayOutputStream()
        CsvWriter.write(table(), out)
        val text = out.toString(Charsets.UTF_8)
        assertThat(text).startsWith("﻿date,weekday,")
        assertThat(text).contains("\"traffic, \"\"bad\"\"\"")
        assertThat(text).contains(",8.5,")
    }

    @Test
    fun `xlsx is a zip with the workbook parts and escaped inline strings`() {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(table(), out)
        val parts = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                parts[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertThat(parts.keys).containsAtLeast("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/styles.xml")
        val sheet = parts.getValue("xl/worksheets/sheet1.xml")
        assertThat(sheet).contains("<c r=\"F2\"><v>8.5</v></c>")
        assertThat(sheet).contains("traffic, &quot;bad&quot;")
        assertThat(sheet).contains("<row r=\"8\">")
    }

    @Test
    fun `column names go past Z`() {
        assertThat(XlsxWriter.columnName(0)).isEqualTo("A")
        assertThat(XlsxWriter.columnName(25)).isEqualTo("Z")
        assertThat(XlsxWriter.columnName(26)).isEqualTo("AA")
        assertThat(XlsxWriter.columnName(27)).isEqualTo("AB")
    }
}
