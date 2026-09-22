package de.kuemmero.app

import android.content.Context
import android.content.ContentValues
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.media.ToneGenerator
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private const val RECHNUNG_SCAN_PREFIX = "rechnung_scan_"
private const val RECHNUNGSNUMMER_COUNTER_KEY = "rechnungsnummer_counter"

private fun naechsteRechnungsnummer(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val jahr = SimpleDateFormat("yyyy", Locale.GERMANY).format(Date())
    val key = RECHNUNGSNUMMER_COUNTER_KEY + "_" + jahr
    val naechste = prefs.getInt(key, 0) + 1
    return "RE-$jahr-" + naechste.toString().padStart(4, '0')
}

private fun speichereRechnungsnummer(
    context: Context,
    nummer: String
) {
    val match = Regex("^RE-(\\d{4})-(\\d+)$").matchEntire(nummer.trim())
        ?: return

    val jahr = match.groupValues[1]
    val nummerWert = match.groupValues[2].toIntOrNull()
        ?: return

    val prefs = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    val key = RECHNUNGSNUMMER_COUNTER_KEY + "_" + jahr
    val bisher = prefs.getInt(key, 0)

    if (nummerWert > bisher) {
        prefs.edit()
            .putInt(key, nummerWert)
            .commit()
    }
}

private fun synchronisiereRechnungsnummerCounter(context: Context, nummer: String) {
    val match = Regex("^RE-(\\d{4})-(\\d+)$").matchEntire(nummer.trim()) ?: return
    val jahr = match.groupValues[1]
    val nummerWert = match.groupValues[2].toIntOrNull() ?: return
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val key = RECHNUNGSNUMMER_COUNTER_KEY + "_" + jahr
    val bisher = prefs.getInt(key, 0)
    if (nummerWert > bisher) {
        prefs.edit().putInt(key, nummerWert).commit()
    }
}

private fun rechnungScanKey(auftrag: Auftrag): String =
    RECHNUNG_SCAN_PREFIX + auftrag.nummer

private fun ladeRechnungScan(context: Context, auftrag: Auftrag): String =
    context.getSharedPreferences("kuemmero_rechnung_scans", Context.MODE_PRIVATE)
        .getString(rechnungScanKey(auftrag), "") ?: ""

private fun speichereRechnungScan(context: Context, auftrag: Auftrag, uri: Uri) {
    context.getSharedPreferences("kuemmero_rechnung_scans", Context.MODE_PRIVATE)
        .edit().putString(rechnungScanKey(auftrag), uri.toString()).apply()
}

data class Kunde(
    val name: String,
    val adresse: String = "",
    val ort: String = "",
    val telefon: String = "",
    val email: String = ""
)

data class Auftrag(
    val nummer: String = "",
    val datum: String = "",
    val gueltigBis: String = "",
    val kunde: String,
    val kundenStrasse: String,
    val kundenOrt: String,
    val leistung: String,
    val stunden: Double,
    val material: Double,
    val materialBonUri: String = "",
    val fahrt: Double,
    val stundensatz: Double,
    val status: String = "Offen",
    val zahlungsstatus: String = "Offen",
    val bezahltAm: String = "",
    val terminDatum: String = "",
    val terminUhrzeit: String = "",
    val notiz: String = "",
    val fotosVorher: List<String> = emptyList(),
    val fotosNachher: List<String> = emptyList(),
    val unterschriftPfad: String = "",
    val unterschriftDatum: String = "",
    val rechnungsnummer: String = "",
    val rechnungsdatum: String = "",
    val faelligAm: String = "",
    val arbeitsStart: Long = 0L,
    val arbeitsEnde: Long = 0L,
    val arbeitsSekunden: Long = 0L,
    val arbeitszeitUebernommen: Boolean = false,
    val erstellungskosten: Double = 0.0,
    val leistungsdatum: String = ""
)

private const val PREFS_NAME = "kuemmero_speicher"
private const val AUFTRAEGE_KEY = "auftraege"
private const val KUNDEN_KEY = "kunden"
private const val STUNDENSATZ_KEY = "stundensatz"
private const val BACKUP_URI_KEY = "backup_uri"
private const val BACKUP_LAST_SUCCESS_KEY = "backup_last_success"
private const val BACKUP_PRE_RESTORE_FILE = "kuemmero_vor_restore_backup.json"
private const val KOSTENVORANSCHLAEGE_KEY = "kostenvoranschlaege"
private const val FIRMENNAME_KEY = "firmen_name"
private const val FIRMENSTRASSE_KEY = "firmen_strasse"
private const val FIRMENPLZORT_KEY = "firmen_plz_ort"
private const val FIRMENTELEFON_KEY = "firmen_telefon"
private const val FIRMENEMAIL_KEY = "firmen_email"
private const val STEUERNUMMER_KEY = "steuernummer"

data class Kostenvoranschlag(
    val nummer: String = "",
    val datum: String = "",
    val gueltigBis: String = "",
    val kunde: String = "",
    val kundenStrasse: String = "",
    val kundenOrt: String = "",
    val leistung: String = "",
    val stunden: Double = 0.0,
    val material: Double = 0.0,
    val fahrt: Double = 0.0,
    val stundensatz: Double = 42.0,
    val materialBonUri: String = "",
    val fotosVorher: List<String> = emptyList(),
    val erstellungskosten: Double = 0.0
)

private fun ladeKostenvoranschlaege(context: Context): List<Kostenvoranschlag> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KOSTENVORANSCHLAEGE_KEY, "[]") ?: "[]"
    val json = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    return List(json.length()) { i ->
        val o = json.optJSONObject(i) ?: JSONObject()
        Kostenvoranschlag(
            o.optString("nummer"), o.optString("datum"), o.optString("gueltigBis"),
            o.optString("kunde"), o.optString("kundenStrasse"), o.optString("kundenOrt"),
            o.optString("leistung"), o.optDouble("stunden", 0.0), o.optDouble("material", 0.0),
            o.optDouble("fahrt", 0.0), o.optDouble("stundensatz", 42.0), o.optString("materialBonUri", ""),
            run { val a = o.optJSONArray("fotosVorher") ?: JSONArray(); List(a.length()) { j -> a.optString(j) } },
            o.optDouble("erstellungskosten", 0.0)
        )
    }
}

private fun speichereKostenvoranschlaege(context: Context, liste: List<Kostenvoranschlag>) {
    val json = JSONArray()
    liste.forEach { k ->
        json.put(JSONObject().apply {
            put("nummer", k.nummer); put("datum", k.datum); put("gueltigBis", k.gueltigBis)
            put("kunde", k.kunde); put("kundenStrasse", k.kundenStrasse); put("kundenOrt", k.kundenOrt)
            put("leistung", k.leistung); put("stunden", k.stunden); put("material", k.material)
            put("fahrt", k.fahrt); put("stundensatz", k.stundensatz); put("materialBonUri", k.materialBonUri)
            put("fotosVorher", JSONArray(k.fotosVorher))
            put("erstellungskosten", k.erstellungskosten)
        })
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KOSTENVORANSCHLAEGE_KEY, json.toString()).commit()
}


private fun zahl(text: String, standard: Double = 0.0): Double =
    text.replace("€", "").replace(" ", "").replace(",", ".").trim()
        .toDoubleOrNull() ?: standard

private fun runde2(value: Double): Double =
    kotlin.math.round(value * 100.0) / 100.0

private fun arbeitsbetrag(stunden: Double, stundensatz: Double): Double {
    // Für die Abrechnung zuerst beide Werte auf die angezeigten 2 Nachkommastellen bringen.
    // Dadurch wird z. B. 0,01 Std. bei 42,00 €/Std. immer zu 0,42 €.
    val abrechnungsStunden = runde2(stunden)
    val abrechnungsSatz = runde2(stundensatz)
    return runde2(abrechnungsStunden * abrechnungsSatz)
}

private fun gesamtbetrag(stunden: Double, material: Double, fahrt: Double, stundensatz: Double, erstellungskosten: Double = 0.0): Double {
    val arbeitskosten = arbeitsbetrag(stunden, stundensatz)
    val materialkosten = runde2(material)
    val fahrtkosten = runde2(fahrt)
    return runde2(arbeitskosten + materialkosten + fahrtkosten + runde2(erstellungskosten))
}

private fun euro(value: Double): String =
    String.format(Locale.GERMANY, "%.2f €", runde2(value))

private fun ladeAuftraege(context: Context): List<Auftrag> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(AUFTRAEGE_KEY, "[]") ?: "[]"
    val json = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    return List(json.length()) { i ->
        val o = json.optJSONObject(i) ?: JSONObject()
        Auftrag(
            o.optString("nummer"),
            o.optString("datum"),
            o.optString("gueltigBis"),
            o.optString("kunde"),
            o.optString("kundenStrasse"),
            o.optString("kundenOrt"),
            o.optString("leistung"),
            o.optDouble("stunden", 0.0),
            o.optDouble("material", 0.0),
            o.optString("materialBonUri", ""),
            o.optDouble("fahrt", 0.0),
            o.optDouble("stundensatz", 42.0),
            o.optString("status", "Offen").ifBlank { "Offen" },
            o.optString("zahlungsstatus", "Offen").ifBlank { "Offen" },
            o.optString("bezahltAm", ""),
            o.optString("terminDatum", ""),
            o.optString("terminUhrzeit", ""),
            o.optString("notiz", ""),
            o.optJSONArray("fotosVorher")?.let { arr -> List(arr.length()) { j -> arr.optString(j) } } ?: emptyList(),
            o.optJSONArray("fotosNachher")?.let { arr -> List(arr.length()) { j -> arr.optString(j) } } ?: emptyList(),
            o.optString("unterschriftPfad", ""),
            o.optString("unterschriftDatum", ""),
            o.optString("rechnungsnummer", ""),
            o.optString("rechnungsdatum", ""),
            o.optString("faelligAm", ""),
            o.optLong("arbeitsStart", 0L),
            o.optLong("arbeitsEnde", 0L),
            o.optLong("arbeitsSekunden", 0L),
            o.optBoolean("arbeitszeitUebernommen", false),
            o.optDouble("erstellungskosten", 0.0)
        )
    }
}

private fun ladeKunden(context: Context): List<Kunde> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KUNDEN_KEY, "[]") ?: "[]"
    val json = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    return List(json.length()) { i ->
        val o = json.optJSONObject(i) ?: JSONObject()
        Kunde(
            o.optString("name"),
            o.optString("adresse"),
            o.optString("ort"),
            o.optString("telefon"),
            o.optString("email")
        )
    }.filter { it.name.isNotBlank() }
}

private fun speichereKunden(context: Context, liste: List<Kunde>) {
    val json = JSONArray()
    liste.forEach { k ->
        json.put(JSONObject().apply {
            put("name", k.name)
            put("adresse", k.adresse)
            put("ort", k.ort)
            put("telefon", k.telefon)
            put("email", k.email)
        })
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KUNDEN_KEY, json.toString()).commit()
}

private fun speichereOderAktualisiereKunde(context: Context, kunde: Kunde) {
    if (kunde.name.isBlank()) return
    val liste = ladeKunden(context).toMutableList()
    val index = liste.indexOfFirst { it.name.equals(kunde.name, ignoreCase = true) }
    if (index >= 0) liste[index] = kunde else liste.add(kunde)
    speichereKunden(context, liste)
}

private fun speichereAuftraege(context: Context, liste: List<Auftrag>) {
    val json = JSONArray()
    liste.forEach { a ->
        json.put(JSONObject().apply {
            put("nummer", a.nummer)
            put("datum", a.datum)
            put("gueltigBis", a.gueltigBis)
            put("kunde", a.kunde)
            put("kundenStrasse", a.kundenStrasse)
            put("kundenOrt", a.kundenOrt)
            put("leistung", a.leistung)
            put("stunden", a.stunden)
            put("material", a.material)
            put("materialBonUri", a.materialBonUri)
            put("fahrt", a.fahrt)
            put("stundensatz", a.stundensatz)
            put("status", a.status)
            put("zahlungsstatus", a.zahlungsstatus)
            put("bezahltAm", a.bezahltAm)
            put("terminDatum", a.terminDatum)
            put("terminUhrzeit", a.terminUhrzeit)
            put("notiz", a.notiz)
            put("fotosVorher", JSONArray(a.fotosVorher))
            put("fotosNachher", JSONArray(a.fotosNachher))
            put("unterschriftPfad", a.unterschriftPfad)
            put("unterschriftDatum", a.unterschriftDatum)
            put("rechnungsnummer", a.rechnungsnummer)
            put("rechnungsdatum", a.rechnungsdatum)
            put("faelligAm", a.faelligAm)
            put("arbeitsStart", a.arbeitsStart)
            put("arbeitsEnde", a.arbeitsEnde)
            put("arbeitsSekunden", a.arbeitsSekunden)
            put("arbeitszeitUebernommen", a.arbeitszeitUebernommen)
            put("erstellungskosten", a.erstellungskosten)
            put("leistungsdatum", a.leistungsdatum)
        })
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(AUFTRAEGE_KEY, json.toString()).commit()
}

private fun backupText(context: Context): String {
    val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return JSONObject().apply {
        put("stundensatz", p.getString(STUNDENSATZ_KEY, "42.00") ?: "42.00")
        put("firmenName", p.getString(FIRMENNAME_KEY, "Markus Becker") ?: "Markus Becker")
        put("firmenStrasse", p.getString(FIRMENSTRASSE_KEY, "") ?: "")
        put("firmenPlzOrt", p.getString(FIRMENPLZORT_KEY, "") ?: "")
        put("firmenTelefon", p.getString(FIRMENTELEFON_KEY, "+49 176 16712509") ?: "+49 176 16712509")
        put("firmenEmail", p.getString(FIRMENEMAIL_KEY, "kuemmero@web.de") ?: "kuemmero@web.de")
        put("steuernummer", p.getString(STEUERNUMMER_KEY, "") ?: "")
        put("auftraege", JSONArray(p.getString(AUFTRAEGE_KEY, "[]") ?: "[]"))
        put("kunden", JSONArray(p.getString(KUNDEN_KEY, "[]") ?: "[]"))
        put("kostenvoranschlaege", JSONArray(p.getString(KOSTENVORANSCHLAEGE_KEY, "[]") ?: "[]"))
    }.toString(2)
}

private fun sichereBackupAutomatisch(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val uriText = prefs.getString(BACKUP_URI_KEY, null) ?: return false
    return try {
        val uri = Uri.parse(uriText)
        val ok = context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(backupText(context).toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } ?: false
        if (!ok) {
            prefs.edit().remove(BACKUP_URI_KEY).apply()
        } else {
            prefs.edit().putLong(BACKUP_LAST_SUCCESS_KEY, System.currentTimeMillis()).apply()
        }
        ok
    } catch (_: Exception) {
        // Die bisher gewählte Datei wurde z. B. gelöscht oder verschoben.
        // Die alte URI darf danach nicht weiter verwendet werden.
        prefs.edit().remove(BACKUP_URI_KEY).apply()
        false
    }
}

private fun ladeUnterschriftBitmap(pfad: String): android.graphics.Bitmap? {
    if (pfad.isBlank()) return null
    val original = android.graphics.BitmapFactory.decodeFile(pfad) ?: return null
    val width = original.width
    val height = original.height
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    val pixels = IntArray(width)
    for (y in 0 until height) {
        original.getPixels(pixels, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val c = pixels[x]
            val r = android.graphics.Color.red(c)
            val g = android.graphics.Color.green(c)
            val b = android.graphics.Color.blue(c)
            val alpha = android.graphics.Color.alpha(c)
            if (alpha > 20 && (r < 245 || g < 245 || b < 245)) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
    }
    if (right < left || bottom < top) {
        original.recycle()
        return null
    }
    val margin = 10
    val cropLeft = maxOf(0, left - margin)
    val cropTop = maxOf(0, top - margin)
    val cropRight = minOf(width, right + margin + 1)
    val cropBottom = minOf(height, bottom + margin + 1)
    val cropped = android.graphics.Bitmap.createBitmap(
        original, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop
    )
    if (cropped !== original) original.recycle()
    return cropped
}

private fun ladeFotoBitmap(context: Context, uriText: String): android.graphics.Bitmap? {
    return try {
        context.contentResolver.openInputStream(Uri.parse(uriText))?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        }
    } catch (_: Exception) { null }
}

private fun fuegeFotoSeitenHinzu(
    context: Context,
    pdf: PdfDocument,
    fotosVorher: List<String>,
    fotosNachher: List<String>
) {
    val gruppen = listOf("FOTOS – VORHER" to fotosVorher, "FOTOS – NACHHER" to fotosNachher)
    var seitenNummer = 2
    for ((titel, fotos) in gruppen) {
        if (fotos.isEmpty()) continue
        for ((index, uriText) in fotos.withIndex()) {
            val bitmap = ladeFotoBitmap(context, uriText) ?: continue
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, seitenNummer++).create())
            val c = page.canvas
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.textSize = 20f
            c.drawText(titel, 40f, 50f, p)
            p.textSize = 11f
            c.drawText("Foto ${index + 1} von ${fotos.size}", 40f, 72f, p)

            val maxW = 515f
            val maxH = 690f
            val scale = minOf(maxW / bitmap.width.toFloat(), maxH / bitmap.height.toFloat())
            val drawW = bitmap.width * scale
            val drawH = bitmap.height * scale
            val left = (595f - drawW) / 2f
            val top = 105f + (maxH - drawH) / 2f
            c.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + drawW, top + drawH), null)
            bitmap.recycle()
            pdf.finishPage(page)
        }
    }
}

private fun erstellePdf(
    context: Context,
    nummer: String,
    datum: String,
    gueltigBis: String,
    kunde: String,
    strasse: String,
    ort: String,
    leistung: String,
    stunden: Double,
    material: Double,
    fahrt: Double,
    stundensatz: Double,
    unterschriftPfad: String = "",
    unterschriftDatum: String = "",
    fotosVorher: List<String> = emptyList(),
    fotosNachher: List<String> = emptyList(),
    dokumentTitel: String = "ANGEBOT",
    erstellungskosten: Double = 0.0
): PdfDocument {
    val pdf = PdfDocument()
    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val c = page.canvas
    val p = Paint()
    p.textSize = 28f
    c.drawText("KÜMMERO", 40f, 60f, p)
    p.textSize = 13f
    c.drawText("Haus & Alltag – wir kümmern uns.", 40f, 88f, p)
    c.drawText("Hausmeisterservice & Seniorenbetreuung", 40f, 108f, p)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val firmenName = prefs.getString(FIRMENNAME_KEY, "Markus Becker") ?: "Markus Becker"
    val firmenStrasse = prefs.getString(FIRMENSTRASSE_KEY, "") ?: ""
    val firmenPlzOrt = prefs.getString(FIRMENPLZORT_KEY, "") ?: ""
    val firmenTelefon = prefs.getString(FIRMENTELEFON_KEY, "+49 176 16712509") ?: "+49 176 16712509"
    val firmenEmail = prefs.getString(FIRMENEMAIL_KEY, "kuemmero@web.de") ?: "kuemmero@web.de"
    val steuernummer = prefs.getString(STEUERNUMMER_KEY, "") ?: ""
    c.drawText(firmenName.ifBlank { "Name / Inhaber: bitte eintragen" }, 40f, 132f, p)
    c.drawText(firmenStrasse.ifBlank { "Firmenstraße / Hausnummer: bitte eintragen" }, 40f, 150f, p)
    c.drawText(firmenPlzOrt.ifBlank { "PLZ / Ort: bitte eintragen" }, 40f, 168f, p)
    c.drawText("Telefon: ${firmenTelefon.ifBlank { "bitte eintragen" }}", 40f, 186f, p)
    c.drawText("E-Mail: ${firmenEmail.ifBlank { "bitte eintragen" }}", 40f, 204f, p)
    c.drawText(dokumentTitel, 40f, 233f, p)
    p.textSize = 12f
    c.drawText("Angebotsnummer: $nummer", 40f, 258f, p)
    c.drawText("Datum: $datum", 40f, 278f, p)
    c.drawText("Gültig bis: $gueltigBis", 40f, 298f, p)
    c.drawText("Kunde: $kunde", 40f, 328f, p)
    c.drawText("Straße: $strasse", 40f, 348f, p)
    c.drawText("PLZ und Ort: $ort", 40f, 368f, p)
    c.drawText("Leistung: $leistung", 40f, 398f, p)
    c.drawLine(40f, 423f, 550f, 423f, p)
    c.drawText("Arbeitszeit", 40f, 448f, p)
    c.drawText("%.2f Std.".format(Locale.GERMANY, stunden), 250f, 448f, p)
    c.drawText(euro(arbeitsbetrag(stunden, stundensatz)), 450f, 448f, p)
    c.drawText("Material", 40f, 473f, p)
    c.drawText(euro(material), 450f, 473f, p)
    c.drawText("Fahrtkosten", 40f, 498f, p)
    c.drawText(euro(fahrt), 450f, 498f, p)
    if (erstellungskosten > 0.0) {
        c.drawText("Erstellungskosten", 40f, 523f, p)
        c.drawText(euro(erstellungskosten), 450f, 523f, p)
    }
    c.drawLine(40f, if (erstellungskosten > 0.0) 538f else 513f, 550f, if (erstellungskosten > 0.0) 538f else 513f, p)
    val gesamt = gesamtbetrag(stunden, material, fahrt, stundensatz, erstellungskosten)
    p.textSize = 18f
    c.drawText("Gesamtsumme: ${euro(gesamt)}", 40f, if (erstellungskosten > 0.0) 573f else 548f, p)
    p.textSize = 11f
    val kvHinweisY = if (erstellungskosten > 0.0) 603f else 578f
    c.drawText("Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.", 40f, kvHinweisY, p)
    val unterschriftTitelY = if (dokumentTitel.contains("KOSTENVORANSCHLAG", ignoreCase = true) && erstellungskosten > 0.0) 650f else 628f
    if (dokumentTitel.contains("KOSTENVORANSCHLAG", ignoreCase = true) && erstellungskosten > 0.0) {
        p.textSize = 11f
        c.drawText("Hinweis: Dieser Kostenvoranschlag ist kostenpflichtig.", 40f, 628f, p)
    }
    c.drawText("Auftragserteilung / Unterschrift Kunde:", 40f, unterschriftTitelY, p)
    val signBitmap = ladeUnterschriftBitmap(unterschriftPfad)
    if (signBitmap != null) {
        val maxW = 225f
        val maxH = 34f
        val scale = minOf(maxW / signBitmap.width.toFloat(), maxH / signBitmap.height.toFloat())
        val drawW = signBitmap.width * scale
        val drawH = signBitmap.height * scale
        val signTop = if (dokumentTitel.contains("KOSTENVORANSCHLAG", ignoreCase = true) && erstellungskosten > 0.0) 670f else 648f
        val dst = android.graphics.RectF(40f, signTop, 40f + drawW, signTop + drawH)
        c.drawBitmap(signBitmap, null, dst, null)
        signBitmap.recycle()
    }
    val signLineY = if (dokumentTitel.contains("KOSTENVORANSCHLAG", ignoreCase = true) && erstellungskosten > 0.0) 715f else 693f
    c.drawLine(40f, signLineY, 280f, signLineY, p)
    c.drawText("Unterschrift", 40f, signLineY + 18f, p)
    c.drawLine(330f, signLineY, 550f, signLineY, p)
    c.drawText("Datum", 330f, signLineY + 18f, p)
    c.drawText("Vielen Dank für Ihr Vertrauen.", 40f, if (signLineY > 700f) 785f else 763f, p)
    pdf.finishPage(page)
    fuegeFotoSeitenHinzu(context, pdf, fotosVorher, fotosNachher)
    return pdf
}


private fun erstelleRechnungPdf(
    context: Context,
    nummer: String,
    rechnungsdatum: String,
    faelligAm: String,
    leistungsdatum: String,
    kunde: String,
    strasse: String,
    ort: String,
    leistung: String,
    stunden: Double,
    material: Double,
    fahrt: Double,
    stundensatz: Double,
    unterschriftPfad: String = "",
    unterschriftDatum: String = "",
    fotosVorher: List<String> = emptyList(),
    fotosNachher: List<String> = emptyList(),
    erstellungskosten: Double = 0.0
): PdfDocument {
    val pdf = PdfDocument()
    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val c = page.canvas
    val p = Paint()
    p.textSize = 28f
    c.drawText("KÜMMERO", 40f, 60f, p)
    p.textSize = 13f
    c.drawText("Haus & Alltag – wir kümmern uns.", 40f, 88f, p)
    c.drawText("Hausmeisterservice & Seniorenbetreuung", 40f, 108f, p)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val firmenName = prefs.getString(FIRMENNAME_KEY, "Markus Becker") ?: "Markus Becker"
    val firmenStrasse = prefs.getString(FIRMENSTRASSE_KEY, "") ?: ""
    val firmenPlzOrt = prefs.getString(FIRMENPLZORT_KEY, "") ?: ""
    val firmenTelefon = prefs.getString(FIRMENTELEFON_KEY, "+49 176 16712509") ?: "+49 176 16712509"
    val firmenEmail = prefs.getString(FIRMENEMAIL_KEY, "kuemmero@web.de") ?: "kuemmero@web.de"
    val steuernummer = prefs.getString(STEUERNUMMER_KEY, "") ?: ""
    c.drawText(firmenName.ifBlank { "Name / Inhaber: bitte eintragen" }, 40f, 132f, p)
    c.drawText(firmenStrasse.ifBlank { "Firmenstraße / Hausnummer: bitte eintragen" }, 40f, 150f, p)
    c.drawText(firmenPlzOrt.ifBlank { "PLZ / Ort: bitte eintragen" }, 40f, 168f, p)
    c.drawText("Telefon: ${firmenTelefon.ifBlank { "bitte eintragen" }}", 40f, 186f, p)
    c.drawText("E-Mail: ${firmenEmail.ifBlank { "bitte eintragen" }}", 40f, 204f, p)

    p.textSize = 18f
    c.drawText("RECHNUNG", 40f, 230f, p)
    p.textSize = 12f
    c.drawText("Rechnungsnummer: $nummer", 40f, 255f, p)
    c.drawText("Rechnungsdatum: $rechnungsdatum", 40f, 275f, p)
    c.drawText("Fällig am: $faelligAm", 40f, 295f, p)
    c.drawText("Steuer-/USt-ID/KU-IdNr.: ${steuernummer.ifBlank { "BITTE IN MEHR EINTRAGEN" }}", 40f, 315f, p)
    c.drawText("Leistungsdatum: ${leistungsdatum.ifBlank { rechnungsdatum }}", 40f, 335f, p)
    c.drawText("Kunde: $kunde", 40f, 365f, p)
    c.drawText("Adresse: $strasse", 40f, 385f, p)
    c.drawText("PLZ und Ort: $ort", 40f, 405f, p)
    c.drawText("Leistung: $leistung", 40f, 435f, p)

    c.drawLine(40f, 460f, 550f, 460f, p)
    c.drawText("Arbeitszeit", 40f, 485f, p)
    c.drawText("%.2f Std.".format(Locale.GERMANY, stunden), 250f, 485f, p)
    c.drawText(euro(arbeitsbetrag(stunden, stundensatz)), 450f, 485f, p)
    c.drawText("Material", 40f, 510f, p)
    c.drawText(euro(material), 450f, 510f, p)
    c.drawText("Fahrtkosten", 40f, 535f, p)
    c.drawText(euro(fahrt), 450f, 535f, p)
    var rechnungY = 560f
    if (erstellungskosten > 0.0) {
        c.drawText("Erstellungskosten", 40f, 560f, p)
        c.drawText(euro(erstellungskosten), 450f, 560f, p)
        rechnungY = 585f
    }
    c.drawLine(40f, rechnungY, 550f, rechnungY, p)

    val gesamt = gesamtbetrag(stunden, material, fahrt, stundensatz, erstellungskosten)
    p.textSize = 18f
    c.drawText("Gesamtsumme: ${euro(gesamt)}", 40f, rechnungY + 40f, p)
    p.textSize = 11f
    c.drawText("Steuerbefreiung für Kleinunternehmer gemäß § 19 UStG.", 40f, rechnungY + 68f, p)
    c.drawText("Es wird keine Umsatzsteuer berechnet.", 40f, rechnungY + 83f, p)
    c.drawText("Bitte überweisen Sie den Rechnungsbetrag bis zum $faelligAm.", 40f, rechnungY + 105f, p)
    c.drawText("Vielen Dank für Ihr Vertrauen.", 40f, rechnungY + 130f, p)
    pdf.finishPage(page)

    if (unterschriftPfad.isNotBlank()) {
        val anlage = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 2).create())
        val ac = anlage.canvas
        val ap = Paint()
        ap.textSize = 22f
        ac.drawText("KÜMMERO", 40f, 60f, ap)
        ap.textSize = 16f
        ac.drawText("ANLAGE – UNTERSCHRIEBENER AUFTRAG", 40f, 105f, ap)
        ap.textSize = 11f
        ac.drawText("Zur Rechnung: $nummer", 40f, 130f, ap)
        ac.drawText("Kunde: $kunde", 40f, 155f, ap)
        ac.drawText("Adresse: $strasse, $ort", 40f, 175f, ap)
        ac.drawText("Leistung: $leistung", 40f, 195f, ap)
        ac.drawText("Unterschriftsdatum: ${unterschriftDatum.ifBlank { "—" }}", 40f, 215f, ap)
        ap.textSize = 14f
        ac.drawText("Digitale Kunden-Unterschrift", 40f, 275f, ap)
        val signBitmap = ladeUnterschriftBitmap(unterschriftPfad)
        if (signBitmap != null) {
            val scale = minOf(420f / signBitmap.width.toFloat(), 180f / signBitmap.height.toFloat())
            val w = signBitmap.width * scale
            val h = signBitmap.height * scale
            ac.drawBitmap(signBitmap, null, android.graphics.RectF(40f, 300f, 40f + w, 300f + h), null)
            signBitmap.recycle()
        }
        ap.textSize = 10f
        ac.drawText("Diese Seite ist als Nachweis dem Rechnungsdokument beigefügt.", 40f, 530f, ap)
        pdf.finishPage(anlage)
    }

    fuegeFotoSeitenHinzu(context, pdf, fotosVorher, fotosNachher)
    return pdf
}

private fun druckeRechnungPdf(context: Context, auftrag: Auftrag) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val nummer = auftrag.rechnungsnummer.ifBlank { naechsteRechnungsnummer(context) }
    val rechnungsdatum = auftrag.rechnungsdatum.ifBlank { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date()) }
    val faelligAm = auftrag.faelligAm.ifBlank {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 14) }
        SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(cal.time)
    }
    val adapter = object : PrintDocumentAdapter() {
        private var pdf: PdfDocument? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: android.os.Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            pdf?.close()
            pdf = erstelleRechnungPdf(
                context,
                nummer, rechnungsdatum, faelligAm,
                auftrag.terminDatum.ifBlank { auftrag.datum },
                auftrag.kunde, auftrag.kundenStrasse, auftrag.kundenOrt, auftrag.leistung,
                auftrag.stunden, auftrag.material, auftrag.fahrt, auftrag.stundensatz,
                auftrag.unterschriftPfad, auftrag.unterschriftDatum, auftrag.fotosVorher, auftrag.fotosNachher, auftrag.erstellungskosten
            )
            val info = PrintDocumentInfo.Builder("KÜMMERO-Rechnung-$nummer.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out android.print.PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                val document = pdf ?: throw IllegalStateException("Rechnung konnte nicht erstellt werden")
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    document.writeTo(output)
                }
                callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            } finally {
                pdf?.close()
                pdf = null
            }
        }

        override fun onFinish() {
            pdf?.close()
            pdf = null
            super.onFinish()
        }
    }

    printManager.print(
        "KÜMMERO-Rechnung-$nummer",
        adapter,
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
    )
}

private fun druckePdf(
    context: Context,
    dateiname: String,
    nummer: String,
    datum: String,
    gueltigBis: String,
    auftrag: Auftrag,
    dokumentTitel: String = "ANGEBOT"
) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val adapter = object : PrintDocumentAdapter() {
        private var pdf: PdfDocument? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: android.os.Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            pdf?.close()
            pdf = erstellePdf(
                context, nummer, datum, gueltigBis,
                auftrag.kunde, auftrag.kundenStrasse, auftrag.kundenOrt, auftrag.leistung,
                auftrag.stunden, auftrag.material, auftrag.fahrt, auftrag.stundensatz,
                auftrag.unterschriftPfad, auftrag.unterschriftDatum, auftrag.fotosVorher, auftrag.fotosNachher, dokumentTitel, auftrag.erstellungskosten
            )
            val info = PrintDocumentInfo.Builder(dateiname)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out android.print.PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                val document = pdf ?: throw IllegalStateException("PDF konnte nicht erstellt werden")
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    document.writeTo(output)
                }
                callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            } finally {
                pdf?.close()
                pdf = null
            }
        }

        override fun onFinish() {
            pdf?.close()
            pdf = null
            super.onFinish()
        }
    }

    printManager.print(
        dateiname.removeSuffix(".pdf"),
        adapter,
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
    )
}

private fun zeitText(sekunden: Long): String {
    val h = sekunden / 3600
    val m = (sekunden % 3600) / 60
    val s = sekunden % 60
    return "%02d:%02d:%02d".format(Locale.GERMANY, h, m, s)
}

private fun rechnungIstUeberfaellig(auftrag: Auftrag, heute: String): Boolean {
    if (auftrag.rechnungsnummer.isBlank() || auftrag.zahlungsstatus == "Bezahlt" || auftrag.faelligAm.isBlank()) return false
    return try {
        val format = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        val faellig = format.parse(auftrag.faelligAm)?.time ?: return false
        val heuteZeit = format.parse(heute)?.time ?: return false
        faellig < heuteZeit
    } catch (_: Exception) { false }
}

private val KuemmeroGreen = Color(0xFF087F3E)
private val KuemmeroGreenLight = Color(0xFF4CAF50)
private val KuemmeroMint = Color(0xFFE8F5E9)
private val KuemmeroBackground = Color(0xFFE8F5E9)
private val KuemmeroSurface = Color(0xFFE8F5E9)
private val KuemmeroText = Color(0xFF18352A)
private val KuemmeroError = Color(0xFFC62828)

private val KuemmeroColors = lightColorScheme(
    primary = KuemmeroGreen,
    onPrimary = Color.White,
    secondary = KuemmeroGreenLight,
    onSecondary = Color.White,
    background = KuemmeroBackground,
    onBackground = KuemmeroText,
    surface = KuemmeroSurface,
    onSurface = KuemmeroText,
    error = KuemmeroError,
    onError = Color.White
)

@Composable
private fun KlappBereich(
    titel: String,
    offen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { onToggle() },
            shape = RoundedCornerShape(14.dp),
            color = KuemmeroMint,
            border = BorderStroke(1.5.dp, KuemmeroGreen)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(titel, fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                Text(if (offen) "▲" else "▼", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
            }
        }
        if (offen) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun leistungsumfangHinweis(leistung: String): String? {
    val text = leistung.lowercase(Locale.GERMANY)
    val elektro = listOf(
        "elektroinstallation", "elektroinstall", "steckdose", "lichtschalter",
        "sicherungskasten", "unterverteilung", "stromleitung", "stromanschluss",
        "kabel verlegen", "elektrische installation"
    ).any { text.contains(it) }
    val schimmel = listOf(
        "schimmelsanierung", "schimmelsanieren", "professionelle schimmel",
        "schimmelbeseitigung", "schimmel sanierung"
    ).any { text.contains(it) }
    return when {
        elektro -> "Hinweis: Diese Leistungsbeschreibung kann in den Bereich des zulassungspflichtigen Elektrotechniker-Handwerks fallen. Nur Leistungen anbieten/ausführen, für die eine entsprechende Berechtigung besteht."
        schimmel -> "Hinweis: Professionelle Schimmel-Sanierungsarbeiten können besondere fachliche und rechtliche Anforderungen haben. Nur den tatsächlich zulässigen Leistungsumfang anbieten."
        else -> null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KuemmeroApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FotoVorschau(
    context: Context,
    uri: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = remember(uri) { ladeFotoBitmap(context, uri) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .border(1.dp, KuemmeroGreen, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Auftragsfoto",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Foto\nnicht lesbar", color = KuemmeroText, fontSize = 12.sp)
                }
            }
        }
        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = KuemmeroError)
        ) { Text("Löschen", fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KuemmeroApp() {
    val context = LocalContext.current
    val heute = remember { Date() }
    val datumFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY) }
    var kunde by remember { mutableStateOf("") }
    var strasse by remember { mutableStateOf("") }
    var ort by remember { mutableStateOf("") }
    var leistung by remember { mutableStateOf("") }
    var stunden by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    var materialBonUri by remember { mutableStateOf("") }
    var fahrt by remember { mutableStateOf("") }
    var stundensatz by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(STUNDENSATZ_KEY, "42.00") ?: "42.00"
        )
    }
    var auftraege by remember { mutableStateOf(ladeAuftraege(context)) }
    var kunden by remember { mutableStateOf(ladeKunden(context)) }
    var kundenSuche by remember { mutableStateOf("") }
    var kundenDialog by remember { mutableStateOf(false) }
    var neuerKundeDialog by remember { mutableStateOf(false) }
    var neuerKundenName by remember { mutableStateOf("") }
    var neuerKundenAdresse by remember { mutableStateOf("") }
    var neuerKundenOrt by remember { mutableStateOf("") }
    var neuerKundenTelefon by remember { mutableStateOf("") }
    var neuerKundenEmail by remember { mutableStateOf("") }
    var nummer by remember {
        mutableStateOf("ANG-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(heute))
    }
    var datum by remember { mutableStateOf(datumFormat.format(heute)) }
    var leistungsdatum by remember { mutableStateOf(datumFormat.format(heute)) }
    var gueltigBis by remember {
        val cal = Calendar.getInstance()
        cal.time = heute
        cal.add(Calendar.DAY_OF_YEAR, 14)
        mutableStateOf(datumFormat.format(cal.time))
    }
    var loeschIndex by remember { mutableStateOf<Int?>(null) }
    var bearbeiteIndex by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("Offen") }
    var zahlungsstatus by remember { mutableStateOf("Offen") }
    var bezahltAm by remember { mutableStateOf("") }
    var terminDatum by remember { mutableStateOf("") }
    var terminUhrzeit by remember { mutableStateOf("") }
    var notiz by remember { mutableStateOf("") }
    var fotosVorher by remember { mutableStateOf<List<String>>(emptyList()) }
    var fotosNachher by remember { mutableStateOf<List<String>>(emptyList()) }
    var unterschriftPfad by remember { mutableStateOf("") }
    var unterschriftDatum by remember { mutableStateOf("") }
    var fotoTyp by remember { mutableStateOf("Vorher") }
    var unterschriftDialog by remember { mutableStateOf(false) }
    var fotoVorschauUri by remember { mutableStateOf<String?>(null) }
    var auftragsSuche by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("Alle") }
    var zahlungsFilterOffen by remember { mutableStateOf(false) }
    var kundenAkteName by remember { mutableStateOf<String?>(null) }
    var kalenderOffen by remember { mutableStateOf(false) }
    var terminBereichOffen by remember { mutableStateOf(false) }
    var notizBereichOffen by remember { mutableStateOf(false) }
    var fotosBereichOffen by remember { mutableStateOf(false) }
    var unterschriftBereichOffen by remember { mutableStateOf(false) }
    var sicherungBereichOffen by remember { mutableStateOf(false) }
    var hauptseite by remember { mutableStateOf("Heute") }
    var kostenvoranschlaege by remember { mutableStateOf(ladeKostenvoranschlaege(context)) }
    var kvFormOffen by remember { mutableStateOf(false) }
    var kvBearbeiteIndex by remember { mutableStateOf<Int?>(null) }
    var kvNummer by remember { mutableStateOf("KV-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(heute)) }
    var kvDatum by remember { mutableStateOf(datumFormat.format(heute)) }
    var kvGueltigBis by remember { mutableStateOf(datumFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 14) }.time)) }
    var kvKunde by remember { mutableStateOf("") }
    var kvStrasse by remember { mutableStateOf("") }
    var kvOrt by remember { mutableStateOf("") }
    var kvLeistung by remember { mutableStateOf("") }
    var kvStunden by remember { mutableStateOf("") }
    var kvMaterial by remember { mutableStateOf("") }
    var kvMaterialBonUri by remember { mutableStateOf("") }
    var kvFotosVorher by remember { mutableStateOf<List<String>>(emptyList()) }
    var kvFahrt by remember { mutableStateOf("") }
    var kvStundensatz by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(STUNDENSATZ_KEY, "42.00") ?: "42.00") }
    var kvErstellungskosten by remember { mutableStateOf("") }
    var kvLoeschIndex by remember { mutableStateOf<Int?>(null) }
    var arbeitszeitAendernIndex by remember { mutableStateOf<Int?>(null) }
    var arbeitszeitNeu by remember { mutableStateOf("") }
    var kvKundenDialog by remember { mutableStateOf(false) }
    var unternehmerName by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(FIRMENNAME_KEY, "Markus Becker") ?: "Markus Becker") }
    var unternehmerStrasse by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(FIRMENSTRASSE_KEY, "") ?: "") }
    var unternehmerPlzOrt by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(FIRMENPLZORT_KEY, "") ?: "") }
    var unternehmerTelefon by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(FIRMENTELEFON_KEY, "+49 176 16712509") ?: "+49 176 16712509") }
    var unternehmerEmail by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(FIRMENEMAIL_KEY, "kuemmero@web.de") ?: "kuemmero@web.de") }
    var steuernummer by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(STEUERNUMMER_KEY, "") ?: "") }
    var auftragDetailIndex by remember { mutableStateOf<Int?>(null) }
    var auftragFormOffen by remember { mutableStateOf(false) }
    val listeState = rememberLazyListState()

    // Laufende Arbeitszeit
    var timerIndex by remember { mutableStateOf<Int?>(null) }
    var timerSekunden by remember { mutableStateOf(0L) }

    // Laufende Arbeitszeit nach App-Neustart automatisch wieder aufnehmen
    LaunchedEffect(Unit) {
        if (timerIndex == null) {
            val laufenderIndex = auftraege.indexOfFirst { it.arbeitsStart > 0L }
            if (laufenderIndex >= 0) {
                timerIndex = laufenderIndex
            }
        }
    }

    LaunchedEffect(timerIndex) {
        while (timerIndex != null) {
            val i = timerIndex ?: break
            val a = auftraege.getOrNull(i)
            timerSekunden = if (a != null && a.arbeitsStart > 0L) {
                ((System.currentTimeMillis() - a.arbeitsStart) / 1000L).coerceAtLeast(0L)
            } else {
                0L
            }
            delay(1000L)
        }
    }

    val feldFarben = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = KuemmeroMint,
        unfocusedContainerColor = KuemmeroMint,
        disabledContainerColor = KuemmeroMint,
        errorContainerColor = KuemmeroMint,
        focusedBorderColor = KuemmeroGreen,
        unfocusedBorderColor = Color(0xFF7A8A82),
        focusedLabelColor = KuemmeroGreen,
        unfocusedLabelColor = KuemmeroText
    )

    LaunchedEffect(bearbeiteIndex) {
        if (bearbeiteIndex != null) {
            listeState.animateScrollToItem(0)
        }
    }

    var auftragFuerPdf by remember { mutableStateOf<Auftrag?>(null) }
    var rechnungFuerIndex by remember { mutableStateOf<Int?>(null) }
    var rechnungNummerEditIndex by remember { mutableStateOf<Int?>(null) }
    var rechnungNummerEditText by remember { mutableStateOf("") }
    var rechnungScanIndex by remember { mutableStateOf<Int?>(null) }
    var rechnungScanUri by remember { mutableStateOf<Uri?>(null) }

    val rechnungScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { erfolgreich ->
        val uri = rechnungScanUri
        val index = rechnungScanIndex
        if (erfolgreich && uri != null && index != null) {
            val a = auftraege.getOrNull(index)
            if (a != null) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        context.contentResolver.update(uri, ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }, null, null)
                    }
                    speichereRechnungScan(context, a, uri)
                    android.widget.Toast.makeText(context, "Rechnung eingescannt und beim Auftrag gespeichert.", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            }
        } else if (uri != null) {
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        rechnungScanUri = null
        rechnungScanIndex = null
    }

    fun starteRechnungScan(index: Int) {
        val a = auftraege.getOrNull(index) ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "KÜMMERO-Rechnung-${a.rechnungsnummer.ifBlank { a.nummer }}-${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KÜMMERO")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            android.widget.Toast.makeText(context, "Kamera konnte nicht gestartet werden.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        rechnungScanIndex = index
        rechnungScanUri = uri
        rechnungScanLauncher.launch(uri)
    }

    var sicherungBestaetigung by remember { mutableStateOf(false) }
    var abschlusspruefungIndex by remember { mutableStateOf<Int?>(null) }
    val backupScope = rememberCoroutineScope()

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { selectedUri ->
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(BACKUP_URI_KEY, selectedUri.toString()).apply()
            backupScope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(selectedUri, "wt")?.use { out ->
                            out.write(backupText(context).toByteArray(Charsets.UTF_8))
                            out.flush()
                        } ?: throw Exception("Datei konnte nicht geöffnet werden")
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                if (result) {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putLong(BACKUP_LAST_SUCCESS_KEY, System.currentTimeMillis()).apply()
                    android.widget.Toast.makeText(context, "Sicherung gespeichert. Diese Datei wird künftig aktualisiert.", 0).show()
                } else {
                    android.widget.Toast.makeText(context, "Sicherung fehlgeschlagen", 1).show()
                }
            }
        }
    }
    // Vorhandene Backup-Datei auswählen und als feste KÜMMERO-Sicherung hinterlegen.
    // Dadurch wird bei einem gelöschten/ungültigen URI keine neue Datei mit (1), (2) usw. erzeugt.
    val backupDateiAuswaehlen = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }

                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(BACKUP_URI_KEY, it.toString())
                    .apply()

                backupScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openOutputStream(it, "wt")?.use { out ->
                                out.write(backupText(context).toByteArray(Charsets.UTF_8))
                                out.flush()
                            } ?: throw Exception("Datei konnte nicht zum Schreiben geöffnet werden")
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (result) {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putLong(BACKUP_LAST_SUCCESS_KEY, System.currentTimeMillis()).apply()
                        android.widget.Toast.makeText(
                            context,
                            "Sicherung gespeichert. Diese Datei wird künftig aktualisiert.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().remove(BACKUP_URI_KEY).apply()
                        android.widget.Toast.makeText(
                            context,
                            "Sicherung konnte nicht gespeichert werden.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(BACKUP_URI_KEY).apply()
                android.widget.Toast.makeText(
                    context,
                    "Sicherung konnte nicht gespeichert werden.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    val restoreBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.openFileOutput(BACKUP_PRE_RESTORE_FILE, Context.MODE_PRIVATE).use { out ->
                    out.write(backupText(context).toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                    ?: throw Exception("Datei konnte nicht gelesen werden")
                val obj = JSONObject(text)
                val rate = obj.optString("stundensatz", "42.00")
                val firmenNameBackup = obj.optString("firmenName", "Markus Becker")
                val firmenStrasseBackup = obj.optString("firmenStrasse", "")
                val firmenPlzOrtBackup = obj.optString("firmenPlzOrt", "")
                val firmenTelefonBackup = obj.optString("firmenTelefon", "+49 176 16712509")
                val firmenEmailBackup = obj.optString("firmenEmail", "kuemmero@web.de")
                val steuernummerBackup = obj.optString("steuernummer", "")
                val arr = obj.optJSONArray("auftraege") ?: JSONArray()
                val kundenArr = obj.optJSONArray("kunden") ?: JSONArray()
                val kvArr = obj.optJSONArray("kostenvoranschlaege") ?: JSONArray()
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(STUNDENSATZ_KEY, rate)
                    .putString(FIRMENNAME_KEY, firmenNameBackup)
                    .putString(FIRMENSTRASSE_KEY, firmenStrasseBackup)
                    .putString(FIRMENPLZORT_KEY, firmenPlzOrtBackup)
                    .putString(FIRMENTELEFON_KEY, firmenTelefonBackup)
                    .putString(FIRMENEMAIL_KEY, firmenEmailBackup)
                    .putString(STEUERNUMMER_KEY, steuernummerBackup)
                    .putString(AUFTRAEGE_KEY, arr.toString())
                    .putString(KUNDEN_KEY, kundenArr.toString())
                    .putString(KOSTENVORANSCHLAEGE_KEY, kvArr.toString()).commit()
                stundensatz = rate
                unternehmerName = firmenNameBackup
                unternehmerStrasse = firmenStrasseBackup
                unternehmerPlzOrt = firmenPlzOrtBackup
                unternehmerTelefon = firmenTelefonBackup
                unternehmerEmail = firmenEmailBackup
                steuernummer = steuernummerBackup
                auftraege = ladeAuftraege(context)
                kunden = ladeKunden(context)
                kostenvoranschlaege = ladeKostenvoranschlaege(context)
                auftragDetailIndex = null
                auftragFormOffen = false
                bearbeiteIndex = null
                kvFormOffen = false
                kvBearbeiteIndex = null
                timerIndex = null
                timerSekunden = 0L
                android.widget.Toast.makeText(context, "Daten wiederhergestellt. Sicherheitskopie des vorherigen Datenstands wurde erstellt.", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Wiederherstellung fehlgeschlagen", 1).show()
            }
        }
    }

    val fotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val neue = uris.map { it.toString() }
            if (fotoTyp == "Vorher") fotosVorher = (fotosVorher + neue).distinct()
            else fotosNachher = (fotosNachher + neue).distinct()
            android.widget.Toast.makeText(context, "${neue.size} Foto(s) hinzugefügt.", 0).show()
        }
    }

    val materialBonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Bei manchen Dateianbietern nicht verfügbar; die URI bleibt trotzdem nutzbar.
            }
            materialBonUri = it.toString()
            android.widget.Toast.makeText(context, "Kassenbon hinzugefügt.", 0).show()
        }
    }

    val kvFotoVorherLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            kvFotosVorher = (kvFotosVorher + uris.map { it.toString() }).distinct()
            android.widget.Toast.makeText(context, "${uris.size} Bild(er) vorher hinzugefügt.", 0).show()
        }
    }

    val datumJetzt = datumFormat.format(Date())

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let {
            val a = auftragFuerPdf
            val pdf = if (a != null) {
                erstellePdf(
                    context, nummer, datum, gueltigBis,
                    a.kunde, a.kundenStrasse, a.kundenOrt, a.leistung,
                    a.stunden, a.material, a.fahrt, a.stundensatz, a.unterschriftPfad, a.unterschriftDatum,
                    a.fotosVorher, a.fotosNachher
                )
            } else {
                erstellePdf(
                    context, nummer, datum, gueltigBis, kunde, strasse, ort, leistung,
                    zahl(stunden), zahl(material), zahl(fahrt), zahl(stundensatz, 42.0), unterschriftPfad, unterschriftDatum,
                    fotosVorher, fotosNachher
                )
            }
            context.contentResolver.openOutputStream(it)?.use { out -> pdf.writeTo(out) }
            pdf.close()
            auftragFuerPdf = null
        }
    }


    val rechnungLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let {
            val index = rechnungFuerIndex
            val a = index?.let { i -> auftraege.getOrNull(i) }
            if (a != null) {
                val rechnungsPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val rechnungsStrasse = rechnungsPrefs.getString(FIRMENSTRASSE_KEY, "") ?: ""
                val rechnungsPlzOrt = rechnungsPrefs.getString(FIRMENPLZORT_KEY, "") ?: ""
                val rechnungsSteuer = rechnungsPrefs.getString(STEUERNUMMER_KEY, "") ?: ""
                if (rechnungsStrasse.isBlank() || rechnungsPlzOrt.isBlank() || rechnungsSteuer.isBlank()) {
                    android.widget.Toast.makeText(context, "Bitte unter Mehr zuerst Straße, PLZ/Ort und Steuernummer eintragen.", android.widget.Toast.LENGTH_LONG).show()
                    rechnungFuerIndex = null
                    return@rememberLauncherForActivityResult
                }
                try {
                    val rechnungsnummer = naechsteRechnungsnummer(context)
                    val rechnungsdatum = datumFormat.format(Date())
                    val faelligCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 14) }
                    val faelligAm = datumFormat.format(faelligCal.time)
                    val pdf = erstelleRechnungPdf(
                        context,
                        rechnungsnummer,
                        rechnungsdatum,
                        faelligAm,
                        a.leistungsdatum.ifBlank { a.terminDatum.ifBlank { a.datum } },
                        a.kunde,
                        a.kundenStrasse,
                        a.kundenOrt,
                        a.leistung,
                        a.stunden,
                        a.material,
                        a.fahrt,
                        a.stundensatz,
                        a.unterschriftPfad,
                        a.unterschriftDatum,
                        a.fotosVorher, a.fotosNachher, a.erstellungskosten
                    )
                    context.contentResolver.openOutputStream(it)?.use { out -> pdf.writeTo(out) }
                    pdf.close()
                    speichereRechnungsnummer(context, rechnungsnummer)
                    auftraege = auftraege.toMutableList().apply {
                        set(
                            index,
                            a.copy(
                                status = "Abgerechnet",
                                rechnungsnummer = rechnungsnummer,
                                rechnungsdatum = rechnungsdatum,
                                faelligAm = faelligAm
                            )
                        )
                    }
                    speichereAuftraege(context, auftraege)
                    android.widget.Toast.makeText(context, "Rechnung gespeichert: $rechnungsnummer", 0).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Rechnung konnte nicht erstellt werden.", 1).show()
                }
            }
            rechnungFuerIndex = null
        }
    }

    val arbeitsstunden = zahl(stunden)
    val materialKosten = zahl(material)
    val fahrtKosten = zahl(fahrt)
    val rate = zahl(stundensatz, 42.0)
    val gesamt = gesamtbetrag(arbeitsstunden, materialKosten, fahrtKosten, rate)
    val umsatz = auftraege.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) }

    val heuteText = datumFormat.format(Date())
    val termineHeute = auftraege.filter { it.terminDatum == heuteText }
        .sortedBy { it.terminUhrzeit }
    val offeneAuftraege = auftraege.count { it.status != "Abgerechnet" }
    val offeneZahlungen = auftraege.filter { it.zahlungsstatus != "Bezahlt" }
    val offeneZahlungSumme = offeneZahlungen.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) }
    val naechsteTermine = auftraege.filter { it.terminDatum.isNotBlank() }
        .sortedWith(compareBy<Auftrag> {
            try { datumFormat.parse(it.terminDatum)?.time ?: Long.MAX_VALUE } catch (_: Exception) { Long.MAX_VALUE }
        }.thenBy { it.terminUhrzeit })
        .take(8)
    val naechsterTermin = naechsteTermine.firstOrNull { it.terminDatum != heuteText }
    val abgearbeiteteAuftraege = auftraege.count { it.status == "Erledigt" || it.status == "Abgerechnet" }

    if (sicherungBestaetigung) {
        val vorhandeneSicherung = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(BACKUP_URI_KEY, null)
            ?.isNotBlank() == true
        AlertDialog(
            onDismissRequest = { sicherungBestaetigung = false },
            title = { Text("Sicherung bestätigen") },
            text = {
                Text(
                    if (vorhandeneSicherung)
                        "Soll die bestehende KÜMMERO-Sicherung jetzt aktualisiert werden?"
                    else
                        "Soll jetzt eine KÜMMERO-Sicherung gespeichert werden?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sicherungBestaetigung = false
                    backupDateiAuswaehlen.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                }) { Text("Ja, sichern") }
            },
            dismissButton = {
                TextButton(onClick = { sicherungBestaetigung = false }) { Text("Abbrechen") }
            }
        )
    }

    if (abschlusspruefungIndex != null) {
        val idx = abschlusspruefungIndex
        val a = idx?.let { auftraege.getOrNull(it) }
        if (a != null) {
            val arbeitszeitOk = a.stunden > 0.0 || a.arbeitsSekunden > 0L
            val kundeOk = a.kunde.isNotBlank() && a.kundenStrasse.isNotBlank() && a.kundenOrt.isNotBlank()
            val leistungOk = a.leistung.isNotBlank()
            val betragOk = gesamtbetrag(a.stunden, a.material, a.fahrt, a.stundensatz, a.erstellungskosten) > 0.0
            val leistungsdatumOk = a.leistungsdatum.isNotBlank() || a.terminDatum.isNotBlank() || a.datum.isNotBlank()

            AlertDialog(
                onDismissRequest = { abschlusspruefungIndex = null },
                title = { Text("Auftrag abschließen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Bitte kurz prüfen, bevor der Auftrag als erledigt markiert wird.", fontWeight = FontWeight.SemiBold)
                        Text(if (kundeOk) "✓ Kundendaten vollständig" else "⚠ Kundendaten prüfen", color = if (kundeOk) KuemmeroGreen else KuemmeroError)
                        Text(if (leistungOk) "✓ Leistungsbeschreibung vorhanden" else "⚠ Leistungsbeschreibung fehlt", color = if (leistungOk) KuemmeroGreen else KuemmeroError)
                        Text(if (arbeitszeitOk) "✓ Arbeitszeit erfasst" else "⚠ Keine Arbeitszeit erfasst", color = if (arbeitszeitOk) KuemmeroGreen else KuemmeroError)
                        Text(if (betragOk) "✓ Rechnungsbetrag vorhanden" else "⚠ Rechnungsbetrag ist 0,00 €", color = if (betragOk) KuemmeroGreen else KuemmeroError)
                        Text(if (leistungsdatumOk) "✓ Leistungsdatum vorhanden" else "⚠ Leistungsdatum fehlt", color = if (leistungsdatumOk) KuemmeroGreen else KuemmeroError)
                        Text("Fotos und Unterschrift sind optional und können je nach Auftrag ergänzt werden.", style = MaterialTheme.typography.bodySmall, color = KuemmeroText)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        auftraege = auftraege.toMutableList().apply {
                            set(idx, a.copy(status = "Erledigt"))
                        }
                        speichereAuftraege(context, auftraege)
                        abschlusspruefungIndex = null
                        android.widget.Toast.makeText(context, "Auftrag als erledigt markiert.", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Trotzdem abschließen") }
                },
                dismissButton = {
                    TextButton(onClick = { abschlusspruefungIndex = null }) { Text("Zurück") }
                }
            )
        } else {
            abschlusspruefungIndex = null
        }
    }

    if (arbeitszeitAendernIndex != null) {
        AlertDialog(
            onDismissRequest = { arbeitszeitAendernIndex = null },
            title = { Text("Arbeitszeit ändern") },
            text = {
                OutlinedTextField(
                    value = arbeitszeitNeu,
                    onValueChange = { arbeitszeitNeu = it },
                    label = { Text("Arbeitszeit in Stunden") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = feldFarben
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val idx = arbeitszeitAendernIndex
                    if (idx != null) {
                        val stundenNeu = zahl(arbeitszeitNeu)
                        val aktualisiert = auftraege[idx].copy(
                            arbeitsSekunden = (stundenNeu * 3600.0).toLong(),
                            arbeitsStart = 0L,
                            arbeitsEnde = System.currentTimeMillis(),
                            arbeitszeitUebernommen = false
                        )
                        auftraege = auftraege.toMutableList().apply { set(idx, aktualisiert) }
                        speichereAuftraege(context, auftraege)
                    }
                    arbeitszeitAendernIndex = null
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { arbeitszeitAendernIndex = null }) { Text("Abbrechen") } }
        )
    }

    if (kvLoeschIndex != null) {
        AlertDialog(
            onDismissRequest = { kvLoeschIndex = null },
            title = { Text("Kostenvoranschlag löschen?") },
            text = { Text("Soll der Kostenvoranschlag wirklich gelöscht werden?") },
            confirmButton = {
                TextButton(onClick = {
                    val idx = kvLoeschIndex
                    if (idx != null) {
                        val list = kostenvoranschlaege.toMutableList()
                        if (idx in list.indices) {
                            list.removeAt(idx)
                            kostenvoranschlaege = list
                            speichereKostenvoranschlaege(context, list)
                        }
                    }
                    kvLoeschIndex = null
                }, colors = ButtonDefaults.textButtonColors(contentColor = KuemmeroError)) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { kvLoeschIndex = null }) { Text("Abbrechen") } }
        )
    }

    if (rechnungNummerEditIndex != null) {
        AlertDialog(
            onDismissRequest = { rechnungNummerEditIndex = null },
            title = { Text("Rechnungsnummer korrigieren") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Hier kann eine falsch vergebene Rechnungsnummer korrigiert werden.")
                    Text(
                        "Wichtig: Eine bereits an den Kunden ausgegebene Rechnung nicht einfach überschreiben. In diesem Fall eine berichtigte Rechnung erstellen und die ursprüngliche Rechnung nachvollziehbar aufbewahren.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KuemmeroText
                    )
                    OutlinedTextField(
                        value = rechnungNummerEditText,
                        onValueChange = { rechnungNummerEditText = it },
                        label = { Text("Rechnungsnummer") },
                        singleLine = true,
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val index = rechnungNummerEditIndex
                    val neu = rechnungNummerEditText.trim()
                    if (index == null || neu.isBlank()) {
                        android.widget.Toast.makeText(context, "Bitte eine Rechnungsnummer eingeben.", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val doppelt = auftraege.withIndex().any { it.index != index && it.value.rechnungsnummer.equals(neu, ignoreCase = true) }
                        if (doppelt) {
                            android.widget.Toast.makeText(context, "Diese Rechnungsnummer ist bereits vergeben.", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            val alt = auftraege.getOrNull(index)
                            if (alt != null) {
                                auftraege = auftraege.toMutableList().apply {
                                    set(index, alt.copy(rechnungsnummer = neu))
                                }
                                speichereAuftraege(context, auftraege)
                                synchronisiereRechnungsnummerCounter(context, neu)
                                rechnungNummerEditIndex = null
                                android.widget.Toast.makeText(context, "Rechnungsnummer geändert: $neu", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { rechnungNummerEditIndex = null }) { Text("Abbrechen") }
            }
        )
    }

    if (kundenDialog) {
        AlertDialog(
            onDismissRequest = { kundenDialog = false },
            title = { Text("Kundenverwaltung") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kunden.isEmpty()) {
                        Text("Noch keine Kunden gespeichert.")
                    } else {
                        kunden.forEach { k ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = KuemmeroMint),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            kundenDialog = false
                                            kundenAkteName = k.name
                                            kunde = k.name
                                            strasse = k.adresse
                                            ort = k.ort
                                            kundenDialog = false
                                        }
                                        .padding(12.dp)
                                ) {
                                    Text(k.name, fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                                    if (k.adresse.isNotBlank()) Text(k.adresse)
                                    if (k.ort.isNotBlank()) Text(k.ort)
                                    if (k.telefon.isNotBlank()) Text("Tel.: ${k.telefon}")
                                    if (k.email.isNotBlank()) Text(k.email)
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            kundenDialog = false
                            neuerKundenName = ""
                            neuerKundenAdresse = ""
                            neuerKundenOrt = ""
                            neuerKundenTelefon = ""
                            neuerKundenEmail = ""
                            neuerKundeDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ Neuer Kunde")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { kundenDialog = false }) { Text("Schließen") }
            }
        )
    }

    if (neuerKundeDialog) {
        AlertDialog(
            onDismissRequest = { neuerKundeDialog = false },
            title = { Text("Neuen Kunden anlegen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        neuerKundenName,
                        { neuerKundenName = it },
                        label = { Text("Kunde") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        neuerKundenAdresse,
                        { neuerKundenAdresse = it },
                        label = { Text("Adresse") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        neuerKundenOrt,
                        { neuerKundenOrt = it },
                        label = { Text("PLZ und Ort") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        neuerKundenTelefon,
                        { neuerKundenTelefon = it },
                        label = { Text("Telefon") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    OutlinedTextField(
                        neuerKundenEmail,
                        { neuerKundenEmail = it },
                        label = { Text("E-Mail") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (neuerKundenName.isBlank()) {
                        android.widget.Toast.makeText(context, "Bitte Kundennamen eingeben.", 0).show()
                    } else {
                        val k = Kunde(
                            neuerKundenName.trim(),
                            neuerKundenAdresse.trim(),
                            neuerKundenOrt.trim(),
                            neuerKundenTelefon.trim(),
                            neuerKundenEmail.trim()
                        )
                        speichereOderAktualisiereKunde(context, k)
                        kunden = ladeKunden(context)
                        kunde = k.name
                        strasse = k.adresse
                        ort = k.ort
                        neuerKundeDialog = false
                        android.widget.Toast.makeText(context, "Kunde gespeichert.", 0).show()
                    }
                }) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { neuerKundeDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    if (fotoVorschauUri != null) {
        val uri = fotoVorschauUri!!
        val bitmap = remember(uri) { ladeFotoBitmap(context, uri) }
        AlertDialog(
            onDismissRequest = { fotoVorschauUri = null },
            title = { Text("Auftragsfoto") },
            text = {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Auftragsfoto",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("Foto konnte nicht geladen werden.")
                }
            },
            confirmButton = {
                TextButton(onClick = { fotoVorschauUri = null }) { Text("Schließen") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        fotosVorher = fotosVorher.filterNot { it == uri }
                        fotosNachher = fotosNachher.filterNot { it == uri }
                        fotoVorschauUri = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = KuemmeroError)
                ) { Text("Löschen") }
            }
        )
    }

    if (unterschriftDialog) {
        AlertDialog(
            onDismissRequest = { unterschriftDialog = false },
            title = { Text("Kunden-Unterschrift") },
            text = {
                Column {
                    Text("Bitte hier unterschreiben:", color = KuemmeroText)
                    Spacer(Modifier.height(8.dp))
                    var pathPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
                    var signBoxWidth by remember { mutableStateOf(1f) }
                    var signBoxHeight by remember { mutableStateOf(1f) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .border(1.dp, KuemmeroGreen, RoundedCornerShape(12.dp))
                            .onSizeChanged {
                                signBoxWidth = it.width.toFloat().coerceAtLeast(1f)
                                signBoxHeight = it.height.toFloat().coerceAtLeast(1f)
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset -> pathPoints = pathPoints + offset },
                                    onDrag = { change, _ -> pathPoints = pathPoints + change.position },
                                    onDragEnd = {}
                                )
                            }
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            if (pathPoints.size > 1) {
                                val path = Path().apply {
                                    moveTo(pathPoints.first().x, pathPoints.first().y)
                                    pathPoints.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(path, KuemmeroGreen, style = Stroke(width = 4f))
                            }
                        }
                    }
                    TextButton(onClick = { pathPoints = emptyList() }) { Text("Unterschrift löschen") }
                    Button(
                        onClick = {
                            if (pathPoints.size < 2) {
                                android.widget.Toast.makeText(context, "Bitte zuerst unterschreiben.", 0).show()
                                return@Button
                            }
                            val file = java.io.File(context.filesDir, "unterschrift_${System.currentTimeMillis()}.png")
                            val bitmapWidth = 1200
                            val bitmapHeight = 500
                            val bitmap = android.graphics.Bitmap.createBitmap(bitmapWidth, bitmapHeight, android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bitmap)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.rgb(8,127,62)
                                strokeWidth = 10f
                                style = android.graphics.Paint.Style.STROKE
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                                isAntiAlias = true
                            }
                            val scaleX = (bitmapWidth - 80f) / signBoxWidth
                            val scaleY = (bitmapHeight - 80f) / signBoxHeight
                            val path = android.graphics.Path()
                            path.moveTo(40f + pathPoints.first().x * scaleX, 40f + pathPoints.first().y * scaleY)
                            pathPoints.drop(1).forEach { point ->
                                path.lineTo(40f + point.x * scaleX, 40f + point.y * scaleY)
                            }
                            canvas.drawPath(path, paint)
                            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            unterschriftPfad = file.absolutePath
                            unterschriftDatum = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
                            unterschriftDialog = false
                            android.widget.Toast.makeText(context, "Unterschrift gespeichert.", 0).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Unterschrift übernehmen") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { unterschriftDialog = false }) { Text("Abbrechen") } }
        )
    }

    kundenAkteName?.let { name ->
        val kundeAkte = kunden.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val kundenAuftraege = auftraege.filter { it.kunde.equals(name, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { kundenAkteName = null },
            title = { Text("Kundenakte") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                    if (kundeAkte?.adresse?.isNotBlank() == true) Text("Adresse: ${kundeAkte.adresse}")
                    if (kundeAkte?.ort?.isNotBlank() == true) Text("Ort: ${kundeAkte.ort}")
                    if (kundeAkte?.telefon?.isNotBlank() == true) Text("Telefon: ${kundeAkte.telefon}")
                    if (kundeAkte?.email?.isNotBlank() == true) Text("E-Mail: ${kundeAkte.email}")
                    HorizontalDivider()
                    Text("Aufträge: ${kundenAuftraege.size}", fontWeight = FontWeight.Bold)
                    Text("Umsatz: ${euro(kundenAuftraege.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) })}")
                    val offen = kundenAuftraege.filter { it.zahlungsstatus != "Bezahlt" }
                    Text("Offene Zahlungen: ${euro(offen.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) })}", color = if (offen.isEmpty()) KuemmeroGreen else KuemmeroError, fontWeight = FontWeight.Bold)
                    val kundenKVs = kostenvoranschlaege.filter { it.kunde.equals(name, ignoreCase = true) }
                    Text("Kostenvoranschläge: ${kundenKVs.size}", fontWeight = FontWeight.Bold)
                    val rechnungen = kundenAuftraege.filter { it.rechnungsnummer.isNotBlank() }
                    Text("Rechnungen: ${rechnungen.size}", fontWeight = FontWeight.Bold)
                    rechnungen.takeLast(5).reversed().forEach { r ->
                        Text(
                            "${r.rechnungsnummer} · ${euro(gesamtbetrag(r.stunden, r.material, r.fahrt, r.stundensatz, r.erstellungskosten))} · ${if (r.zahlungsstatus == "Bezahlt") "Bezahlt" else "Offen"}",
                            color = if (r.zahlungsstatus == "Bezahlt") KuemmeroGreen else KuemmeroError,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { kundenAkteName = null }) { Text("Schließen") } }
        )
    }

    if (kalenderOffen) {
        AlertDialog(
            onDismissRequest = { kalenderOffen = false },
            title = { Text("📅 Termine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (naechsteTermine.isEmpty()) {
                        Text("Noch keine Termine eingetragen.")
                    } else {
                        naechsteTermine.forEach { a ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = KuemmeroMint),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("${a.terminDatum}${if (a.terminUhrzeit.isNotBlank()) " · ${a.terminUhrzeit}" else ""}", fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                                    Text(a.kunde, fontWeight = FontWeight.SemiBold)
                                    if (a.leistung.isNotBlank()) Text(a.leistung)
                                    if (a.kundenStrasse.isNotBlank() || a.kundenOrt.isNotBlank()) Text(listOf(a.kundenStrasse, a.kundenOrt).filter { it.isNotBlank() }.joinToString(", "))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { kalenderOffen = false }) { Text("Schließen") } }
        )
    }

    loeschIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { loeschIndex = null },
            title = { Text("Auftrag löschen?") },
            text = { Text("Soll der Auftrag wirklich gelöscht werden?") },
            confirmButton = {
                TextButton(onClick = {
                    auftraege = auftraege.toMutableList().apply { removeAt(index) }
                    speichereAuftraege(context, auftraege)
                    timerIndex = null
                    timerSekunden = 0L
                    // Nach dem Löschen immer zurück zur Auftragsübersicht.
                    auftragDetailIndex = null
                    auftragFormOffen = false
                    bearbeiteIndex = null
                    loeschIndex = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { loeschIndex = null }) { Text("Abbrechen") } }
        )
    }

    MaterialTheme(colorScheme = KuemmeroColors) {
        if (kvKundenDialog) {
        AlertDialog(
            onDismissRequest = { kvKundenDialog = false },
            title = { Text("Kunde auswählen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kunden.isEmpty()) {
                        Text("Noch keine Kunden gespeichert.")
                    } else {
                        kunden.forEach { k ->
                            OutlinedButton(
                                onClick = {
                                    kvKunde = k.name
                                    kvStrasse = k.adresse
                                    kvOrt = k.ort
                                    kvKundenDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.5.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroText)
                            ) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text(k.name, fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                                    if (k.adresse.isNotBlank() || k.ort.isNotBlank()) Text(listOf(k.adresse, k.ort).filter { it.isNotBlank() }.joinToString(", "), color = KuemmeroText)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { kvKundenDialog = false; neuerKundenName = kvKunde; neuerKundenAdresse = kvStrasse; neuerKundenOrt = kvOrt; neuerKundeDialog = true }) {
                    Text("+ Neuer Kunde")
                }
            },
            dismissButton = { TextButton(onClick = { kvKundenDialog = false }) { Text("Abbrechen") } }
        )
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Canvas(modifier = Modifier.size(54.dp)) {
                                val w = size.width
                                val h = size.height
                                val stroke = Stroke(width = 3.2f)
                                val roof = Path().apply {
                                    moveTo(w * 0.08f, h * 0.48f)
                                    lineTo(w * 0.50f, h * 0.12f)
                                    lineTo(w * 0.92f, h * 0.48f)
                                }
                                drawPath(roof, Color.White, style = stroke)
                                drawLine(Color.White, Offset(w * 0.22f, h * 0.38f), Offset(w * 0.22f, h * 0.82f), strokeWidth = 3.2f)
                                drawLine(Color.White, Offset(w * 0.78f, h * 0.38f), Offset(w * 0.78f, h * 0.82f), strokeWidth = 3.2f)
                                drawLine(Color.White, Offset(w * 0.22f, h * 0.82f), Offset(w * 0.78f, h * 0.82f), strokeWidth = 3.2f)
                                val leaf = Path().apply {
                                    moveTo(w * 0.50f, h * 0.72f)
                                    cubicTo(w * 0.35f, h * 0.62f, w * 0.34f, h * 0.48f, w * 0.48f, h * 0.50f)
                                    cubicTo(w * 0.63f, h * 0.52f, w * 0.62f, h * 0.65f, w * 0.50f, h * 0.72f)
                                }
                                drawPath(leaf, Color.White, style = stroke)
                                drawLine(Color.White, Offset(w * 0.50f, h * 0.72f), Offset(w * 0.50f, h * 0.90f), strokeWidth = 3.2f)
                            }
                            Column {
                                Text("KÜMMERO", fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    "Haus & Alltag – wir kümmern uns.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFFD9F2E3)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = KuemmeroGreen,
                        titleContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                NavigationBar(containerColor = KuemmeroSurface) {
                    listOf(
                        Triple("Heute", "⌂", "Heute"),
                        Triple("Aufträge", "▤", "Aufträge"),
                        Triple("Kostenvoranschläge", "€", "Kostenvoranschläge"),
                        Triple("Kunden", "♙", "Kunden"),
                        Triple("Mehr", "⋯", "Mehr")
                    ).forEach { (label, iconText, page) ->
                        NavigationBarItem(
                            selected = hauptseite == page,
                            onClick = { hauptseite = page },
                            icon = { Text(iconText, fontSize = 20.sp) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = KuemmeroGreen,
                                selectedTextColor = KuemmeroGreen,
                                indicatorColor = KuemmeroMint,
                                unselectedIconColor = KuemmeroText,
                                unselectedTextColor = KuemmeroText
                            )
                        )
                    }
                }
            },
            containerColor = KuemmeroBackground
        ) { padding ->
            val gefilterteAuftraege = auftraege.mapIndexed { index, auftrag -> index to auftrag }
            .filter { (_, a) ->
                val suche = auftragsSuche.trim().lowercase(Locale.GERMANY)
                val passtSuche = suche.isBlank() || listOf(
                    a.kunde, a.nummer, a.datum, a.kundenStrasse, a.kundenOrt, a.leistung, a.status
                ).any { it.lowercase(Locale.GERMANY).contains(suche) }
                val passtStatus = statusFilter == "Alle" || a.status == statusFilter
                val passtZahlung = !zahlungsFilterOffen || a.zahlungsstatus != "Bezahlt"
                passtSuche && passtStatus && passtZahlung
            }

        if (hauptseite == "Aufträge") {
            LazyColumn(
                state = listeState,
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (auftragFormOffen) {
                    item {
                        Text(
                            if (bearbeiteIndex != null) "Auftrag bearbeiten" else "Neuer Auftrag",
                            style = MaterialTheme.typography.headlineSmall,
                            color = KuemmeroGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (auftragFormOffen) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = KuemmeroGreen),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Heute · $heuteText", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Termine", color = Color.White)
                                    Text("${termineHeute.size}", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Offene Aufträge", color = Color.White)
                                    Text("$offeneAuftraege", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Offen €", color = Color.White)
                                    Text(euro(offeneZahlungSumme), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (termineHeute.isEmpty()) {
                                Text("Heute keine Termine.", color = Color.White)
                            } else {
                                termineHeute.take(3).forEach { a ->
                                    Text("${if (a.terminUhrzeit.isBlank()) "" else a.terminUhrzeit + " · "}${a.kunde}${if (a.leistung.isBlank()) "" else " – " + a.leistung}", color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            OutlinedButton(
                                onClick = { kalenderOffen = true },
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.5.dp, Color.White),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) { Text("📅 Alle Termine anzeigen") }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Haus & Alltag – wir kümmern uns.",
                                style = MaterialTheme.typography.titleMedium,
                                color = KuemmeroGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("Hausmeisterservice & Seniorenbetreuung", color = KuemmeroText)
                            Text("Markus Becker · 58675 Hemer", color = KuemmeroText)
                            Text("E-Mail: kuemmero@web.de", color = KuemmeroText)
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { kundenDialog = true },
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            shape = RoundedCornerShape(26.dp),
                            border = BorderStroke(2.dp, KuemmeroGreen),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                        ) {
                            Text("Kunden auswählen", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                neuerKundenName = kunde
                                neuerKundenAdresse = strasse
                                neuerKundenOrt = ort
                                neuerKundenTelefon = ""
                                neuerKundenEmail = ""
                                neuerKundeDialog = true
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            shape = RoundedCornerShape(26.dp),
                            border = BorderStroke(2.dp, KuemmeroGreen),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                        ) {
                            Text("+ Neuer Kunde", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        nummer, { nummer = it },
                        label = { Text("Angebotsnummer") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        strasse, { strasse = it },
                        label = { Text("Adresse") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        datum, { datum = it },
                        label = { Text("Datum") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        leistungsdatum, { leistungsdatum = it },
                        label = { Text("Leistungsdatum") },
                        placeholder = { Text("TT.MM.JJJJ") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        gueltigBis, { gueltigBis = it },
                        label = { Text("Gültig bis") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        kunde, { kunde = it },
                        label = { Text("Kunde") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        ort, { ort = it },
                        label = { Text("PLZ und Ort") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        leistung, { leistung = it },
                        label = { Text("Leistung") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                    leistungsumfangHinweis(leistung)?.let { hinweis ->
                        Text(
                            "⚠ $hinweis",
                            color = KuemmeroError,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        stunden, { stunden = it },
                        label = { Text("Arbeitsstunden") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        material, { material = it },
                        label = { Text("Material (€)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { materialBonLauncher.launch(arrayOf("image/*", "application/pdf")) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(26.dp),
                            border = BorderStroke(2.dp, KuemmeroGreen),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                        ) {
                            Text(
                                if (materialBonUri.isBlank()) "🧾 Kassenbon zu Material hinzufügen" else "✓ Kassenbon zum Material vorhanden",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (materialBonUri.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (materialBonUri.lowercase(Locale.GERMANY).contains(".pdf")) {
                                    Text("🧾 Material-Kassenbon: PDF", modifier = Modifier.weight(1f), color = KuemmeroText)
                                } else {
                                    FotoVorschau(
                                        context,
                                        materialBonUri,
                                        { fotoVorschauUri = materialBonUri },
                                        { materialBonUri = "" }
                                    )
                                    Text("Material-Kassenbon", modifier = Modifier.weight(1f), color = KuemmeroText)
                                }
                                TextButton(
                                    onClick = { materialBonUri = "" },
                                    colors = ButtonDefaults.textButtonColors(contentColor = KuemmeroError)
                                ) { Text("Entfernen") }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        fahrt, { fahrt = it },
                        label = { Text("Fahrtkosten (€)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        stundensatz,
                        {
                            stundensatz = it
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                                .putString(STUNDENSATZ_KEY, it).apply()
                        },
                        label = { Text("Stundensatz (€ / Stunde)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Text("Auftragsstatus", fontWeight = FontWeight.Bold, color = KuemmeroText)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Offen", "In Bearbeitung", "Erledigt", "Abgerechnet").forEach { option ->
                            val aktiv = status == option
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clickable { status = option },
                                shape = RoundedCornerShape(14.dp),
                                color = if (aktiv) KuemmeroGreen else KuemmeroMint,
                                border = BorderStroke(1.5.dp, if (aktiv) KuemmeroGreen else Color(0xFF7A8A82))
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        option,
                                        color = if (aktiv) Color.White else KuemmeroText,
                                        fontWeight = if (aktiv) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    KlappBereich("📅 Termin", terminBereichOffen, { terminBereichOffen = !terminBereichOffen }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(terminDatum, { terminDatum = it }, label = { Text("Datum") }, placeholder = { Text(datumJetzt) }, colors = feldFarben, modifier = Modifier.weight(1f))
                            OutlinedTextField(terminUhrzeit, { terminUhrzeit = it }, label = { Text("Uhrzeit") }, placeholder = { Text("09:00") }, colors = feldFarben, modifier = Modifier.weight(1f))
                        }
                    }
                }

                item {
                    KlappBereich("📝 Notiz zum Auftrag", notizBereichOffen, { notizBereichOffen = !notizBereichOffen }) {
                        OutlinedTextField(notiz, { notiz = it }, label = { Text("Notiz zum Auftrag") }, minLines = 3, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                    }
                }

                item {
                    KlappBereich("📷 Auftragsfotos (${fotosVorher.size + fotosNachher.size})", fotosBereichOffen, { fotosBereichOffen = !fotosBereichOffen }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { fotoTyp = "Vorher"; fotoLauncher.launch("image/*") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("📷 Vorher (${fotosVorher.size})") }
                            OutlinedButton(onClick = { fotoTyp = "Nachher"; fotoLauncher.launch("image/*") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("📷 Nachher (${fotosNachher.size})") }
                        }
                        if (fotosVorher.isNotEmpty()) {
                            Text("Vorher-Fotos", fontWeight = FontWeight.Bold, color = KuemmeroText)
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fotosVorher.forEach { uri -> FotoVorschau(context, uri, { fotoVorschauUri = uri }, { fotosVorher = fotosVorher.filterNot { it == uri } }) }
                            }
                        }
                        if (fotosNachher.isNotEmpty()) {
                            Text("Nachher-Fotos", fontWeight = FontWeight.Bold, color = KuemmeroText)
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fotosNachher.forEach { uri -> FotoVorschau(context, uri, { fotoVorschauUri = uri }, { fotosNachher = fotosNachher.filterNot { it == uri } }) }
                            }
                        }
                    }
                }

                item {
                    KlappBereich("✍ Kunden-Unterschrift", unterschriftBereichOffen, { unterschriftBereichOffen = !unterschriftBereichOffen }) {
                        OutlinedButton(onClick = { unterschriftDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) {
                            Text(if (unterschriftPfad.isBlank()) "✍ Kunden-Unterschrift aufnehmen" else "✓ Unterschrift vorhanden${if (unterschriftDatum.isBlank()) "" else " · $unterschriftDatum"}", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item {
                    Text("Aktueller Gesamtbetrag: ${euro(gesamt)}", style = MaterialTheme.typography.headlineSmall)
                }

                item {
                    Button(
                        onClick = {
                            if (kunde.isBlank()) {
                                android.widget.Toast.makeText(context, "Bitte Kundennamen eingeben.", 0).show()
                            } else {
                                speichereOderAktualisiereKunde(
                                    context,
                                    Kunde(kunde.trim(), strasse.trim(), ort.trim())
                                )
                                kunden = ladeKunden(context)
                                val a = Auftrag(
                                    nummer.trim(), datum.trim(), gueltigBis.trim(),
                                    kunde.trim(), strasse.trim(), ort.trim(), leistung.trim(),
                                    arbeitsstunden, materialKosten, materialBonUri, fahrtKosten, rate, status, zahlungsstatus, bezahltAm,
                                    terminDatum.trim(), terminUhrzeit.trim(), notiz.trim(), fotosVorher, fotosNachher, unterschriftPfad, unterschriftDatum,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.rechnungsnummer } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.rechnungsdatum } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.faelligAm } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.arbeitsStart } ?: 0L,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.arbeitsEnde } ?: 0L,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.arbeitsSekunden } ?: 0L,
                                    erstellungskosten = bearbeiteIndex?.let { auftraege.getOrNull(it)?.erstellungskosten } ?: 0.0,
                                    leistungsdatum = leistungsdatum.trim().ifBlank { datum.trim() }
                                )
                                val index = bearbeiteIndex
                                if (index != null) {
                                    auftraege = auftraege.toMutableList().apply { set(index, a) }
                                    speichereAuftraege(context, auftraege)
                                    bearbeiteIndex = null
                                    android.widget.Toast.makeText(context, "Auftrag geändert.", 0).show()
                                } else {
                                    auftraege = auftraege + a
                                    speichereAuftraege(context, auftraege)
                                    android.widget.Toast.makeText(context, "Auftrag gespeichert.", 0).show()
                                }
                                auftragFormOffen = false
                                leistungsdatum = datumFormat.format(Date())
                                kunde = ""
                                strasse = ""
                                ort = ""
                                leistung = ""
                                stunden = ""
                                material = ""
                                materialBonUri = ""
                                fahrt = ""
                                status = "Offen"
                                zahlungsstatus = "Offen"
                                bezahltAm = ""
                                terminDatum = ""
                                terminUhrzeit = ""
                                notiz = ""
                                fotosVorher = emptyList()
                                fotosNachher = emptyList()
                                unterschriftPfad = ""
                                unterschriftDatum = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                    ) {
                        Text(
                            if (bearbeiteIndex != null) "Änderungen speichern" else "Auftrag speichern",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (bearbeiteIndex != null) {
                    item {
                        OutlinedButton(
                            onClick = {
                                bearbeiteIndex = null
                                auftragFormOffen = false
                                kunde = ""
                                strasse = ""
                                ort = ""
                                leistung = ""
                                stunden = ""
                                material = ""
                                fahrt = ""
                                status = "Offen"
                                zahlungsstatus = "Offen"
                                bezahltAm = ""
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                        ) {
                            Text("Bearbeiten abbrechen", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            if (kunde.isBlank()) {
                                android.widget.Toast.makeText(context, "Bitte Kundennamen eingeben.", 0).show()
                            } else {
                                pdfLauncher.launch("KÜMMERO-Angebot-$nummer.pdf")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)
                    ) {
                        Text("PDF-Angebot erstellen", fontWeight = FontWeight.Bold)
                    }
                }

                item {
                    KlappBereich(
                        "💾 Sicherung",
                        sicherungBereichOffen,
                        { sicherungBereichOffen = !sicherungBereichOffen },
                    ) {
                        OutlinedButton(onClick = { sicherungBestaetigung = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("Sicherung speichern / aktualisieren", fontWeight = FontWeight.SemiBold) }
                        Button(onClick = { restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)) { Text("Daten wiederherstellen", fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = { sicherungBestaetigung = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(26.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("Sicherung jetzt aktualisieren", fontWeight = FontWeight.SemiBold) }
                    }
                }

                item { HorizontalDivider() }
                item {
                    Text(
                        "Übersicht",
                        style = MaterialTheme.typography.headlineSmall,
                        color = KuemmeroGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Gespeicherte", color = KuemmeroText)
                                Text("Aufträge", color = KuemmeroText)
                                Text(
                                    "${auftraege.size}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = KuemmeroGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Gesamtumsatz", color = KuemmeroText)
                                Text("gespeicherter Aufträge", color = KuemmeroText)
                                Text(
                                    euro(umsatz),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = KuemmeroGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                item {
                    val offeneAuftraege = auftraege.filter { it.zahlungsstatus != "Bezahlt" }
                    val offeneSumme = offeneAuftraege.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                zahlungsFilterOffen = true
                                statusFilter = "Alle"
                                auftragsSuche = ""
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (zahlungsFilterOffen) KuemmeroMint else KuemmeroSurface
                        ),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(2.dp, if (zahlungsFilterOffen) KuemmeroGreen else KuemmeroGreenLight)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Offene Zahlungen",
                                    color = KuemmeroText,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${offeneAuftraege.size} Rechnung${if (offeneAuftraege.size == 1) "" else "en"} offen",
                                    color = KuemmeroText
                                )
                            }
                            Text(
                                euro(offeneSumme),
                                style = MaterialTheme.typography.titleLarge,
                                color = if (offeneAuftraege.isEmpty()) KuemmeroGreen else KuemmeroError,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (zahlungsFilterOffen) {
                        TextButton(
                            onClick = { zahlungsFilterOffen = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Alle Aufträge anzeigen", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                } // Ende Auftragsformular
                if (!auftragFormOffen && auftragDetailIndex == null) {
                    item {
                        Button(
                            onClick = {
                                bearbeiteIndex = null
                                auftragDetailIndex = null
                                auftragFormOffen = true
                                nummer = ""
                                datum = datumJetzt
                                leistungsdatum = datumJetzt
                                gueltigBis = ""
                                kunde = ""
                                strasse = ""
                                ort = ""
                                leistung = ""
                                stunden = ""
                                material = ""
                                materialBonUri = ""
                                fahrt = ""
                                status = "Offen"
                                zahlungsstatus = "Offen"
                                bezahltAm = ""
                                terminDatum = ""
                                terminUhrzeit = ""
                                notiz = ""
                                fotosVorher = emptyList()
                                fotosNachher = emptyList()
                                unterschriftPfad = ""
                                unterschriftDatum = ""
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                        ) { Text("+ Neuer Auftrag", fontWeight = FontWeight.Bold) }
                    }
                    item {
                        Text(
                            "Gespeicherte Aufträge",
                        style = MaterialTheme.typography.headlineSmall,
                        color = KuemmeroGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = auftragsSuche,
                        onValueChange = { auftragsSuche = it },
                        label = { Text("Aufträge suchen") },
                        placeholder = { Text("Kunde, Angebot, Adresse ...") },
                        singleLine = true,
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Alle", "Offen", "In Bearbeitung", "Erledigt", "Abgerechnet").forEach { option ->
                            val aktiv = statusFilter == option
                            Surface(
                                modifier = Modifier
                                    .height(42.dp)
                                    .clickable { statusFilter = option },
                                shape = RoundedCornerShape(21.dp),
                                color = if (aktiv) KuemmeroGreen else KuemmeroMint,
                                border = BorderStroke(1.5.dp, if (aktiv) KuemmeroGreen else Color(0xFF7A8A82))
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        option,
                                        color = if (aktiv) Color.White else KuemmeroGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${gefilterteAuftraege.size} Auftrag/Aufträge angezeigt",
                        color = KuemmeroText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    }
                }
                if (!auftragFormOffen) {
                itemsIndexed(if (auftragDetailIndex == null) gefilterteAuftraege else gefilterteAuftraege.filter { it.first == auftragDetailIndex }) { _, pair ->
                    val index = pair.first
                    val a = pair.second
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { auftragDetailIndex = index },
                        colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                a.kunde,
                                style = MaterialTheme.typography.titleLarge,
                                color = KuemmeroText,
                                fontWeight = FontWeight.Bold
                            )
                            if (a.nummer.isNotBlank()) {
                                Text("Angebot: ${a.nummer}", color = KuemmeroGreen, fontWeight = FontWeight.SemiBold)
                            }
                            if (a.datum.isNotBlank()) {
                                Text("Datum: ${a.datum}", color = KuemmeroText)
                            }
                            if (a.terminDatum.isNotBlank()) {
                                Text("📅 Termin: ${a.terminDatum}${if (a.terminUhrzeit.isNotBlank()) " · ${a.terminUhrzeit}" else ""}", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                            }
                            Text("Status: ${a.status}", color = KuemmeroGreen, fontWeight = FontWeight.SemiBold)
                            if (a.kundenStrasse.isNotBlank() || a.kundenOrt.isNotBlank()) {
                                Text(
                                    listOf(a.kundenStrasse, a.kundenOrt)
                                        .filter { it.isNotBlank() }
                                        .joinToString(", ")
                                )
                            }
                            if (a.leistung.isNotBlank()) {
                                Text(a.leistung)
                            }
                            Text(
                                euro(gesamtbetrag(a.stunden, a.material, a.fahrt, a.stundensatz, a.erstellungskosten)),
                                style = MaterialTheme.typography.titleMedium,
                                color = KuemmeroGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (a.zahlungsstatus == "Bezahlt") {
                                    "Zahlung: Bezahlt${if (a.bezahltAm.isNotBlank()) " – ${a.bezahltAm}" else ""}"
                                } else {
                                    "Zahlung: Offen"
                                },
                                color = if (a.zahlungsstatus == "Bezahlt") KuemmeroGreen else KuemmeroError,
                                fontWeight = FontWeight.Bold
                            )
                            if (a.rechnungsnummer.isNotBlank()) {
                                Text(
                                    "Rechnung: ${a.rechnungsnummer} · fällig ${a.faelligAm}",
                                    color = KuemmeroText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (rechnungIstUeberfaellig(a, heuteText)) {
                                    Text(
                                        "⚠ Zahlung überfällig",
                                        color = KuemmeroError,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (auftragDetailIndex == index) {
                                OutlinedButton(
                                    onClick = { auftragDetailIndex = null; auftragFormOffen = false },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(2.dp, KuemmeroGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                ) { Text("← Zurück zur Auftragsübersicht", fontWeight = FontWeight.Bold) }

                            } else {
                                Text(
                                    "Tippen, um den Auftrag zu öffnen →",
                                    color = KuemmeroGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }

                            if (auftragDetailIndex == index) {
                            OutlinedButton(
                                onClick = {
                                    val heuteBezahlt = datumFormat.format(Date())
                                    val bezahlt = a.zahlungsstatus != "Bezahlt"
                                    auftraege = auftraege.toMutableList().apply {
                                        set(
                                            index,
                                            if (bezahlt) a.copy(zahlungsstatus = "Bezahlt", bezahltAm = heuteBezahlt)
                                            else a.copy(zahlungsstatus = "Offen", bezahltAm = "")
                                        )
                                    }
                                    speichereAuftraege(context, auftraege)
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, if (a.zahlungsstatus == "Bezahlt") KuemmeroGreen else KuemmeroGreenLight),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (a.zahlungsstatus == "Bezahlt") KuemmeroGreen else KuemmeroGreenLight
                                )
                            ) {
                                Text(
                                    if (a.zahlungsstatus == "Bezahlt") "Zahlung zurücksetzen" else "Als bezahlt markieren",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            val laufend = timerIndex == index && a.arbeitsStart > 0L
                            val gespeicherteZeit = a.arbeitsSekunden + if (laufend) timerSekunden else 0L

                            Text(
                                "Arbeitszeit: ${zeitText(gespeicherteZeit)}",
                                color = KuemmeroText,
                                fontWeight = FontWeight.Bold
                            )

                            if (laufend) {
                                Button(
                                    onClick = {
                                        val jetzt = System.currentTimeMillis()
                                        val dauer = ((jetzt - a.arbeitsStart) / 1000L).coerceAtLeast(0L)
                                        val aktualisiert = a.copy(
                                            arbeitsEnde = jetzt,
                                            arbeitsSekunden = a.arbeitsSekunden + dauer,
                                            arbeitsStart = 0L
                                        )
                                        auftraege = auftraege.toMutableList().apply { set(index, aktualisiert) }
                                        speichereAuftraege(context, auftraege)
                                        timerIndex = null
                                        timerSekunden = 0L
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroError)
                                ) {
                                    Text("⏹ Arbeit beenden", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        val jetzt = System.currentTimeMillis()
                                        val laufenderIndex = auftraege.indexOfFirst { it.arbeitsStart > 0L && it != a }
                                        val neueListe = auftraege.toMutableList()
                                        if (laufenderIndex >= 0) {
                                            val laufend = neueListe[laufenderIndex]
                                            val dauer = ((jetzt - laufend.arbeitsStart) / 1000L).coerceAtLeast(0L)
                                            neueListe[laufenderIndex] = laufend.copy(
                                                arbeitsEnde = jetzt,
                                                arbeitsSekunden = laufend.arbeitsSekunden + dauer,
                                                arbeitsStart = 0L
                                            )
                                        }
                                        neueListe[index] = a.copy(
                                            arbeitsStart = jetzt,
                                            arbeitsEnde = 0L,
                                            arbeitszeitUebernommen = false
                                        )
                                        auftraege = neueListe
                                        speichereAuftraege(context, auftraege)
                                        timerIndex = index
                                        timerSekunden = 0L
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    border = BorderStroke(2.dp, KuemmeroGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                ) {
                                    Text("▶ Arbeit starten", fontWeight = FontWeight.Bold)
                                }
                            }

                            if (gespeicherteZeit > 0L) {
                                if (a.arbeitszeitUebernommen) {
                                    OutlinedButton(
                                        onClick = { },
                                        enabled = false,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                        shape = RoundedCornerShape(26.dp),
                                        border = BorderStroke(2.dp, KuemmeroGreenLight),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = KuemmeroGreenLight,
                                            disabledContentColor = KuemmeroGreenLight
                                        )
                                    ) {
                                        Text(
                                            "✓ Arbeitszeit übernommen (${String.format(Locale.GERMANY, "%.2f", gespeicherteZeit / 3600.0)} Std.)",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            val neueStunden = runde2(gespeicherteZeit / 3600.0)
                                            val aktualisiert = a.copy(
                                                stunden = neueStunden,
                                                arbeitszeitUebernommen = true
                                            )
                                            auftraege = auftraege.toMutableList().apply { set(index, aktualisiert) }
                                            speichereAuftraege(context, auftraege)
                                            stunden = String.format(Locale.GERMANY, "%.2f", neueStunden)
                                        },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                        shape = RoundedCornerShape(26.dp),
                                        border = BorderStroke(2.dp, KuemmeroGreen),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                    ) {
                                        Text(
                                            "⏱ Arbeitszeit übernehmen (${String.format(Locale.GERMANY, "%.2f", gespeicherteZeit / 3600.0)} Std.)",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            if (gespeicherteZeit > 0L) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            arbeitszeitAendernIndex = index
                                            arbeitszeitNeu = String.format(Locale.GERMANY, "%.2f", gespeicherteZeit / 3600.0)
                                        },
                                        modifier = Modifier.weight(1f),
                                        border = BorderStroke(2.dp, KuemmeroGreen),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                    ) { Text("Arbeitszeit ändern") }
                                    OutlinedButton(
                                        onClick = {
                                            val aktualisiert = a.copy(arbeitsStart = 0L, arbeitsEnde = 0L, arbeitsSekunden = 0L, arbeitszeitUebernommen = false)
                                            auftraege = auftraege.toMutableList().apply { set(index, aktualisiert) }
                                            speichereAuftraege(context, auftraege)
                                            if (timerIndex == index) { timerIndex = null; timerSekunden = 0L }
                                        },
                                        modifier = Modifier.weight(1f),
                                        border = BorderStroke(2.dp, KuemmeroError),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroError)
                                    ) { Text("Arbeitszeit löschen") }
                                }
                            }

                            Button(
                                onClick = {
                                    bearbeiteIndex = index
                                    auftragDetailIndex = index
                                    auftragFormOffen = true
                                    nummer = a.nummer.ifBlank { nummer }
                                    datum = a.datum.ifBlank { datum }
                                    leistungsdatum = a.leistungsdatum.ifBlank { a.terminDatum.ifBlank { a.datum.ifBlank { datum } } }
                                    gueltigBis = a.gueltigBis.ifBlank { gueltigBis }
                                    kunde = a.kunde
                                    strasse = a.kundenStrasse
                                    ort = a.kundenOrt
                                    leistung = a.leistung
                                    stunden = a.stunden.toString().replace(".", ",")
                                    material = a.material.toString().replace(".", ",")
                                    materialBonUri = a.materialBonUri
                                    fahrt = a.fahrt.toString().replace(".", ",")
                                    stundensatz = a.stundensatz.toString().replace(".", ",")
                                    status = a.status
                                    zahlungsstatus = a.zahlungsstatus
                                    bezahltAm = a.bezahltAm
                                    terminDatum = a.terminDatum
                                    terminUhrzeit = a.terminUhrzeit
                                    notiz = a.notiz
                                    fotosVorher = a.fotosVorher
                                    fotosNachher = a.fotosNachher
                                    unterschriftPfad = a.unterschriftPfad
                                    unterschriftDatum = a.unterschriftDatum
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                            ) {
                                Text("Auftrag bearbeiten", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    druckePdf(
                                        context,
                                        "KÜMMERO-Angebot-${a.kunde}.pdf",
                                        a.nummer.ifBlank { nummer },
                                        a.datum.ifBlank { datum },
                                        a.gueltigBis.ifBlank { gueltigBis },
                                        a
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)
                            ) {
                                Text("PDF drucken", fontWeight = FontWeight.Bold)
                            }

                            Text(
                                when (a.status) {
                                    "Offen" -> "Ablauf: Angebot → Auftrag annehmen"
                                    "In Bearbeitung" -> "Ablauf: Auftrag → Arbeit erledigen"
                                    "Erledigt" -> "Ablauf: Erledigt → Rechnung erstellen"
                                    "Abgerechnet" -> "Ablauf abgeschlossen"
                                    else -> "Ablauf"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = KuemmeroText,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )

                            OutlinedButton(
                                onClick = {
                                    when (a.status) {
                                        "Offen" -> {
                                            auftraege = auftraege.toMutableList().apply {
                                                set(index, a.copy(status = "In Bearbeitung"))
                                            }
                                            speichereAuftraege(context, auftraege)
                                        }
                                        "In Bearbeitung" -> {
                                            abschlusspruefungIndex = index
                                        }
                                    }
                                },
                                enabled = a.status == "Offen" || a.status == "In Bearbeitung",
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) {
                                Text(
                                    when (a.status) {
                                        "Offen" -> "Auftrag annehmen →"
                                        "In Bearbeitung" -> "Arbeit erledigt →"
                                        "Erledigt" -> "Bereit für Rechnung ✓"
                                        "Abgerechnet" -> "Abgerechnet ✓"
                                        else -> "Status"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (a.status == "Erledigt") {
                                Text(
                                    "Rechnung wird direkt aus diesem Auftrag erstellt.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = KuemmeroText
                                )
                                Button(
                                    onClick = {
                                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                        val firmenStrasse = prefs.getString(FIRMENSTRASSE_KEY, "")?.trim().orEmpty()
                                        val firmenPlzOrt = prefs.getString(FIRMENPLZORT_KEY, "")?.trim().orEmpty()
                                        val steuer = prefs.getString(STEUERNUMMER_KEY, "")?.trim().orEmpty()
                                        val fehlend = when {
                                            firmenStrasse.isBlank() -> "Bitte unter Mehr die Firmenstraße / Hausnummer eintragen."
                                            firmenPlzOrt.isBlank() -> "Bitte unter Mehr PLZ / Ort eintragen."
                                            steuer.isBlank() -> "Bitte unter Mehr Steuernummer / USt-ID / KU-IdNr. eintragen."
                                            a.kunde.isBlank() -> "Für die Rechnung fehlt der Kundenname."
                                            a.kundenStrasse.isBlank() -> "Für die Rechnung fehlt die Kundenstraße / Hausnummer."
                                            a.kundenOrt.isBlank() -> "Für die Rechnung fehlt PLZ / Ort des Kunden."
                                            a.leistung.isBlank() -> "Für die Rechnung fehlt die Leistungsbeschreibung."
                                            else -> ""
                                        }
                                        if (fehlend.isNotBlank()) {
                                            android.widget.Toast.makeText(context, fehlend, android.widget.Toast.LENGTH_LONG).show()
                                        } else {
                                            rechnungFuerIndex = index
                                            val name = a.kunde.ifBlank { "Kunde" }.replace("/", "-")
                                            rechnungLauncher.launch("KÜMMERO-Rechnung-$name.pdf")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)
                                ) {
                                    Text("Rechnung erstellen", fontWeight = FontWeight.Bold)
                                }
                            }

                            if (a.status == "Abgerechnet" && a.rechnungsnummer.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        rechnungNummerEditIndex = index
                                        rechnungNummerEditText = a.rechnungsnummer
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    border = BorderStroke(2.dp, KuemmeroGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                ) { Text("✏ Rechnungsnummer korrigieren", fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = { druckeRechnungPdf(context, a) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)
                                ) {
                                    Text("🧾 Rechnung PDF drucken", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { starteRechnungScan(index) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    border = BorderStroke(2.dp, KuemmeroGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                ) { Text("📷 Rechnung scannen", fontWeight = FontWeight.Bold) }

                                val scanUriText = ladeRechnungScan(context, a)
                                if (scanUriText.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(Uri.parse(scanUriText), "image/*")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                })
                                            } catch (_: Exception) {
                                                android.widget.Toast.makeText(context, "Gespeicherter Scan kann nicht geöffnet werden.", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                        shape = RoundedCornerShape(26.dp),
                                        border = BorderStroke(1.dp, KuemmeroGreenLight),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreenLight)
                                    ) { Text("📄 Eingescannte Rechnung öffnen", fontWeight = FontWeight.Bold) }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                                    loeschIndex = index
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroError),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroError)
                            ) {
                                Text("Auftrag löschen", fontWeight = FontWeight.Bold)
                            }
                            }
                        }
                    }
                }
                } // Ende Auftragsliste
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (hauptseite) {
                    "Heute" -> {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = KuemmeroGreen),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Heute · $heuteText", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(
                                            Modifier.weight(1f).clickable {
                                                hauptseite = "Kalender"
                                                auftragFormOffen = false
                                                auftragDetailIndex = null
                                                statusFilter = "Alle"
                                                zahlungsFilterOffen = false
                                                auftragsSuche = ""
                                            }
                                        ) {
                                            Text("Termine", color = Color.White)
                                            Text("${termineHeute.size}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Column(
                                            Modifier.weight(1f).clickable {
                                                hauptseite = "Aufträge"
                                                auftragFormOffen = false
                                                auftragDetailIndex = null
                                                bearbeiteIndex = null
                                                statusFilter = "Offen"
                                                zahlungsFilterOffen = false
                                                auftragsSuche = ""
                                            }
                                        ) {
                                            Text("Offene Aufträge", color = Color.White)
                                            Text("$offeneAuftraege", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Column(
                                            Modifier.weight(1f).clickable {
                                                hauptseite = "Aufträge"
                                                auftragFormOffen = false
                                                auftragDetailIndex = null
                                                bearbeiteIndex = null
                                                statusFilter = "Erledigt"
                                                zahlungsFilterOffen = false
                                                auftragsSuche = ""
                                            }
                                        ) {
                                            Text("Abgearbeitet", color = Color.White)
                                            Text("$abgearbeiteteAuftraege", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(
                                            Modifier.weight(1f).clickable {
                                                hauptseite = "Aufträge"
                                                auftragFormOffen = false
                                                auftragDetailIndex = null
                                                bearbeiteIndex = null
                                                statusFilter = "Alle"
                                                zahlungsFilterOffen = true
                                                auftragsSuche = ""
                                            }
                                        ) {
                                            Text("Offen €", color = Color.White)
                                            Text(euro(offeneZahlungSumme), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.weight(2f))
                                    }
                                }
                            }
                        }
                        item {
                            Text("Heutige Termine", style = MaterialTheme.typography.titleLarge, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                        }
                        if (termineHeute.isEmpty()) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                    Text("Heute keine Termine.", Modifier.padding(18.dp), color = KuemmeroText)
                                }
                            }
                        } else {
                            itemsIndexed(termineHeute) { _, a ->
                                Card(
                                    Modifier.fillMaxWidth().clickable {
                                        val index = auftraege.indexOfFirst { it.nummer == a.nummer && it.kunde == a.kunde && it.terminDatum == a.terminDatum }
                                        if (index >= 0) { hauptseite = "Aufträge"; auftragDetailIndex = index }
                                    },
                                    colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.5.dp, KuemmeroGreenLight)
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(a.terminUhrzeit.ifBlank { "Ohne Uhrzeit" }, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                        Text(a.kunde, color = KuemmeroText, style = MaterialTheme.typography.titleMedium)
                                        Text(a.leistung, color = KuemmeroText)
                                        Text(a.kundenStrasse + if (a.kundenOrt.isBlank()) "" else ", ${a.kundenOrt}", color = KuemmeroText)
                                    }
                                }
                            }
                        }
                        item {
                            Text("Nächster Termin", style = MaterialTheme.typography.titleLarge, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                        }
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.5.dp, KuemmeroGreenLight)
                            ) {
                                if (naechsterTermin == null) {
                                    Text("Keine zukünftigen Termine.", Modifier.padding(18.dp), color = KuemmeroText)
                                } else {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${naechsterTermin.terminDatum} · ${naechsterTermin.terminUhrzeit.ifBlank { "ohne Uhrzeit" }}", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                        Text(naechsterTermin.kunde, color = KuemmeroText, style = MaterialTheme.typography.titleMedium)
                                        if (naechsterTermin.leistung.isNotBlank()) Text(naechsterTermin.leistung, color = KuemmeroText)
                                        Button(
                                            onClick = {
                                                val index = auftraege.indexOfFirst { it.nummer == naechsterTermin.nummer && it.kunde == naechsterTermin.kunde && it.terminDatum == naechsterTermin.terminDatum }
                                                if (index >= 0) { hauptseite = "Aufträge"; auftragFormOffen = false; auftragDetailIndex = index }
                                            },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                            shape = RoundedCornerShape(25.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                        ) { Text("Auftrag öffnen →", fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                    "Kunden" -> {
                        item { Text("Kunden", style = MaterialTheme.typography.headlineSmall, color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                        item {
                            Button(onClick = { neuerKundeDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)) {
                                Text("+ Neuer Kunde", fontWeight = FontWeight.Bold)
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = kundenSuche,
                                onValueChange = { kundenSuche = it },
                                label = { Text("Kunden suchen") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = feldFarben,
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        val kundenGefiltert = kunden.filter {
                            val q = kundenSuche.trim().lowercase()
                            q.isBlank() || listOf(it.name, it.adresse, it.ort, it.telefon, it.email).any { value -> value.lowercase().contains(q) }
                        }
                        item { Text("${kundenGefiltert.size} Kunde${if (kundenGefiltert.size == 1) "" else "n"}", color = KuemmeroText) }
                        if (kundenGefiltert.isEmpty()) {
                            item { Text(if (kunden.isEmpty()) "Noch keine Kunden gespeichert." else "Kein Kunde gefunden.", color = KuemmeroText) }
                        } else {
                            itemsIndexed(kundenGefiltert) { _, k ->
                                Card(Modifier.fillMaxWidth().clickable { kundenAkteName = k.name }, colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.5.dp, KuemmeroGreenLight)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(k.name, style = MaterialTheme.typography.titleMedium, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                        if (k.adresse.isNotBlank() || k.ort.isNotBlank()) Text(listOf(k.adresse, k.ort).filter { it.isNotBlank() }.joinToString(", "), color = KuemmeroText)
                                        if (k.telefon.isNotBlank()) Text("☎ ${k.telefon}", color = KuemmeroText)
                                        if (k.email.isNotBlank()) Text("✉ ${k.email}", color = KuemmeroText)
                                        Text("Aufträge: ${auftraege.count { it.kunde == k.name }}", color = KuemmeroText)
                                        Text("Kundenakte öffnen →", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    "Kalender" -> {
                        item { Text("Kalender", style = MaterialTheme.typography.headlineSmall, color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                        val geplante = auftraege.filter { it.terminDatum.isNotBlank() }.sortedWith(compareBy({ it.terminDatum }, { it.terminUhrzeit }))
                        if (geplante.isEmpty()) {
                            item { Text("Keine Termine gespeichert.", color = KuemmeroText) }
                        } else {
                            itemsIndexed(geplante) { _, a ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.5.dp, KuemmeroGreenLight)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(a.terminDatum, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                        Text(a.terminUhrzeit.ifBlank { "Ohne Uhrzeit" }, color = KuemmeroText)
                                        Text(a.kunde, style = MaterialTheme.typography.titleMedium, color = KuemmeroText, fontWeight = FontWeight.Bold)
                                        Text(a.leistung, color = KuemmeroText)
                                    }
                                }
                            }
                        }
                    }
                    "Kostenvoranschläge" -> {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                TextButton(onClick = { hauptseite = "Aufträge" }) { Text("← Zurück") }
                                Text(
                                    "Kostenvoranschläge",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = KuemmeroGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (!kvFormOffen) {
                            item {
                                Button(
                                    onClick = {
                                        kvBearbeiteIndex = null
                                        kvNummer = "KV-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date())
                                        kvDatum = datumFormat.format(Date())
                                        kvGueltigBis = datumFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 14) }.time)
                                        kvKunde = ""
                                        kvStrasse = ""
                                        kvOrt = ""
                                        kvLeistung = ""
                                        kvStunden = ""
                                        kvMaterial = ""
                                        kvMaterialBonUri = ""
                                        kvFotosVorher = emptyList()
                                        kvFahrt = ""
                                        kvFormOffen = true
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                    shape = RoundedCornerShape(28.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                ) { Text("+ Neuer Kostenvoranschlag", fontWeight = FontWeight.Bold) }
                            }

                            if (kostenvoranschlaege.isEmpty()) {
                                item { Text("Noch keine Kostenvoranschläge gespeichert.", color = KuemmeroText) }
                            }

                            itemsIndexed(kostenvoranschlaege) { index, k ->
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.5.dp, KuemmeroGreenLight)
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            k.kunde.ifBlank { "Ohne Kundenname" },
                                            style = MaterialTheme.typography.titleMedium,
                                            color = KuemmeroText,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text("${k.nummer} · ${k.datum}", color = KuemmeroText)
                                        if (k.leistung.isNotBlank()) Text(k.leistung, color = KuemmeroText)
                                        Text(
                                            euro(gesamtbetrag(k.stunden, k.material, k.fahrt, k.stundensatz, k.erstellungskosten)),
                                            color = KuemmeroGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (k.materialBonUri.isNotBlank()) {
                                            Text("🧾 Material-Kassenbon vorhanden", color = KuemmeroGreen, fontWeight = FontWeight.SemiBold)
                                        }
                                        if (k.fotosVorher.isNotEmpty()) {
                                            Text("📷 Bild vorher: ${k.fotosVorher.size}", color = KuemmeroGreen, fontWeight = FontWeight.SemiBold)
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    kvBearbeiteIndex = index
                                                    kvNummer = k.nummer
                                                    kvDatum = k.datum
                                                    kvGueltigBis = k.gueltigBis
                                                    kvKunde = k.kunde
                                                    kvStrasse = k.kundenStrasse
                                                    kvOrt = k.kundenOrt
                                                    kvLeistung = k.leistung
                                                    kvStunden = k.stunden.toString().replace(".", ",")
                                                    kvMaterial = k.material.toString().replace(".", ",")
                                                    kvMaterialBonUri = k.materialBonUri
                                                    kvFotosVorher = k.fotosVorher
                                                    kvFahrt = k.fahrt.toString().replace(".", ",")
                                                    kvStundensatz = k.stundensatz.toString().replace(".", ",")
                                                    kvErstellungskosten = k.erstellungskosten.toString().replace(".", ",")
                                                    kvFormOffen = true
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Bearbeiten") }

                                            OutlinedButton(
                                                onClick = { kvLoeschIndex = index },
                                                modifier = Modifier.weight(1f),
                                                border = BorderStroke(2.dp, KuemmeroError),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroError)
                                            ) { Text("Löschen") }

                                            Button(
                                                onClick = {
                                                    val a = Auftrag(
                                                        nummer = "ANG-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date()),
                                                        datum = k.datum.ifBlank { datumFormat.format(Date()) },
                                                        gueltigBis = k.gueltigBis,
                                                        kunde = k.kunde,
                                                        kundenStrasse = k.kundenStrasse,
                                                        kundenOrt = k.kundenOrt,
                                                        leistung = k.leistung,
                                                        stunden = k.stunden,
                                                        material = k.material,
                                                        materialBonUri = k.materialBonUri,
                                                        fahrt = k.fahrt,
                                                        stundensatz = k.stundensatz,
                                                        status = "Offen",
                                                        zahlungsstatus = "Offen",
                                                        fotosVorher = k.fotosVorher,
                                                        erstellungskosten = k.erstellungskosten,
                                                        leistungsdatum = k.datum
                                                    )
                                                    auftraege = auftraege + a
                                                    speichereAuftraege(context, auftraege)
                                                    hauptseite = "Aufträge"
                                                    auftragFormOffen = false
                                                    bearbeiteIndex = null
                                                    auftragDetailIndex = auftraege.lastIndex
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Kostenvoranschlag wurde als Auftrag übernommen.",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                            ) { Text("Als Auftrag") }

                                            Button(
                                                onClick = {
                                                    val a = Auftrag(
                                                        k.nummer, k.datum, k.gueltigBis, k.kunde,
                                                        k.kundenStrasse, k.kundenOrt, k.leistung,
                                                        k.stunden, k.material, k.materialBonUri, k.fahrt, k.stundensatz,
                                                        fotosVorher = k.fotosVorher,
                                                        erstellungskosten = k.erstellungskosten
                                                    )
                                                    druckePdf(
                                                        context,
                                                        "Kostenvoranschlag-${k.kunde.ifBlank { "Kunde" }}.pdf",
                                                        k.nummer, k.datum, k.gueltigBis, a,
                                                        "KOSTENVORANSCHLAG"
                                                    )
                                                },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)
                                            ) { Text("PDF") }
                                        }
                                    }
                                }
                            }
                        } else {
                            item {
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                        Text(
                                            if (kvBearbeiteIndex == null) "Neuer Kostenvoranschlag" else "Kostenvoranschlag bearbeiten",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = KuemmeroGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                        OutlinedTextField(kvNummer, { kvNummer = it }, label = { Text("Nummer") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvDatum, { kvDatum = it }, label = { Text("Datum") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvGueltigBis, { kvGueltigBis = it }, label = { Text("Gültig bis") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Kunde", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                            OutlinedButton(
                                                onClick = { kvKundenDialog = true },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                                shape = RoundedCornerShape(14.dp),
                                                border = BorderStroke(2.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroText)
                                            ) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    Text(if (kvKunde.isBlank()) "Kunde auswählen" else kvKunde, fontWeight = FontWeight.SemiBold)
                                                    Text("▼", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        OutlinedTextField(kvStrasse, { kvStrasse = it }, label = { Text("Adresse") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvOrt, { kvOrt = it }, label = { Text("PLZ und Ort") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvLeistung, { kvLeistung = it }, label = { Text("Leistung") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("📷 Bild vorher", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                            OutlinedButton(
                                                onClick = { kvFotoVorherLauncher.launch("image/*") },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                                shape = RoundedCornerShape(26.dp),
                                                border = BorderStroke(2.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) {
                                                Text("📷 Bild vorher hinzufügen", fontWeight = FontWeight.Bold)
                                            }
                                            if (kvFotosVorher.isNotEmpty()) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    kvFotosVorher.forEach { uri ->
                                                        FotoVorschau(
                                                            context, uri,
                                                            onClick = { fotoVorschauUri = uri },
                                                            onDelete = { kvFotosVorher = kvFotosVorher.filterNot { it == uri } }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        OutlinedTextField(kvStunden, { kvStunden = it }, label = { Text("Arbeitsstunden") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvMaterial, { kvMaterial = it }, label = { Text("Material (€)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { materialBonLauncher.launch(arrayOf("image/*", "application/pdf")) },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                                shape = RoundedCornerShape(26.dp),
                                                border = BorderStroke(2.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) {
                                                Text(
                                                    if (kvMaterialBonUri.isBlank()) "🧾 Kassenbon zum Material hinzufügen" else "✓ Material-Kassenbon vorhanden",
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            if (kvMaterialBonUri.isNotBlank()) {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text(
                                                        if (kvMaterialBonUri.lowercase(Locale.GERMANY).contains(".pdf")) "🧾 Material-Kassenbon: PDF" else "🧾 Material-Kassenbon: Foto",
                                                        modifier = Modifier.weight(1f), color = KuemmeroText
                                                    )
                                                    TextButton(onClick = { kvMaterialBonUri = "" }, colors = ButtonDefaults.textButtonColors(contentColor = KuemmeroError)) { Text("Entfernen") }
                                                }
                                            }
                                        }
                                        OutlinedTextField(kvFahrt, { kvFahrt = it }, label = { Text("Fahrtkosten (€)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvStundensatz, { kvStundensatz = it }, label = { Text("Stundensatz (€ / Stunde)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvErstellungskosten, { kvErstellungskosten = it }, label = { Text("Erstellungskosten (€)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        if (zahl(kvErstellungskosten) > 0.0) {
                                            Text(
                                                "Hinweis: Der Kostenvoranschlag ist kostenpflichtig. Die Erstellungskosten werden mit dem angegebenen Betrag ausgewiesen.",
                                                color = KuemmeroError,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Text(
                                            "Gesamtsumme: ${euro(gesamtbetrag(zahl(kvStunden), zahl(kvMaterial), zahl(kvFahrt), zahl(kvStundensatz, 42.0), zahl(kvErstellungskosten)))}",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = KuemmeroGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Button(
                                            onClick = {
                                                if (kvKunde.isBlank()) {
                                                    android.widget.Toast.makeText(context, "Bitte Kundennamen eingeben.", 0).show()
                                                } else {
                                                    val k = Kostenvoranschlag(
                                                        kvNummer.trim(), kvDatum.trim(), kvGueltigBis.trim(),
                                                        kvKunde.trim(), kvStrasse.trim(), kvOrt.trim(), kvLeistung.trim(),
                                                        zahl(kvStunden), zahl(kvMaterial), zahl(kvFahrt), zahl(kvStundensatz, 42.0),
                                                        kvMaterialBonUri, kvFotosVorher, zahl(kvErstellungskosten)
                                                    )
                                                    val list = kostenvoranschlaege.toMutableList()
                                                    if (kvBearbeiteIndex != null) list[kvBearbeiteIndex!!] = k else list.add(k)
                                                    kostenvoranschlaege = list
                                                    speichereKostenvoranschlaege(context, list)
                                                    kvFormOffen = false
                                                    kvBearbeiteIndex = null
                                                    kvErstellungskosten = ""
                                                    android.widget.Toast.makeText(context, "Kostenvoranschlag gespeichert.", 0).show()
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                            shape = RoundedCornerShape(28.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                        ) { Text("Kostenvoranschlag speichern", fontWeight = FontWeight.Bold) }
                                        OutlinedButton(
                                            onClick = { kvFormOffen = false; kvBearbeiteIndex = null; kvErstellungskosten = "" },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                            shape = RoundedCornerShape(26.dp),
                                            border = BorderStroke(2.dp, KuemmeroGreen),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                        ) { Text("Abbrechen") }
                                    }
                                }
                            }
                        }
                    }
                    "Mehr" -> {
                        item { Text("Mehr", style = MaterialTheme.typography.headlineSmall, color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                        item {
                            OutlinedButton(
                                onClick = { hauptseite = "Kalender" },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) { Text("📅 Kalender", fontWeight = FontWeight.Bold) }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Sicherung & Daten", style = MaterialTheme.typography.titleMedium, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    val backupPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    val backupUriVorhanden = backupPrefs.getString(BACKUP_URI_KEY, null)?.isNotBlank() == true
                                    val backupZeit = backupPrefs.getLong(BACKUP_LAST_SUCCESS_KEY, 0L)
                                    Text(
                                        when {
                                            backupZeit > 0L -> "Letzte erfolgreiche Sicherung: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(backupZeit))}"
                                            backupUriVorhanden -> "Sicherungsdatei hinterlegt – noch kein erfolgreicher Sicherungslauf gespeichert."
                                            else -> "Noch keine Sicherungsdatei hinterlegt."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (backupZeit > 0L) KuemmeroGreen else KuemmeroText
                                    )
                                    OutlinedButton(onClick = { sicherungBestaetigung = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("Sicherung speichern / aktualisieren") }
                                    Button(onClick = { restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)) { Text("Daten wiederherstellen", fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Unternehmensdaten für Rechnungen", style = MaterialTheme.typography.titleMedium, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    OutlinedTextField(unternehmerName, { unternehmerName = it }, label = { Text("Name / Inhaber") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(unternehmerStrasse, { unternehmerStrasse = it }, label = { Text("Straße / Hausnummer") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(unternehmerPlzOrt, { unternehmerPlzOrt = it }, label = { Text("PLZ / Ort") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(unternehmerTelefon, { unternehmerTelefon = it }, label = { Text("Telefon") }, colors = feldFarben, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                                    OutlinedTextField(unternehmerEmail, { unternehmerEmail = it }, label = { Text("E-Mail") }, colors = feldFarben, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                                    OutlinedTextField(steuernummer, { steuernummer = it }, label = { Text("Steuernummer / USt-ID / KU-IdNr.") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                    Button(onClick = {
                                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                                            .putString(FIRMENNAME_KEY, unternehmerName.trim())
                                            .putString(FIRMENSTRASSE_KEY, unternehmerStrasse.trim())
                                            .putString(FIRMENPLZORT_KEY, unternehmerPlzOrt.trim())
                                            .putString(FIRMENTELEFON_KEY, unternehmerTelefon.trim())
                                            .putString(FIRMENEMAIL_KEY, unternehmerEmail.trim())
                                            .putString(STEUERNUMMER_KEY, steuernummer.trim())
                                            .apply()
                                        android.widget.Toast.makeText(context, "Unternehmensdaten gespeichert.", 0).show()
                                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)) { Text("Unternehmensdaten speichern") }
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("KÜMMERO", style = MaterialTheme.typography.titleLarge, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    Text("Haus & Alltag – wir kümmern uns.", color = KuemmeroText)
                                    Text("${auftraege.size} Aufträge · ${kunden.size} Kunden", color = KuemmeroText)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
                                     }
