package de.kuemmero.app

import android.content.Context
import android.content.ContentValues
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
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

private val KUEMMERO_HAUPTSEITEN = listOf(
    "Heute",
    "Aufträge",
    "Kostenvoranschläge",
    "Kunden",
    "Mahnungen",
    "Mehr"
)

private fun Modifier.kuemmeroHauptseitenWischen(
    hauptseite: String,
    onSeite: (String) -> Unit
): Modifier = pointerInput(hauptseite) {
    var gesamtDelta = 0f
    detectHorizontalDragGestures(
        onHorizontalDrag = { _, dragAmount ->
            gesamtDelta += dragAmount
        },
        onDragEnd = {
            val index = KUEMMERO_HAUPTSEITEN.indexOf(hauptseite)
            if (index >= 0 && kotlin.math.abs(gesamtDelta) >= 80f) {
                val zielIndex = if (gesamtDelta < 0f) index + 1 else index - 1
                KUEMMERO_HAUPTSEITEN.getOrNull(zielIndex)?.let(onSeite)
            }
            gesamtDelta = 0f
        },
        onDragCancel = { gesamtDelta = 0f }
    )
}
private const val RECHNUNGSNUMMER_COUNTER_KEY = "rechnungsnummer_counter"

private fun naechsteRechnungsnummer(
    context: Context,
    vorhandeneRechnungsnummern: List<String> = emptyList()
): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val jahr = SimpleDateFormat("yyyy", Locale.GERMANY).format(Date())
    val key = RECHNUNGSNUMMER_COUNTER_KEY + "_" + jahr
    val gespeicherterZaehler = prefs.getInt(key, 0)

    val groessteVorhandeneNummer = vorhandeneRechnungsnummern
        .mapNotNull { nummer ->
            Regex("^RE-${Regex.escape(jahr)}-(\\d{4})$").find(nummer.trim())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        .maxOrNull() ?: 0

    val naechste = maxOf(gespeicherterZaehler, groessteVorhandeneNummer) + 1
    var kandidat = "RE-$jahr-" + naechste.toString().padStart(4, '0')
    var laufnummer = naechste

    while (vorhandeneRechnungsnummern.any { it.equals(kandidat, ignoreCase = true) }) {
        laufnummer++
        kandidat = "RE-$jahr-" + laufnummer.toString().padStart(4, '0')
    }

    return kandidat
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
    val mahnung1Datum: String = "",
    val mahnung1Frist: String = "",
    val mahnung1Gebuehr: Double = 0.0,
    val mahnung1Text: String = "",
    val mahnung1Erstellt: Boolean = false,
    val mahnung2Datum: String = "",
    val mahnung2Frist: String = "",
    val mahnung2Gebuehr: Double = 0.0,
    val mahnung2Text: String = "",
    val mahnung2Erstellt: Boolean = false,
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
private const val FAHRTKOSTEN_PRO_KM_KEY = "fahrtkosten_pro_km"
private const val BACKUP_URI_KEY = "backup_uri"
private const val BACKUP_LAST_SUCCESS_KEY = "backup_last_success"
private const val BACKUP_PRE_RESTORE_FILE = "kuemmero_vor_restore_backup.json"
private const val DROPBOX_PACKAGE = "com.dropbox.android"
private const val KOSTENVORANSCHLAEGE_KEY = "kostenvoranschlaege"
private const val FIRMENNAME_KEY = "firmen_name"
private const val FIRMENSTRASSE_KEY = "firmen_strasse"
private const val FIRMENPLZORT_KEY = "firmen_plz_ort"
private const val FIRMENTELEFON_KEY = "firmen_telefon"
private const val FIRMENEMAIL_KEY = "firmen_email"
private const val STEUERNUMMER_KEY = "steuernummer"
private const val MAHNUNG1_FRIST_TAGE_KEY = "mahnung1_frist_tage"
private const val MAHNUNG1_GEBUEHR_KEY = "mahnung1_gebuehr"
private const val MAHNUNG1_TEXT_KEY = "mahnung1_text"
private const val MAHNUNG_TESTMODUS_KEY = "mahnung_testmodus"
private const val MAHNUNG_SPEICHERORDNER_URI_KEY = "mahnung_speicherordner_uri"
private const val DOKUMENTE_SPEICHERORDNER_URI_KEY = "dokumente_speicherordner_uri"
private fun standardMahnung1Frist(context: Context, basisDatum: Date = Date()): String {
    val tage = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(MAHNUNG1_FRIST_TAGE_KEY, 7)
        .coerceAtLeast(0)
    val cal = Calendar.getInstance().apply {
        time = basisDatum
        add(Calendar.DAY_OF_YEAR, tage)
    }
    return SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(cal.time)
}

private fun standardMahnung1Text(context: Context): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(
            MAHNUNG1_TEXT_KEY,
            "Bitte begleichen Sie den offenen Rechnungsbetrag innerhalb der angegebenen Zahlungsfrist."
        ) ?: "Bitte begleichen Sie den offenen Rechnungsbetrag innerhalb der angegebenen Zahlungsfrist."

private fun csvFeld(wert: String): String =
    "\"" + wert.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""


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
            o.optString("mahnung1Datum", ""),
            o.optString("mahnung1Frist", ""),
            o.optDouble("mahnung1Gebuehr", 0.0),
            o.optString("mahnung1Text", ""),
            o.optBoolean("mahnung1Erstellt", false),
            o.optString("mahnung2Datum", ""),
            o.optString("mahnung2Frist", ""),
            o.optDouble("mahnung2Gebuehr", 0.0),
            o.optString("mahnung2Text", ""),
            o.optBoolean("mahnung2Erstellt", false),
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
            put("mahnung1Datum", a.mahnung1Datum)
            put("mahnung1Frist", a.mahnung1Frist)
            put("mahnung1Gebuehr", a.mahnung1Gebuehr)
            put("mahnung1Text", a.mahnung1Text)
            put("mahnung1Erstellt", a.mahnung1Erstellt)
            put("mahnung2Datum", a.mahnung2Datum)
            put("mahnung2Frist", a.mahnung2Frist)
            put("mahnung2Gebuehr", a.mahnung2Gebuehr)
            put("mahnung2Text", a.mahnung2Text)
            put("mahnung2Erstellt", a.mahnung2Erstellt)
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
        put("leistungspositionen", JSONArray(p.getString(LEISTUNGSPOSITIONEN_KEY, "[]") ?: "[]"))
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


private fun teileBackupMitDropbox(context: Context): Boolean {
    return try {
        val dateiname = "KÜMMERO-Cloud-Sicherung-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.GERMANY).format(Date())}.json"
        val datei = java.io.File(context.cacheDir, dateiname)
        datei.writeText(backupText(context), Charsets.UTF_8)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            datei
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "KÜMMERO Cloud-Sicherung")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(DROPBOX_PACKAGE)
        }
        context.startActivity(intent)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(
            context,
            "Dropbox ist nicht installiert oder nicht verfügbar.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        false
    } catch (_: Exception) {
        android.widget.Toast.makeText(
            context,
            "Dropbox-Sicherung konnte nicht vorbereitet werden.",
            android.widget.Toast.LENGTH_LONG
        ).show()
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
    val istAuftrag = dokumentTitel.equals("AUFTRAG", ignoreCase = true)
    c.drawText(if (istAuftrag) "Auftragsnummer: $nummer" else "Angebotsnummer: $nummer", 40f, 258f, p)
    c.drawText("Datum: $datum", 40f, 278f, p)
    if (!istAuftrag) {
        c.drawText("Gültig bis: $gueltigBis", 40f, 298f, p)
    }
    val kundenY = if (istAuftrag) 308f else 328f
    val strasseY = if (istAuftrag) 328f else 348f
    val ortY = if (istAuftrag) 348f else 368f
    val leistungY = if (istAuftrag) 378f else 398f
    val lineY = if (istAuftrag) 403f else 423f
    val arbeitsY = if (istAuftrag) 428f else 448f
    c.drawText("Kunde: $kunde", 40f, kundenY, p)
    c.drawText("Straße: $strasse", 40f, strasseY, p)
    c.drawText("PLZ und Ort: $ort", 40f, ortY, p)
    c.drawText("Leistung: $leistung", 40f, leistungY, p)
    c.drawLine(40f, lineY, 550f, lineY, p)
    c.drawText("Arbeitszeit", 40f, arbeitsY, p)
    val materialY = arbeitsY + 25f
    val fahrtY = arbeitsY + 50f
    val erstellungY = arbeitsY + 75f
    val line2Y = if (erstellungskosten > 0.0) arbeitsY + 90f else arbeitsY + 65f
    val gesamtY = if (erstellungskosten > 0.0) arbeitsY + 125f else arbeitsY + 100f
    val hinweisY = if (erstellungskosten > 0.0) arbeitsY + 155f else arbeitsY + 130f
    c.drawText("%.2f Std.".format(Locale.GERMANY, stunden), 250f, arbeitsY, p)
    c.drawText(euro(arbeitsbetrag(stunden, stundensatz)), 450f, arbeitsY, p)
    c.drawText("Material", 40f, materialY, p)
    c.drawText(euro(material), 450f, materialY, p)
    c.drawText("Fahrtkosten", 40f, fahrtY, p)
    c.drawText(euro(fahrt), 450f, fahrtY, p)
    if (erstellungskosten > 0.0) {
        c.drawText("Erstellungskosten", 40f, erstellungY, p)
        c.drawText(euro(erstellungskosten), 450f, erstellungY, p)
    }
    c.drawLine(40f, line2Y, 550f, line2Y, p)
    val gesamt = gesamtbetrag(stunden, material, fahrt, stundensatz, erstellungskosten)
    p.textSize = 18f
    c.drawText("Gesamtsumme: ${euro(gesamt)}", 40f, gesamtY, p)
    p.textSize = 11f
    c.drawText("Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.", 40f, hinweisY, p)
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

private fun erstelleMahnung1Pdf(
    context: Context,
    auftrag: Auftrag,
    mahnungDatum: String,
    zahlungsfrist: String,
    mahngebuehr: Double,
    eigenerText: String
): PdfDocument {
    val pdf = PdfDocument()
    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val c = page.canvas
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val firmenName = prefs.getString(FIRMENNAME_KEY, "") ?: ""
    val firmenStrasse = prefs.getString(FIRMENSTRASSE_KEY, "") ?: ""
    val firmenPlzOrt = prefs.getString(FIRMENPLZORT_KEY, "") ?: ""
    val firmenTelefon = prefs.getString(FIRMENTELEFON_KEY, "") ?: ""
    val firmenEmail = prefs.getString(FIRMENEMAIL_KEY, "") ?: ""

    p.textSize = 16f
    p.isFakeBoldText = true
    c.drawText(firmenName.ifBlank { "KÜMMERO" }, 40f, 55f, p)
    p.isFakeBoldText = false
    p.textSize = 10f
    c.drawText(firmenStrasse, 40f, 75f, p)
    c.drawText(firmenPlzOrt, 40f, 90f, p)
    if (firmenTelefon.isNotBlank()) c.drawText("Telefon: $firmenTelefon", 40f, 105f, p)
    if (firmenEmail.isNotBlank()) c.drawText("E-Mail: $firmenEmail", 40f, 120f, p)

    p.textSize = 20f
    p.isFakeBoldText = true
    c.drawText("1. MAHNUNG", 40f, 170f, p)
    p.isFakeBoldText = false
    p.textSize = 11f
    c.drawText("Mahndatum: $mahnungDatum", 40f, 195f, p)
    c.drawText("Rechnungsnummer: ${auftrag.rechnungsnummer}", 40f, 215f, p)
    c.drawText("Rechnungsdatum: ${auftrag.rechnungsdatum}", 40f, 235f, p)
    c.drawText("Ursprünglich fällig am: ${auftrag.faelligAm}", 40f, 255f, p)
    c.drawText("Kunde: ${auftrag.kunde}", 40f, 285f, p)
    c.drawText("Adresse: ${auftrag.kundenStrasse}", 40f, 305f, p)
    c.drawText("PLZ und Ort: ${auftrag.kundenOrt}", 40f, 325f, p)

    val rechnungsbetrag = gesamtbetrag(auftrag.stunden, auftrag.material, auftrag.fahrt, auftrag.stundensatz, auftrag.erstellungskosten)
    val gesamt = runde2(rechnungsbetrag + mahngebuehr)
    p.textSize = 13f
    p.isFakeBoldText = true
    c.drawText("Offener Rechnungsbetrag: ${euro(rechnungsbetrag)}", 40f, 365f, p)
    var y = 390f
    if (mahngebuehr > 0.0) {
        c.drawText("Mahngebühr: ${euro(mahngebuehr)}", 40f, y, p)
        y += 25f
        c.drawText("Gesamt zu zahlen: ${euro(gesamt)}", 40f, y, p)
        y += 40f
    } else {
        c.drawText("Gesamt zu zahlen: ${euro(gesamt)}", 40f, y, p)
        y += 40f
    }
    p.isFakeBoldText = false
    p.textSize = 11f

    val text = eigenerText.ifBlank {
        "Zu unserer Rechnung ${auftrag.rechnungsnummer} ist bisher kein Zahlungseingang festgestellt worden. Bitte begleichen Sie den offenen Betrag spätestens bis zum $zahlungsfrist."
    }
    val words = text.split(Regex("\\s+"))
    var line = ""
    for (word in words) {
        val test = if (line.isBlank()) word else "$line $word"
        if (p.measureText(test) > 510f) {
            c.drawText(line, 40f, y, p)
            y += 17f
            line = word
        } else line = test
    }
    if (line.isNotBlank()) {
        c.drawText(line, 40f, y, p)
        y += 17f
    }
    y += 18f
    c.drawText("Neue Zahlungsfrist: $zahlungsfrist", 40f, y, p)
    y += 30f
    c.drawText("Mit freundlichen Grüßen", 40f, y, p)
    y += 18f
    c.drawText(firmenName.ifBlank { "KÜMMERO" }, 40f, y, p)
    pdf.finishPage(page)
    return pdf
}

private fun erstelleMahnung2Pdf(
    context: Context,
    auftrag: Auftrag,
    mahnungDatum: String,
    zahlungsfrist: String,
    mahngebuehr: Double,
    eigenerText: String
): PdfDocument {
    val pdf = PdfDocument()
    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val c = page.canvas
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val firmenName = prefs.getString(FIRMENNAME_KEY, "") ?: ""
    val firmenStrasse = prefs.getString(FIRMENSTRASSE_KEY, "") ?: ""
    val firmenPlzOrt = prefs.getString(FIRMENPLZORT_KEY, "") ?: ""
    val firmenTelefon = prefs.getString(FIRMENTELEFON_KEY, "") ?: ""
    val firmenEmail = prefs.getString(FIRMENEMAIL_KEY, "") ?: ""

    p.textSize = 16f
    p.isFakeBoldText = true
    c.drawText(firmenName.ifBlank { "KÜMMERO" }, 40f, 55f, p)
    p.isFakeBoldText = false
    p.textSize = 10f
    c.drawText(firmenStrasse, 40f, 75f, p)
    c.drawText(firmenPlzOrt, 40f, 90f, p)
    if (firmenTelefon.isNotBlank()) c.drawText("Telefon: $firmenTelefon", 40f, 105f, p)
    if (firmenEmail.isNotBlank()) c.drawText("E-Mail: $firmenEmail", 40f, 120f, p)

    p.textSize = 20f
    p.isFakeBoldText = true
    c.drawText("2. MAHNUNG", 40f, 170f, p)
    p.isFakeBoldText = false
    p.textSize = 11f
    c.drawText("Mahndatum: $mahnungDatum", 40f, 195f, p)
    c.drawText("Rechnungsnummer: ${auftrag.rechnungsnummer}", 40f, 215f, p)
    c.drawText("Rechnungsdatum: ${auftrag.rechnungsdatum}", 40f, 235f, p)
    c.drawText("Ursprünglich fällig am: ${auftrag.faelligAm}", 40f, 255f, p)
    c.drawText("Kunde: ${auftrag.kunde}", 40f, 285f, p)
    c.drawText("Adresse: ${auftrag.kundenStrasse}", 40f, 305f, p)
    c.drawText("PLZ und Ort: ${auftrag.kundenOrt}", 40f, 325f, p)

    val rechnungsbetrag = gesamtbetrag(auftrag.stunden, auftrag.material, auftrag.fahrt, auftrag.stundensatz, auftrag.erstellungskosten)
    val gesamt = runde2(rechnungsbetrag + mahngebuehr)
    p.textSize = 13f
    p.isFakeBoldText = true
    c.drawText("Offener Rechnungsbetrag: ${euro(rechnungsbetrag)}", 40f, 365f, p)
    var y = 390f
    if (mahngebuehr > 0.0) {
        c.drawText("Mahngebühr: ${euro(mahngebuehr)}", 40f, y, p)
        y += 25f
        c.drawText("Gesamt zu zahlen: ${euro(gesamt)}", 40f, y, p)
        y += 40f
    } else {
        c.drawText("Gesamt zu zahlen: ${euro(gesamt)}", 40f, y, p)
        y += 40f
    }
    p.isFakeBoldText = false
    p.textSize = 11f

    val text = eigenerText.ifBlank {
        "Zu unserer Rechnung ${auftrag.rechnungsnummer} ist bisher kein Zahlungseingang festgestellt worden. Bitte begleichen Sie den offenen Betrag spätestens bis zum $zahlungsfrist."
    }
    val words = text.split(Regex("\\s+"))
    var line = ""
    for (word in words) {
        val test = if (line.isBlank()) word else "$line $word"
        if (p.measureText(test) > 510f) {
            c.drawText(line, 40f, y, p)
            y += 17f
            line = word
        } else line = test
    }
    if (line.isNotBlank()) {
        c.drawText(line, 40f, y, p)
        y += 17f
    }
    y += 18f
    c.drawText("Neue Zahlungsfrist: $zahlungsfrist", 40f, y, p)
    y += 30f
    c.drawText("Mit freundlichen Grüßen", 40f, y, p)
    y += 18f
    c.drawText(firmenName.ifBlank { "KÜMMERO" }, 40f, y, p)
    pdf.finishPage(page)
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

private fun mahnung1IstUeberfaellig(auftrag: Auftrag, heute: String): Boolean {
    if (!auftrag.mahnung1Erstellt || auftrag.mahnung1Frist.isBlank()) return false
    return try {
        val format = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        val frist = format.parse(auftrag.mahnung1Frist)?.time ?: return false
        val heuteZeit = format.parse(heute)?.time ?: return false
        frist < heuteZeit
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


private fun kuemmeroNaechsteDokumentNummer(prefix: String, jahr: Int, vorhandene: List<String>): String {
    val max = vorhandene.mapNotNull { value ->
        Regex("^${Regex.escape(prefix)}-$jahr-(\\d{4})$").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }.maxOrNull() ?: 0
    return "$prefix-$jahr-${(max + 1).toString().padStart(4, '0')}"
}

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

data class Leistungsposition(
    val name: String,
    val beschreibung: String = "",
    val einheit: String = "Pauschale",
    val preis: Double = 0.0,
    val aktiv: Boolean = true
)

private const val LEISTUNGSPOSITIONEN_KEY = "leistungspositionen"

private val KUEMMERO_STANDARD_LEISTUNGEN = listOf(
    Leistungsposition(
        "Einfache Kleinreparaturen / Ausbesserungen",
        "Nur einfache, nicht wesentliche Ausbesserungsarbeiten im zulässigen Umfang."
    ),
    Leistungsposition("Möbel- und Regalmontage", "Montage von Möbeln und Regalen im zulässigen Umfang; keine Tischlerarbeiten."),
    Leistungsposition(
        "Kleine Tapezier-/Ausbesserungsarbeiten",
        "Nur geringfügige Tapezier- oder Ausbesserungsarbeiten im zulässigen Umfang."
    ),
    Leistungsposition("Rasen mähen", "Gartenpflege / Rasenmähen."),
    Leistungsposition("Haushalts- / Alltagshilfe", "Unterstützung im Haushalt und Alltag; keine Pflege- oder medizinischen Leistungen."),
    Leistungsposition(
        "Schimmel – Reinigung / oberflächliche Stellen",
        "Nur einfache Reinigung bzw. oberflächliche Behandlung im zulässigen Umfang; keine umfassende Schimmel- oder Bauschadensanierung."
    ),
    Leistungsposition(
        "Computer / Router / Smart Home – ohne Elektroarbeiten",
        "Einrichtung und Konfiguration; keine Arbeiten an elektrischen Anlagen, Leitungen, Steckdosen oder Schaltern."
    ),
    Leistungsposition("Anfahrt"),
    Leistungsposition("Arbeitszeit", einheit = "Stunde", preis = 42.0),
    Leistungsposition("Material"),
    Leistungsposition("Eigene Position", "Nur für Tätigkeiten verwenden, die im eigenen Gewerbe tatsächlich zulässig sind.")
)

private fun ladeLeistungspositionen(context: Context): List<Leistungsposition> {
    val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = p.getString(LEISTUNGSPOSITIONEN_KEY, null)
    if (raw.isNullOrBlank()) {
        val defaults = KUEMMERO_STANDARD_LEISTUNGEN
        speichereLeistungspositionen(context, defaults)
        return defaults
    }
    return try {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.optJSONObject(i) ?: JSONObject()
            Leistungsposition(
                name = o.optString("name"),
                beschreibung = o.optString("beschreibung"),
                einheit = o.optString("einheit", "Pauschale"),
                preis = o.optDouble("preis", 0.0),
                aktiv = o.optBoolean("aktiv", true)
            )
        }.filter { it.name.isNotBlank() }
            .filterNot { it.name.equals("Elektro-/Strom-Kleinaufgabe", ignoreCase = true) }
            .map { position ->
                when {
                    position.name.equals("Allgemeine Kleinreparatur", ignoreCase = true) -> position.copy(
                        name = "Einfache Kleinreparaturen / Ausbesserungen",
                        beschreibung = position.beschreibung.ifBlank { "Nur einfache, nicht wesentliche Ausbesserungsarbeiten im zulässigen Umfang." }
                    )
                    position.name.equals("Tapezieren", ignoreCase = true) -> position.copy(
                        name = "Kleine Tapezier-/Ausbesserungsarbeiten",
                        beschreibung = position.beschreibung.ifBlank { "Nur geringfügige Tapezier- oder Ausbesserungsarbeiten im zulässigen Umfang." }
                    )
                    position.name.equals("Schimmelbehandlung", ignoreCase = true) -> position.copy(
                        name = "Schimmel – Reinigung / oberflächliche Stellen",
                        beschreibung = position.beschreibung.ifBlank { "Nur einfache Reinigung bzw. oberflächliche Behandlung im zulässigen Umfang; keine umfassende Schimmel- oder Bauschadensanierung." }
                    )
                    position.name.equals("Smart Home / Computer / Router", ignoreCase = true) -> position.copy(
                        name = "Computer / Router / Smart Home – ohne Elektroarbeiten",
                        beschreibung = position.beschreibung.ifBlank { "Einrichtung und Konfiguration; keine Arbeiten an elektrischen Anlagen, Leitungen, Steckdosen oder Schaltern." }
                    )
                    position.name.equals("Eigene Position", ignoreCase = true) -> position.copy(
                        beschreibung = position.beschreibung.ifBlank { "Nur für Tätigkeiten verwenden, die im eigenen Gewerbe tatsächlich zulässig sind." }
                    )
                    else -> position
                }
            }
            .also { speichereLeistungspositionen(context, it) }
    } catch (_: Exception) {
        KUEMMERO_STANDARD_LEISTUNGEN
    }
}

private fun speichereLeistungspositionen(context: Context, liste: List<Leistungsposition>) {
    val arr = JSONArray()
    liste.forEach { l ->
        arr.put(JSONObject().apply {
            put("name", l.name)
            put("beschreibung", l.beschreibung)
            put("einheit", l.einheit)
            put("preis", l.preis)
            put("aktiv", l.aktiv)
        })
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(LEISTUNGSPOSITIONEN_KEY, arr.toString()).commit()
}


private fun leistungsumfangHinweis(leistung: String): String? {
    val text = leistung.lowercase(Locale.GERMANY)
    val elektro = listOf(
        "elektroinstallation", "elektroinstall", "steckdose", "lichtschalter",
        "sicherungskasten", "sicherung", "unterverteilung", "stromleitung", "stromanschluss",
        "kabel verlegen", "elektrische installation"
    ).any { text.contains(it) }
    val schimmel = text.contains("schimmel")
    val tapezieren = text.contains("tapezier")
    return when {
        elektro -> "Hinweis: Arbeiten an elektrischen Anlagen/Installationen können zum zulassungspflichtigen Elektrotechniker-Handwerk gehören. Nur entsprechend zulässige Tätigkeiten anbieten/ausführen."
        schimmel -> "Hinweis: Keine umfassende Schimmel-Sanierung anbieten. Nur den tatsächlich zulässigen Reinigungs-/oberflächlichen Leistungsumfang ausführen."
        tapezieren -> "Hinweis: Tapezierarbeiten nur in dem geringfügigen bzw. sonst rechtlich zulässigen Umfang anbieten."
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
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unbekannt"
    }
    val heute = remember { Date() }
    val datumFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY) }
    var kunde by remember { mutableStateOf("") }
    var strasse by remember { mutableStateOf("") }
    var ort by remember { mutableStateOf("") }
    var leistung by remember { mutableStateOf("") }
    var stunden by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    var materialBonUri by remember { mutableStateOf("") }
    var fahrtKm by remember { mutableStateOf("") }
    var fahrtKostenProKm by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(FAHRTKOSTEN_PRO_KM_KEY, "0.40") ?: "0.40") }
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
    var hilfeThema by remember { mutableStateOf<String?>(null) }
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
    var globaleSuche by remember { mutableStateOf("") }
    var rechnungArchivSuche by remember { mutableStateOf("") }
    var rechnungArchivJahr by remember { mutableStateOf("Alle") }
    var kostenvoranschlaege by remember { mutableStateOf(ladeKostenvoranschlaege(context)) }
    var leistungspositionen by remember { mutableStateOf(ladeLeistungspositionen(context)) }
    var leistungspositionDialog by remember { mutableStateOf(false) }
    var leistungspositionBearbeiteIndex by remember { mutableStateOf<Int?>(null) }
    var leistungspositionLoeschIndex by remember { mutableStateOf<Int?>(null) }
    var leistungspositionName by remember { mutableStateOf("") }
    var leistungspositionBeschreibung by remember { mutableStateOf("") }
    var leistungspositionEinheit by remember { mutableStateOf("Pauschale") }
    var leistungspositionPreis by remember { mutableStateOf("") }
    var leistungspositionAktiv by remember { mutableStateOf(true) }
    var leistungsPreisIndex by remember { mutableStateOf<Int?>(null) }
    var leistungsPreisEingabe by remember { mutableStateOf("") }
    var kvFormOffen by remember { mutableStateOf(false) }
    var kvBearbeiteIndex by remember { mutableStateOf<Int?>(null) }
    var kvNummer by remember { mutableStateOf(kuemmeroNaechsteDokumentNummer("KV", Calendar.getInstance().get(Calendar.YEAR), kostenvoranschlaege.map { it.nummer })) }
    var kvDatum by remember { mutableStateOf(datumFormat.format(heute)) }
    var kvGueltigBis by remember { mutableStateOf(datumFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 14) }.time)) }
    var kvKunde by remember { mutableStateOf("") }
    var kvStrasse by remember { mutableStateOf("") }
    var kvOrt by remember { mutableStateOf("") }
    var kvLeistung by remember { mutableStateOf("") }
    var leistungsAuswahlZiel by remember { mutableStateOf<String?>(null) }
    var leistungsPreisDialog by remember { mutableStateOf(false) }
    var leistungsPreisName by remember { mutableStateOf("") }
    var leistungsPreisEinheit by remember { mutableStateOf("Pauschale") }
    var leistungsPreisVorschlag by remember { mutableStateOf("") }
    var kvStunden by remember { mutableStateOf("") }
    var kvMaterial by remember { mutableStateOf("") }
    var kvMaterialBonUri by remember { mutableStateOf("") }
    var kvFotosVorher by remember { mutableStateOf<List<String>>(emptyList()) }
    var kvFahrtKm by remember { mutableStateOf("") }
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
    var ungespeicherteAenderungen by remember { mutableStateOf(false) }
    var ausstehendeSeite by remember { mutableStateOf<String?>(null) }
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

    // Pflichtfelder werden vor dem Speichern/Erstellen zentral geprüft.
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
    var mahnung1Index by remember { mutableStateOf<Int?>(null) }
    var mahnung1Datum by remember { mutableStateOf("") }
    var mahnung1Frist by remember { mutableStateOf("") }
    var mahnung1Gebuehr by remember { mutableStateOf("") }
    var mahnung1Text by remember { mutableStateOf("") }
    var mahnung2Index by remember { mutableStateOf<Int?>(null) }
    var mahnung2Datum by remember { mutableStateOf("") }
    var mahnung2Frist by remember { mutableStateOf("") }
    var mahnung2Gebuehr by remember { mutableStateOf("") }
    var mahnung2Text by remember { mutableStateOf("") }

    var mahnungEinstellungenOffen by remember { mutableStateOf(false) }
    var mahnungSpeicherBestaetigungOffen by remember { mutableStateOf(false) }
    var ausstehendeMahnungDateiName by remember { mutableStateOf("") }
    var ausstehendeMahnungTyp by remember { mutableStateOf(0) }
    var ausstehendeMahnungIndex by remember { mutableStateOf<Int?>(null) }
    val mahnungPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    var mahnungEinstellungFristTage by remember {
        mutableStateOf(mahnungPrefs.getInt(MAHNUNG1_FRIST_TAGE_KEY, 7).toString())
    }
    var mahnungEinstellungGebuehr by remember {
        mutableStateOf(
            String.format(
                Locale.GERMANY,
                "%.2f",
                mahnungPrefs.getFloat(MAHNUNG1_GEBUEHR_KEY, 0f).toDouble()
            )
        )
    }
    val testHeuteText = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
    var mahnungEinstellungText by remember { mutableStateOf(standardMahnung1Text(context)) }
    var mahnungTestmodus by remember { mutableStateOf(mahnungPrefs.getBoolean(MAHNUNG_TESTMODUS_KEY, false)) }
    var testMahnung1DialogOffen by remember { mutableStateOf(false) }
    var testMahnungLoeschBestaetigung by remember { mutableStateOf(false) }
    var testMahnung1Erstellt by remember { mutableStateOf(false) }
    var testMahnung1Datum by remember { mutableStateOf(testHeuteText) }
    var testMahnung1Frist by remember { mutableStateOf(standardMahnung1Frist(context)) }
    var testMahnung1Gebuehr by remember { mutableStateOf(mahnungEinstellungGebuehr) }
    var testMahnung1Text by remember { mutableStateOf(mahnungEinstellungText) }

    val testMahnungAuftrag = Auftrag(
        nummer = "TEST-AUFTRAG",
        datum = testHeuteText,
        kunde = "TESTKUNDE – NICHT ECHT",
        kundenStrasse = "Teststraße 1",
        kundenOrt = "58675 Hemer",
        leistung = "Testleistung Mahnung",
        stunden = 10.0,
        material = 0.0,
        fahrt = 0.0,
        stundensatz = 42.0,
        rechnungsnummer = "TEST-RECHNUNG",
        rechnungsdatum = testHeuteText,
        faelligAm = testHeuteText
    )

    var mahnungSpeicherOrdnerUri by remember { mutableStateOf(mahnungPrefs.getString(MAHNUNG_SPEICHERORDNER_URI_KEY, "") ?: "") }

    // Zentraler Ordner für alle von KÜMMERO erzeugten/gespeicherten Dokumente.
    // Die Auswahl gilt für PDFs (Angebote, Rechnungen, Mahnungen, Test-PDFs) und CSV/JSON-Exporte.
    val dokumentePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    var dokumenteSpeicherOrdnerUri by remember {
        mutableStateOf(dokumentePrefs.getString(DOKUMENTE_SPEICHERORDNER_URI_KEY, "") ?: "")
    }
    val dokumenteOrdnerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            dokumentePrefs.edit().putString(DOKUMENTE_SPEICHERORDNER_URI_KEY, uri.toString()).apply()
            dokumenteSpeicherOrdnerUri = uri.toString()
            android.widget.Toast.makeText(context, "Dokumentenordner ausgewählt.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun dokumentSpeicherIntent(mimeType: String, dateiname: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType
        putExtra(Intent.EXTRA_TITLE, dateiname)
        if (dokumenteSpeicherOrdnerUri.isNotBlank()) {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(dokumenteSpeicherOrdnerUri))
        }
    }

    val mahnungOrdnerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Manche Anbieter erlauben keine dauerhafte Berechtigung; die aktuelle Auswahl bleibt trotzdem gültig.
            }
            mahnungPrefs.edit().putString(MAHNUNG_SPEICHERORDNER_URI_KEY, uri.toString()).apply()
            mahnungSpeicherOrdnerUri = uri.toString()
            android.widget.Toast.makeText(context, "Mahnung-Ordner ausgewählt.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }


    fun speichereMahnungInAusgewaehltenOrdner(typ: Int, index: Int, dateiname: String): Boolean {
        if (mahnungSpeicherOrdnerUri.isBlank()) return false
        val a = auftraege.getOrNull(index) ?: return false
        return try {
            val treeUri = Uri.parse(mahnungSpeicherOrdnerUri)
            val mime = "application/pdf"
            val zielUri = DocumentsContract.createDocument(
                context.contentResolver,
                treeUri,
                mime,
                dateiname
            ) ?: throw Exception("Datei konnte nicht angelegt werden")
            val pdf = if (typ == 1) {
                erstelleMahnung1Pdf(context, a, mahnung1Datum.trim(), mahnung1Frist.trim(), zahl(mahnung1Gebuehr), mahnung1Text.trim())
            } else {
                erstelleMahnung2Pdf(context, a, mahnung2Datum.trim(), mahnung2Frist.trim(), zahl(mahnung2Gebuehr), mahnung2Text.trim())
            }
            context.contentResolver.openOutputStream(zielUri)?.use { out -> pdf.writeTo(out) } ?: throw Exception("Datei konnte nicht geöffnet werden")
            pdf.close()
            val aktualisiert = if (typ == 1) {
                a.copy(
                    mahnung1Datum = mahnung1Datum.trim(),
                    mahnung1Frist = mahnung1Frist.trim(),
                    mahnung1Gebuehr = zahl(mahnung1Gebuehr),
                    mahnung1Text = mahnung1Text.trim(),
                    mahnung1Erstellt = true
                )
            } else {
                a.copy(
                    mahnung2Datum = mahnung2Datum.trim(),
                    mahnung2Frist = mahnung2Frist.trim(),
                    mahnung2Gebuehr = zahl(mahnung2Gebuehr),
                    mahnung2Text = mahnung2Text.trim(),
                    mahnung2Erstellt = true
                )
            }
            auftraege = auftraege.toMutableList().apply { set(index, aktualisiert) }
            speichereAuftraege(context, auftraege)
            if (typ == 1) mahnung1Index = null else mahnung2Index = null
            android.widget.Toast.makeText(context, "Mahnung gespeichert.", android.widget.Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Mahnung konnte nicht gespeichert werden.", android.widget.Toast.LENGTH_LONG).show()
            false
        }
    }

    val testMahnungLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri ->
            try {
                val pdf = erstelleMahnung1Pdf(context, testMahnungAuftrag, testMahnung1Datum.trim(), testMahnung1Frist.trim(), zahl(testMahnung1Gebuehr), testMahnung1Text.trim())
                context.contentResolver.openOutputStream(uri)?.use { out -> pdf.writeTo(out) } ?: throw Exception("Datei konnte nicht geöffnet werden")
                pdf.close()
                testMahnung1Erstellt = true
                testMahnung1DialogOffen = false
                android.widget.Toast.makeText(context, "Test-Mahnung erstellt – echte Rechnungsdaten wurden nicht verändert.", android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "Test-Mahnung konnte nicht erstellt werden.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val mahnung1Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri ->
            val index = mahnung1Index
            val a = index?.let { i -> auftraege.getOrNull(i) }
            if (a != null) {
                try {
                    val pdf = erstelleMahnung1Pdf(context, a, mahnung1Datum.trim(), mahnung1Frist.trim(), zahl(mahnung1Gebuehr), mahnung1Text.trim())
                    context.contentResolver.openOutputStream(uri)?.use { out -> pdf.writeTo(out) } ?: throw Exception("Datei konnte nicht geöffnet werden")
                    pdf.close()
                    val aktualisiert = a.copy(
                        mahnung1Datum = mahnung1Datum.trim(),
                        mahnung1Frist = mahnung1Frist.trim(),
                        mahnung1Gebuehr = zahl(mahnung1Gebuehr),
                        mahnung1Text = mahnung1Text.trim(),
                        mahnung1Erstellt = true
                    )
                    auftraege = auftraege.toMutableList().apply { set(index, aktualisiert) }
                    speichereAuftraege(context, auftraege)
                    android.widget.Toast.makeText(context, "1. Mahnung gespeichert.", 0).show()
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, "1. Mahnung konnte nicht erstellt werden.", 1).show()
                }
            }
        }
        mahnung1Index = null
    }

    val mahnung2Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri ->
            val index = mahnung2Index
            val a = index?.let { i -> auftraege.getOrNull(i) }
            if (a != null) {
                try {
                    val pdf = erstelleMahnung2Pdf(context, a, mahnung2Datum.trim(), mahnung2Frist.trim(), zahl(mahnung2Gebuehr), mahnung2Text.trim())
                    context.contentResolver.openOutputStream(uri)?.use { out -> pdf.writeTo(out) } ?: throw Exception("Datei konnte nicht geöffnet werden")
                    pdf.close()
                    val aktualisiert = a.copy(
                        mahnung2Datum = mahnung2Datum.trim(),
                        mahnung2Frist = mahnung2Frist.trim(),
                        mahnung2Gebuehr = zahl(mahnung2Gebuehr),
                        mahnung2Text = mahnung2Text.trim(),
                        mahnung2Erstellt = true
                    )
                    auftraege = auftraege.toMutableList().apply { set(index, aktualisiert) }
                    speichereAuftraege(context, auftraege)
                    android.widget.Toast.makeText(context, "2. Mahnung gespeichert.", 0).show()
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, "2. Mahnung konnte nicht erstellt werden.", 1).show()
                }
            }
        }
        mahnung2Index = null
    }

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
    var dropboxBestaetigung by remember { mutableStateOf(false) }
    var abschlusspruefungIndex by remember { mutableStateOf<Int?>(null) }
    val backupScope = rememberCoroutineScope()

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { selectedUri ->
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
    val createDataExport = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { selectedUri ->
            backupScope.launch {
                val csv = buildString {
                    append("KÜMMERO Datenexport\n")
                    append("Aufträge\n")
                    append(listOf("Nummer", "Datum", "Leistungsdatum", "Kunde", "Adresse", "Ort", "Leistung", "Stunden", "Material", "Fahrt", "Stundensatz", "Betrag", "Status", "Zahlungsstatus", "Rechnungsnummer", "Rechnungsdatum", "Fällig am").joinToString(";") { csvFeld(it) })
                    append("\n")
                    auftraege.forEach { a ->
                        append(listOf(
                            a.nummer, a.datum, a.leistungsdatum, a.kunde, a.kundenStrasse, a.kundenOrt,
                            a.leistung, a.stunden.toString(), a.material.toString(), a.fahrt.toString(), a.stundensatz.toString(),
                            gesamtbetrag(a.stunden, a.material, a.fahrt, a.stundensatz, a.erstellungskosten).toString(),
                            a.status, a.zahlungsstatus, a.rechnungsnummer, a.rechnungsdatum, a.faelligAm
                        ).joinToString(";") { csvFeld(it) })
                        append("\n")
                    }
                    append("\nKunden\n")
                    append(listOf("Name", "Adresse", "Ort", "Telefon", "E-Mail").joinToString(";") { csvFeld(it) })
                    append("\n")
                    kunden.forEach { k ->
                        append(listOf(k.name, k.adresse, k.ort, k.telefon, k.email).joinToString(";") { csvFeld(it) })
                        append("\n")
                    }
                }
                val ok = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(selectedUri, "wt")?.use { out ->
                            out.write(csv.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } ?: throw Exception("Datei konnte nicht geöffnet werden")
                        true
                    } catch (_: Exception) { false }
                }
                android.widget.Toast.makeText(
                    context,
                    if (ok) "Datenexport gespeichert." else "Datenexport fehlgeschlagen.",
                    if (ok) 0 else 1
                ).show()
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
                val leistungsArr = obj.optJSONArray("leistungspositionen")
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
                    .putString(KOSTENVORANSCHLAEGE_KEY, kvArr.toString())
                    .apply { if (leistungsArr != null) putString(LEISTUNGSPOSITIONEN_KEY, leistungsArr.toString()) }
                    .commit()
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
                leistungspositionen = ladeLeistungspositionen(context)
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
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri ->
            val a = auftragFuerPdf
            val pdf = if (a != null) {
                erstellePdf(
                    context, nummer, datum, gueltigBis,
                    a.kunde, a.kundenStrasse, a.kundenOrt, a.leistung,
                    a.stunden, a.material, a.fahrt, a.stundensatz, a.unterschriftPfad, a.unterschriftDatum,
                    a.fotosVorher, a.fotosNachher, "AUFTRAG"
                )
            } else {
                erstellePdf(
                    context, nummer, datum, gueltigBis, kunde, strasse, ort, leistung,
                    zahl(stunden), zahl(material), zahl(fahrt), zahl(stundensatz, 42.0), unterschriftPfad, unterschriftDatum,
                    fotosVorher, fotosNachher, "AUFTRAG"
                )
            }
            context.contentResolver.openOutputStream(uri)?.use { out -> pdf.writeTo(out) }
            pdf.close()
            auftragFuerPdf = null
        }
    }


    val rechnungLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri ->
            val index = rechnungFuerIndex
            val a = index?.let { i -> auftraege.getOrNull(i) }
            if (a != null) {
                val rechnungsPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val rechnungsStrasse = rechnungsPrefs.getString(FIRMENSTRASSE_KEY, "") ?: ""
                val rechnungsPlzOrt = rechnungsPrefs.getString(FIRMENPLZORT_KEY, "") ?: ""
                val rechnungsSteuer = rechnungsPrefs.getString(STEUERNUMMER_KEY, "") ?: ""
                val rechnungsName = rechnungsPrefs.getString(FIRMENNAME_KEY, "") ?: ""
                val fehlendeRechnungsfelder = mutableListOf<String>()
                if (rechnungsName.isBlank()) fehlendeRechnungsfelder.add("Unternehmensname / Inhaber")
                if (rechnungsStrasse.isBlank()) fehlendeRechnungsfelder.add("Unternehmensstraße / Hausnummer")
                if (rechnungsPlzOrt.isBlank()) fehlendeRechnungsfelder.add("Unternehmens-PLZ / Ort")
                if (rechnungsSteuer.isBlank()) fehlendeRechnungsfelder.add("Steuernummer / USt-ID / KU-IdNr.")
                if (a.kunde.trim().isBlank()) fehlendeRechnungsfelder.add("Kundenname")
                if (a.kundenStrasse.trim().isBlank()) fehlendeRechnungsfelder.add("Kundenadresse")
                if (a.kundenOrt.trim().isBlank()) fehlendeRechnungsfelder.add("Kunden-PLZ / Ort")
                if (a.leistung.trim().isBlank()) fehlendeRechnungsfelder.add("Leistung")
                if (a.leistungsdatum.ifBlank { a.terminDatum.ifBlank { a.datum } }.trim().isBlank()) fehlendeRechnungsfelder.add("Leistungsdatum")
                if (fehlendeRechnungsfelder.isNotEmpty()) {
                    android.widget.Toast.makeText(context, "Rechnung nicht erstellt. Pflichtfelder fehlen: ${fehlendeRechnungsfelder.joinToString(", ")}", android.widget.Toast.LENGTH_LONG).show()
                    rechnungFuerIndex = null
                    return@rememberLauncherForActivityResult
                }
                try {
                    val vorhandeneRechnungsnummern = auftraege.map { it.rechnungsnummer }
                    val rechnungsnummer = naechsteRechnungsnummer(context, vorhandeneRechnungsnummern)
                    if (vorhandeneRechnungsnummern.any { it.equals(rechnungsnummer, ignoreCase = true) }) {
                        android.widget.Toast.makeText(context, "Fehler: Rechnungsnummer bereits vergeben. Keine Rechnung gespeichert.", android.widget.Toast.LENGTH_LONG).show()
                        rechnungFuerIndex = null
                        return@rememberLauncherForActivityResult
                    }
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
                    context.contentResolver.openOutputStream(uri)?.use { out -> pdf.writeTo(out) }
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
    val fahrtKosten = runde2(zahl(fahrtKm) * zahl(fahrtKostenProKm, 0.40))
    val rate = zahl(stundensatz, 42.0)
    val gesamt = gesamtbetrag(arbeitsstunden, materialKosten, fahrtKosten, rate)
    val umsatz = auftraege.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) }

    val heuteText = datumFormat.format(Date())
    val termineHeute = auftraege.filter { it.terminDatum == heuteText }
        .sortedBy { it.terminUhrzeit }
    val offeneAuftraege = auftraege.count { it.status != "Abgerechnet" }
    val offeneZahlungen = auftraege.filter { it.zahlungsstatus != "Bezahlt" }
    val offeneZahlungSumme = offeneZahlungen.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) }
    val ueberfaelligeRechnungen = auftraege.count { rechnungIstUeberfaellig(it, heuteText) }
    val naechsteTermine = auftraege.filter { it.terminDatum.isNotBlank() }
        .sortedWith(compareBy<Auftrag> {
            try { datumFormat.parse(it.terminDatum)?.time ?: Long.MAX_VALUE } catch (_: Exception) { Long.MAX_VALUE }
        }.thenBy { it.terminUhrzeit })
        .take(8)
    val naechsterTermin = naechsteTermine.firstOrNull { it.terminDatum != heuteText }
    val abgearbeiteteAuftraege = auftraege.count { it.status == "Erledigt" || it.status == "Abgerechnet" }
    val heuteZuErledigen = auftraege.filter { a ->
        a.terminDatum == heuteText ||
        rechnungIstUeberfaellig(a, heuteText) ||
        (a.status == "Erledigt" && a.rechnungsnummer.isBlank()) ||
        a.status == "In Bearbeitung"
    }.distinctBy { it.nummer.ifBlank { "${it.kunde}|${it.datum}|${it.leistung}" } }

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


    if (dropboxBestaetigung) {
        AlertDialog(
            onDismissRequest = { dropboxBestaetigung = false },
            title = { Text("Dropbox-Sicherung bestätigen") },
            text = {
                Text("Soll jetzt eine aktuelle KÜMMERO-Datensicherung an Dropbox übergeben werden? Es wird erst nach deiner Bestätigung die Dropbox-App geöffnet.")
            },
            confirmButton = {
                TextButton(onClick = {
                    dropboxBestaetigung = false
                    teileBackupMitDropbox(context)
                }) { Text("Ja, an Dropbox") }
            },
            dismissButton = {
                TextButton(onClick = { dropboxBestaetigung = false }) { Text("Abbrechen") }
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
                    if (arbeitszeitNeu.trim().isBlank()) {
                        android.widget.Toast.makeText(context, "Bitte Arbeitszeit eingeben.", android.widget.Toast.LENGTH_SHORT).show()
                    } else if (idx != null) {
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

    if (mahnungEinstellungenOffen) {
        AlertDialog(
            onDismissRequest = { mahnungEinstellungenOffen = false },
            title = { Text("⚙ Mahnung-Einstellungen") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Diese Werte sind nur Voreinstellungen. Bei jeder einzelnen Mahnung kannst du sie trotzdem ändern.",
                        color = KuemmeroText
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = KuemmeroMint),
                        border = BorderStroke(1.dp, KuemmeroGreen),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("📁 Mahnung-Speicherordner", fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                            Text(
                                if (mahnungSpeicherOrdnerUri.isBlank())
                                    "Kein Ordner ausgewählt. Beim Speichern fragt Android nach dem Ziel."
                                else
                                    "Ein Ordner ist ausgewählt. Vor jeder Speicherung wird trotzdem nochmals gefragt." ,
                                style = MaterialTheme.typography.bodySmall,
                                color = KuemmeroText
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { mahnungOrdnerLauncher.launch(null) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(22.dp),
                                    border = BorderStroke(2.dp, KuemmeroGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                ) { Text(if (mahnungSpeicherOrdnerUri.isBlank()) "Ordner auswählen" else "Ordner ändern") }
                                if (mahnungSpeicherOrdnerUri.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            mahnungPrefs.edit().remove(MAHNUNG_SPEICHERORDNER_URI_KEY).apply()
                                            mahnungSpeicherOrdnerUri = ""
                                            android.widget.Toast.makeText(context, "Mahnung-Ordner entfernt.", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(22.dp),
                                        border = BorderStroke(1.dp, KuemmeroError),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroError)
                                    ) { Text("Entfernen") }
                                }
                            }
                        }
                    }
                    if (mahnungEinstellungFristTage.toIntOrNull()?.coerceAtLeast(0) == 0) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = KuemmeroMint),
                            border = BorderStroke(2.dp, KuemmeroGreen),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                "0 Tage eingestellt – die neue Zahlungsfrist endet am Mahntag.",
                                modifier = Modifier.padding(12.dp),
                                color = KuemmeroGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Testmodus für Mahnungen", fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                            Text(
                                "Nur aktivieren, wenn du Erstellen → Ändern → Löschen testen möchtest. Testdaten verändern keine echte Rechnung.",
                                style = MaterialTheme.typography.bodySmall,
                                color = KuemmeroText
                            )
                        }
                        Switch(
                            checked = mahnungTestmodus,
                            onCheckedChange = {
                                mahnungTestmodus = it
                                mahnungPrefs.edit().putBoolean(MAHNUNG_TESTMODUS_KEY, it).apply()
                                if (!it) testMahnung1Erstellt = false
                            }
                        )
                    }
                    OutlinedTextField(
                        value = mahnungEinstellungFristTage,
                        onValueChange = { mahnungEinstellungFristTage = it },
                        label = { Text("Zahlungsfrist nach 1. Mahnung (Tage)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mahnungEinstellungGebuehr,
                        onValueChange = { mahnungEinstellungGebuehr = it },
                        label = { Text("Mahngebühr (€)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mahnungEinstellungText,
                        onValueChange = { mahnungEinstellungText = it },
                        label = { Text("Standard-Mahnungstext") },
                        minLines = 5,
                        maxLines = 8,
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val tage = mahnungEinstellungFristTage.toIntOrNull()?.coerceAtLeast(0) ?: 7
                    val gebuehr = zahl(mahnungEinstellungGebuehr)
                    val text = mahnungEinstellungText.trim()
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(MAHNUNG1_FRIST_TAGE_KEY, tage)
                        .putFloat(MAHNUNG1_GEBUEHR_KEY, gebuehr.toFloat())
                        .putString(MAHNUNG1_TEXT_KEY, text)
                        .putBoolean(MAHNUNG_TESTMODUS_KEY, mahnungTestmodus)
                        .apply()
                    mahnungEinstellungFristTage = tage.toString()
                    mahnungEinstellungGebuehr = String.format(Locale.GERMANY, "%.2f", gebuehr)
                    mahnungEinstellungText = text
                    mahnungEinstellungenOffen = false
                    android.widget.Toast.makeText(context, "Mahnung-Einstellungen gespeichert.", 0).show()
                }) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { mahnungEinstellungenOffen = false }) { Text("Abbrechen") }
            }
        )
    }

    if (mahnungSpeicherBestaetigungOffen) {
        AlertDialog(
            onDismissRequest = { mahnungSpeicherBestaetigungOffen = false },
            title = { Text("Mahnung wirklich speichern?") },
            text = {
                Text(
                    "Die PDF wird jetzt nur nach deiner Bestätigung im ausgewählten Mahnung-Ordner gespeichert.\n\nDatei: $ausstehendeMahnungDateiName\n\nOhne Bestätigung wird nichts gespeichert.",
                    color = KuemmeroText
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = ausstehendeMahnungIndex?.let {
                        speichereMahnungInAusgewaehltenOrdner(ausstehendeMahnungTyp, it, ausstehendeMahnungDateiName)
                    } ?: false
                    if (ok) {
                        mahnungSpeicherBestaetigungOffen = false
                        ausstehendeMahnungIndex = null
                        ausstehendeMahnungDateiName = ""
                        ausstehendeMahnungTyp = 0
                    }
                }) { Text("Jetzt speichern") }
            },
            dismissButton = { TextButton(onClick = {
                mahnungSpeicherBestaetigungOffen = false
                ausstehendeMahnungIndex = null
                ausstehendeMahnungDateiName = ""
                ausstehendeMahnungTyp = 0
            }) { Text("Nicht speichern") } }
        )
    }

    if (testMahnung1DialogOffen && mahnungTestmodus) {
        AlertDialog(
            onDismissRequest = { testMahnung1DialogOffen = false },
            title = { Text("Test-Mahnung erstellen / ändern") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("TESTDATEN – keine echte Rechnung wird verändert.", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                    Text("Rechnung: TEST-RECHNUNG · Kunde: TESTKUNDE – NICHT ECHT")
                    OutlinedTextField(testMahnung1Datum, { testMahnung1Datum = it }, label = { Text("Mahndatum") }, singleLine = true, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(testMahnung1Frist, { testMahnung1Frist = it }, label = { Text("Neue Zahlungsfrist") }, singleLine = true, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(testMahnung1Gebuehr, { testMahnung1Gebuehr = it }, label = { Text("Mahngebühr (€)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(testMahnung1Text, { testMahnung1Text = it }, label = { Text("Mahntext (änderbar)") }, minLines = 4, maxLines = 7, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (testMahnung1Datum.isBlank() || testMahnung1Frist.isBlank()) {
                        android.widget.Toast.makeText(context, "Bitte Mahndatum und Zahlungsfrist eingeben.", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        testMahnungLauncher.launch(dokumentSpeicherIntent("application/pdf", "KÜMMERO-TEST-Mahnung-${testMahnung1Datum.replace('.', '-')}.pdf"))
                    }
                }) { Text(if (testMahnung1Erstellt) "Test-PDF neu erstellen" else "Test-PDF erstellen") }
            },
            dismissButton = { TextButton(onClick = { testMahnung1DialogOffen = false }) { Text("Abbrechen") } }
        )
    }

    if (testMahnungLoeschBestaetigung && mahnungTestmodus) {
        AlertDialog(
            onDismissRequest = { testMahnungLoeschBestaetigung = false },
            title = { Text("Test-Mahnung löschen?") },
            text = { Text("Nur die Test-Mahnung wird entfernt. Echte Rechnungsdaten bleiben unverändert.") },
            confirmButton = {
                TextButton(onClick = {
                    testMahnung1Erstellt = false
                    testMahnungLoeschBestaetigung = false
                    testMahnung1Datum = testHeuteText
                    testMahnung1Frist = standardMahnung1Frist(context)
                    testMahnung1Gebuehr = mahnungEinstellungGebuehr
                    testMahnung1Text = mahnungEinstellungText
                    android.widget.Toast.makeText(context, "Test-Mahnung gelöscht.", android.widget.Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.textButtonColors(contentColor = KuemmeroError)) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { testMahnungLoeschBestaetigung = false }) { Text("Abbrechen") } }
        )
    }

    if (mahnung1Index != null) {
        AlertDialog(
            onDismissRequest = { mahnung1Index = null },
            title = { Text("1. Mahnung erstellen") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("Alle Angaben können vor dem Erstellen geändert werden.")
                    OutlinedTextField(mahnung1Datum, { mahnung1Datum = it }, label = { Text("Mahndatum") }, singleLine = true, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(mahnung1Frist, { mahnung1Frist = it }, label = { Text("Neue Zahlungsfrist") }, singleLine = true, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(mahnung1Gebuehr, { mahnung1Gebuehr = it }, label = { Text("Mahngebühr (€)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben, modifier = Modifier.fillMaxWidth())
                    if (mahnungEinstellungFristTage.toIntOrNull()?.coerceAtLeast(0) == 0) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = KuemmeroMint),
                            border = BorderStroke(1.dp, KuemmeroGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "0 Tage eingestellt – die neue Zahlungsfrist endet am Mahntag.",
                                modifier = Modifier.padding(10.dp),
                                color = KuemmeroGreen,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    OutlinedTextField(mahnung1Text, { mahnung1Text = it }, label = { Text("Mahntext (änderbar)") }, minLines = 4, maxLines = 7, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val index = mahnung1Index
                    val a = index?.let { auftraege.getOrNull(it) }
                    if (a == null) mahnung1Index = null
                    else if (mahnung1Datum.isBlank() || mahnung1Frist.isBlank()) {
                        android.widget.Toast.makeText(context, "Bitte Mahndatum und Zahlungsfrist eingeben.", 0).show()
                    } else {
                        val name = a.kunde.ifBlank { "Kunde" }.replace("/", "-")
                        val dateiname = "KÜMMERO-1-Mahnung-${a.rechnungsnummer}-$name.pdf"
                        if (mahnungSpeicherOrdnerUri.isNotBlank()) {
                            ausstehendeMahnungTyp = 1
                            ausstehendeMahnungIndex = index
                            ausstehendeMahnungDateiName = dateiname
                            mahnungSpeicherBestaetigungOffen = true
                        } else {
                            mahnung1Launcher.launch(dokumentSpeicherIntent("application/pdf", dateiname))
                        }
                    }
                }) { Text("PDF erstellen") }
            },
            dismissButton = { TextButton(onClick = { mahnung1Index = null }) { Text("Abbrechen") } }
        )
    }

    if (mahnung2Index != null) {
        AlertDialog(
            onDismissRequest = { mahnung2Index = null },
            title = { Text("2. Mahnung erstellen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val index = mahnung2Index
                    val a = index?.let { auftraege.getOrNull(it) }
                    if (a != null) {
                        Text("Kunde: ${a.kunde}", fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                        Text("Rechnung: ${a.rechnungsnummer}")
                        OutlinedTextField(mahnung2Datum, { mahnung2Datum = it }, label = { Text("Mahndatum") }, singleLine = true, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(mahnung2Frist, { mahnung2Frist = it }, label = { Text("Neue Zahlungsfrist") }, singleLine = true, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(mahnung2Gebuehr, { mahnung2Gebuehr = it }, label = { Text("Mahngebühr (€)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(mahnung2Text, { mahnung2Text = it }, label = { Text("Mahntext (änderbar)") }, minLines = 4, maxLines = 7, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val index = mahnung2Index
                    val a = index?.let { auftraege.getOrNull(it) }
                    if (a == null) mahnung2Index = null
                    else if (mahnung2Datum.isBlank() || mahnung2Frist.isBlank()) {
                        android.widget.Toast.makeText(context, "Bitte Mahndatum und Zahlungsfrist eingeben.", 0).show()
                    } else {
                        val name = a.kunde.ifBlank { "Kunde" }.replace("/", "-")
                        val dateiname = "KÜMMERO-2-Mahnung-${a.rechnungsnummer}-$name.pdf"
                        if (mahnungSpeicherOrdnerUri.isNotBlank()) {
                            ausstehendeMahnungTyp = 2
                            ausstehendeMahnungIndex = index
                            ausstehendeMahnungDateiName = dateiname
                            mahnungSpeicherBestaetigungOffen = true
                        } else {
                            mahnung2Launcher.launch(dokumentSpeicherIntent("application/pdf", dateiname))
                        }
                    }
                }) { Text("PDF erstellen") }
            },
            dismissButton = { TextButton(onClick = { mahnung2Index = null }) { Text("Abbrechen") } }
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
                    Text("* Pflichtfelder", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        neuerKundenName,
                        { neuerKundenName = it },
                        label = { Text("Kunde *") },
                        singleLine = true,
                        isError = neuerKundenName.isBlank(),
                        supportingText = { if (neuerKundenName.isBlank()) Text("Pflichtfeld") }
                    )
                    OutlinedTextField(
                        neuerKundenAdresse,
                        { neuerKundenAdresse = it },
                        label = { Text("Adresse *") },
                        singleLine = true,
                        isError = neuerKundenAdresse.isBlank(),
                        supportingText = { if (neuerKundenAdresse.isBlank()) Text("Pflichtfeld") }
                    )
                    OutlinedTextField(
                        neuerKundenOrt,
                        { neuerKundenOrt = it },
                        label = { Text("PLZ und Ort *") },
                        singleLine = true,
                        isError = neuerKundenOrt.isBlank(),
                        supportingText = { if (neuerKundenOrt.isBlank()) Text("Pflichtfeld") }
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
                Button(
                    enabled = neuerKundenName.trim().isNotBlank() && neuerKundenAdresse.trim().isNotBlank() && neuerKundenOrt.trim().isNotBlank(),
                    onClick = {
                    if (neuerKundenName.trim().isBlank() || neuerKundenAdresse.trim().isBlank() || neuerKundenOrt.trim().isBlank()) {
                        android.widget.Toast.makeText(context, "Bitte alle Pflichtfelder (*) ausfüllen.", 0).show()
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
                    val mahnungen = rechnungen.filter { it.mahnung1Erstellt || it.mahnung2Erstellt }
                    Text("Rechnungen: ${rechnungen.size}", fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            mahnungen.any { it.mahnung2Erstellt } -> "Mahnung: 2. Mahnung vorhanden"
                            mahnungen.any { it.mahnung1Erstellt } -> "Mahnung: 1. Mahnung vorhanden"
                            else -> "Mahnung: keine"
                        },
                        color = if (mahnungen.any { it.mahnung1Erstellt || it.mahnung2Erstellt }) KuemmeroError else KuemmeroGreen,
                        fontWeight = FontWeight.Bold
                    )
                    rechnungen.takeLast(5).reversed().forEach { r ->
                        val mahnstatus = when {
                            r.mahnung2Erstellt -> "2. Mahnung"
                            r.mahnung1Erstellt -> "1. Mahnung"
                            else -> "keine Mahnung"
                        }
                        Text(
                            "${r.rechnungsnummer} · ${euro(gesamtbetrag(r.stunden, r.material, r.fahrt, r.stundensatz, r.erstellungskosten))} · ${if (r.zahlungsstatus == "Bezahlt") "Bezahlt" else "Offen"} · $mahnstatus",
                            color = if (r.zahlungsstatus == "Bezahlt") KuemmeroGreen else if (r.mahnung2Erstellt || r.mahnung1Erstellt) KuemmeroError else KuemmeroText,
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
        val zuLoeschenderAuftrag = auftraege.getOrNull(index)
        val hatRechnung = !zuLoeschenderAuftrag?.rechnungsnummer.isNullOrBlank()

        AlertDialog(
            onDismissRequest = { loeschIndex = null },
            title = { Text(if (hatRechnung) "Auftrag kann nicht gelöscht werden" else "Auftrag endgültig löschen?") },
            text = { Text(if (hatRechnung) "Für diesen Auftrag wurde bereits eine Rechnung erstellt. Der Auftrag bleibt zur Dokumentation erhalten." else "Dieser Auftrag wird vollständig aus der KÜMMERO-Auftragsliste entfernt. Die Löschung kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                if (!hatRechnung) {
                    TextButton(onClick = {
                        auftraege = auftraege.toMutableList().apply { removeAt(index) }
                        speichereAuftraege(context, auftraege)
                        timerIndex = null
                        timerSekunden = 0L
                        auftragDetailIndex = null
                        auftragFormOffen = false
                        bearbeiteIndex = null
                        loeschIndex = null
                        android.widget.Toast.makeText(context, "Auftrag endgültig gelöscht.", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Row(verticalAlignment = Alignment.CenterVertically) { Text("ENDGÜLTIG LÖSCHEN"); Spacer(Modifier.width(6.dp)); Text("ⓘ") } }
                }
            },
            dismissButton = { TextButton(onClick = { loeschIndex = null }) { Text(if (hatRechnung) "OK" else "Abbrechen") } }
        )
    }

    hilfeThema?.let { thema ->
        val hilfeText = when (thema) {
            "Speichern" -> "Speichert die eingegebenen Daten dauerhaft in der KÜMMERO-App."
            "Löschen" -> "Entfernt den ausgewählten Auftrag dauerhaft. Diese Aktion kann nicht rückgängig gemacht werden."
            "Bearbeiten" -> "Ändere die Daten und tippe anschließend auf Speichern."
            "Nummer" -> "Die Nummer dient zur eindeutigen Zuordnung des Dokuments. Bereits vergebene Nummern können nicht doppelt verwendet werden."
            "Rechnung" -> "Erstellt eine Rechnung aus den gespeicherten Auftragsdaten. Bereits erstellte Rechnungen sollten nicht einfach gelöscht werden."
            "PDF" -> "Erstellt eine PDF-Datei, die du speichern, drucken oder weitergeben kannst."
            "Backup" -> "Sichert deine KÜMMERO-Daten, damit du sie später wiederherstellen kannst."
            else -> "Hier findest du eine kurze Erklärung zu dieser Funktion."
        }
        AlertDialog(
            onDismissRequest = { hilfeThema = null },
            title = { Text("ⓘ Hilfe: $thema") },
            text = { Text(hilfeText) },
            confirmButton = {
                TextButton(onClick = { hilfeThema = null }) { Text("OK") }
            }
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

    fun versucheSeitenwechsel(ziel: String) {
        if (auftragFormOffen && ungespeicherteAenderungen) ausstehendeSeite = ziel else hauptseite = ziel
    }

    ausstehendeSeite?.let { ziel ->
        AlertDialog(
            onDismissRequest = { ausstehendeSeite = null },
            title = { Text("Änderungen noch nicht gespeichert") },
            text = { Text("Du hast Änderungen am Auftrag vorgenommen. Was möchtest du tun?") },
            confirmButton = {
                TextButton(onClick = { ausstehendeSeite = null }) { Text("Speichern") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        ungespeicherteAenderungen = false
                        auftragFormOffen = false
                        bearbeiteIndex = null
                        val zielSeite = ausstehendeSeite
                        ausstehendeSeite = null
                        if (zielSeite != null) hauptseite = zielSeite
                    }) { Text("Verwerfen") }
                    TextButton(onClick = { ausstehendeSeite = null }) { Text("Abbrechen") }
                }
            }
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
                                Row(verticalAlignment = Alignment.CenterVertically) { Text("KÜMMERO", fontWeight = FontWeight.Bold, color = Color.White); Spacer(Modifier.width(8.dp)); TextButton(onClick = { hilfeThema = "Speichern" }) { Text("ⓘ", color = Color.White) } }
                                Text(
                                    "Haus & Alltag – wir kümmern uns.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFFD9F2E3)
                                )
                                Text(
                                    "Version $appVersion",
                                    style = MaterialTheme.typography.labelSmall,
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
                        Triple("KV", "€", "Kostenvoranschläge"),
                        Triple("Kunden", "♙", "Kunden"),
                        Triple("Mahnungen", "!", "Mahnungen"),
                        Triple("Mehr", "⋯", "Mehr")
                    ).forEach { (label, iconText, page) ->
                        NavigationBarItem(
                            selected = hauptseite == page,
                            onClick = {
                                versucheSeitenwechsel(page)
                                if (page == "Aufträge" && !(auftragFormOffen && ungespeicherteAenderungen)) {
                                    // Beim Öffnen von „Aufträge“ immer die Übersicht zeigen.
                                    auftragDetailIndex = null
                                    auftragFormOffen = false
                                    bearbeiteIndex = null
                                    loeschIndex = null
                                }
                            },
                            icon = { Text(iconText, fontSize = 20.sp) },
                            label = { Text(label, fontSize = 10.sp, maxLines = 2, textAlign = TextAlign.Center) },
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
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .kuemmeroHauptseitenWischen(hauptseite) { versucheSeitenwechsel(it) },
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
                        nummer, { nummer = it; ungespeicherteAenderungen = true },
                        label = { Text("Auftragsnummer *") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        strasse, { strasse = it; ungespeicherteAenderungen = true },
                        label = { Text("Adresse *") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        datum, { datum = it; ungespeicherteAenderungen = true },
                        label = { Text("Datum *") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        leistungsdatum, { leistungsdatum = it; ungespeicherteAenderungen = true },
                        label = { Text("Leistungsdatum *") },
                        placeholder = { Text("TT.MM.JJJJ") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        kunde, { kunde = it; ungespeicherteAenderungen = true },
                        label = { Text("Kunde *") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        ort, { ort = it; ungespeicherteAenderungen = true },
                        label = { Text("PLZ und Ort *") },
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Leistungsposition", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = { leistungsAuswahlZiel = "auftrag" },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(26.dp),
                            border = BorderStroke(2.dp, KuemmeroGreen),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                        ) {
                            Text(if (leistung.isBlank()) "Position auswählen" else "Ausgewählt: $leistung", fontWeight = FontWeight.Bold)
                        }
                        OutlinedTextField(
                            leistung, { leistung = it; ungespeicherteAenderungen = true },
                            label = { Text("Leistung / eigene Beschreibung *") },
                            colors = feldFarben,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
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
                        stunden, { stunden = it; ungespeicherteAenderungen = true },
                        label = { Text("Arbeitsstunden") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        material, { material = it; ungespeicherteAenderungen = true },
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
                        fahrtKm, { fahrtKm = it; ungespeicherteAenderungen = true },
                        label = { Text("Kilometer") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text("Fahrkosten: ${euro(fahrtKosten)} (${zahl(fahrtKm)} km × ${euro(zahl(fahrtKostenProKm, 0.40))}/km)", color = KuemmeroGreen, fontWeight = FontWeight.SemiBold)
                }
                item {
                    OutlinedTextField(
                        stundensatz,
                        {
                            stundensatz = it; ungespeicherteAenderungen = true
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
                            OutlinedTextField(terminDatum, { terminDatum = it; ungespeicherteAenderungen = true }, label = { Text("Datum") }, placeholder = { Text(datumJetzt) }, colors = feldFarben, modifier = Modifier.weight(1f))
                            OutlinedTextField(terminUhrzeit, { terminUhrzeit = it; ungespeicherteAenderungen = true }, label = { Text("Uhrzeit") }, placeholder = { Text("09:00") }, colors = feldFarben, modifier = Modifier.weight(1f))
                        }
                    }
                }

                item {
                    KlappBereich("📝 Notiz zum Auftrag", notizBereichOffen, { notizBereichOffen = !notizBereichOffen }) {
                        OutlinedTextField(notiz, { notiz = it; ungespeicherteAenderungen = true }, label = { Text("Notiz zum Auftrag") }, minLines = 3, colors = feldFarben, modifier = Modifier.fillMaxWidth())
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
                            val fehlendePflichtfelder = mutableListOf<String>()
                            if (nummer.trim().isBlank()) fehlendePflichtfelder.add("Auftragsnummer")
                            if (datum.trim().isBlank()) fehlendePflichtfelder.add("Datum")
                            if (leistungsdatum.trim().isBlank()) fehlendePflichtfelder.add("Leistungsdatum")
                            if (kunde.trim().isBlank()) fehlendePflichtfelder.add("Kunde")
                            if (strasse.trim().isBlank()) fehlendePflichtfelder.add("Adresse")
                            if (ort.trim().isBlank()) fehlendePflichtfelder.add("PLZ und Ort")
                            if (leistung.trim().isBlank()) fehlendePflichtfelder.add("Leistung")
                            if (fehlendePflichtfelder.isNotEmpty()) {
                                android.widget.Toast.makeText(context, "Bitte Pflichtfelder ausfüllen: ${fehlendePflichtfelder.joinToString(", ")}", android.widget.Toast.LENGTH_LONG).show()
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
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung1Datum } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung1Frist } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung1Gebuehr } ?: 0.0,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung1Text } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung1Erstellt } ?: false,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung2Datum } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung2Frist } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung2Gebuehr } ?: 0.0,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung2Text } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.mahnung2Erstellt } ?: false,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.arbeitsStart } ?: 0L,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.arbeitsEnde } ?: 0L,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.arbeitsSekunden } ?: 0L,
                                    erstellungskosten = bearbeiteIndex?.let { auftraege.getOrNull(it)?.erstellungskosten } ?: 0.0,
                                    leistungsdatum = leistungsdatum.trim().ifBlank { datum.trim() }
                                )
                                val index = bearbeiteIndex
                                val doppelteAuftragsnummer = auftraege.withIndex().any {
                                    it.index != index && it.value.nummer.equals(a.nummer.trim(), ignoreCase = true)
                                }
                                if (doppelteAuftragsnummer) {
                                    android.widget.Toast.makeText(context, "Diese Auftragsnummer ist bereits vergeben. Bitte eine andere Nummer verwenden.", android.widget.Toast.LENGTH_LONG).show()
                                } else if (index != null) {
                                    auftraege = auftraege.toMutableList().apply { set(index, a) }
                                    speichereAuftraege(context, auftraege)
                                    bearbeiteIndex = null
                                    android.widget.Toast.makeText(context, "Auftrag geändert.", 0).show()
                                    auftragFormOffen = false
                                } else {
                                    auftraege = auftraege + a
                                    speichereAuftraege(context, auftraege)
                                    android.widget.Toast.makeText(context, "Auftrag gespeichert.", 0).show()
                                    auftragFormOffen = false
                                }
                                ungespeicherteAenderungen = false
                                leistungsdatum = datumFormat.format(Date())
                                kunde = ""
                                strasse = ""
                                ort = ""
                                leistung = ""
                                stunden = ""
                                material = ""
                                materialBonUri = ""
                                fahrtKm = ""
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
                                fahrtKm = ""
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
                            val fehlendePflichtfelder = listOf(
                                "Auftragsnummer" to nummer.trim(),
                                "Datum" to datum.trim(),
                                "Leistungsdatum" to leistungsdatum.trim(),
                                "Kunde" to kunde.trim(),
                                "Adresse" to strasse.trim(),
                                "PLZ und Ort" to ort.trim(),
                                "Leistung" to leistung.trim()
                            ).filter { it.second.isBlank() }.map { it.first }
                            if (fehlendePflichtfelder.isNotEmpty()) {
                                android.widget.Toast.makeText(context, "Bitte Pflichtfelder ausfüllen: ${fehlendePflichtfelder.joinToString(", ")}", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                pdfLauncher.launch(dokumentSpeicherIntent("application/pdf", "KÜMMERO-Angebot-$nummer.pdf"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)
                    ) {
                        Text("PDF-Auftrag erstellen", fontWeight = FontWeight.Bold)
                    }
                }

                item {
                    KlappBereich(
                        "💾 Sicherung",
                        sicherungBereichOffen,
                        { sicherungBereichOffen = !sicherungBereichOffen },
                    ) {
                        OutlinedButton(onClick = { sicherungBestaetigung = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("Sicherung speichern / aktualisieren", fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(onClick = { dropboxBestaetigung = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("☁️ Jetzt in Dropbox sichern", fontWeight = FontWeight.SemiBold) }
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
                                ungespeicherteAenderungen = false
                                nummer = kuemmeroNaechsteDokumentNummer("AUF", Calendar.getInstance().get(Calendar.YEAR), auftraege.map { it.nummer })
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
                                fahrtKm = ""
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
                        modifier = Modifier.fillMaxWidth().clickable {
                            auftragFormOffen = false
                            bearbeiteIndex = null
                            auftragDetailIndex = index
                        },
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
                                Text("Auftrag: ${a.nummer}", color = KuemmeroGreen, fontWeight = FontWeight.SemiBold)
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
                                OutlinedButton(
                                    onClick = {
                                        auftragFormOffen = false
                                        bearbeiteIndex = null
                                        auftragDetailIndex = index
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(2.dp, KuemmeroGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                ) {
                                    Text("ℹ Auftrag öffnen / Info", fontWeight = FontWeight.Bold)
                                }
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

                            if (a.rechnungsnummer.isNotBlank() && a.zahlungsstatus != "Bezahlt" && rechnungIstUeberfaellig(a, heuteText)) {
                                Button(
                                    onClick = {
                                        mahnung1Index = index
                                        mahnung1Datum = a.mahnung1Datum.ifBlank { heuteText }
                                        mahnung1Frist = a.mahnung1Frist.ifBlank {
                                            val cal = Calendar.getInstance()
                                            cal.add(Calendar.DAY_OF_YEAR, 7)
                                            datumFormat.format(cal.time)
                                        }
                                        mahnung1Gebuehr = if (a.mahnung1Gebuehr > 0.0) String.format(Locale.GERMANY, "%.2f", a.mahnung1Gebuehr) else ""
                                        mahnung1Text = a.mahnung1Text
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroError)
                                ) {
                                    Text(if (a.mahnung1Erstellt) "1. Mahnung bearbeiten / neu erstellen" else "1. Mahnung erstellen", fontWeight = FontWeight.Bold)
                                }
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
                                    ungespeicherteAenderungen = false
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
                                    fahrtKm = if (zahl(fahrtKostenProKm, 0.40) > 0.0) (a.fahrt / zahl(fahrtKostenProKm, 0.40)).toString().replace(".", ",") else ""
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
                                            rechnungLauncher.launch(dokumentSpeicherIntent("application/pdf", "KÜMMERO-Rechnung-$name.pdf"))
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
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .kuemmeroHauptseitenWischen(hauptseite) { versucheSeitenwechsel(it) },
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
                            Text("Heute erledigen", style = MaterialTheme.typography.titleLarge, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                        }
                        if (heuteZuErledigen.isEmpty()) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                    Text("Heute ist alles erledigt. ✓", Modifier.padding(18.dp), color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            itemsIndexed(heuteZuErledigen.take(6)) { _, a ->
                                val index = auftraege.indexOfFirst { it.nummer == a.nummer && it.kunde == a.kunde && it.datum == a.datum }
                                val rechnungOffen = a.status == "Erledigt" && a.rechnungsnummer.isBlank()
                                val ueberfaellig = rechnungIstUeberfaellig(a, heuteText)
                                val heuteTermin = a.terminDatum == heuteText
                                val titel = when {
                                    ueberfaellig -> "🔴 Rechnung überfällig"
                                    rechnungOffen -> "🧾 Rechnung noch nicht erstellt"
                                    heuteTermin -> "📅 Termin heute"
                                    else -> "🟢 Auftrag bearbeiten"
                                }
                                Card(
                                    Modifier.fillMaxWidth().clickable {
                                        if (index >= 0) {
                                            hauptseite = "Aufträge"
                                            auftragFormOffen = false
                                            auftragDetailIndex = index
                                            bearbeiteIndex = null
                                        }
                                    },
                                    colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.5.dp, if (ueberfaellig) KuemmeroError else KuemmeroGreenLight)
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(titel, color = if (ueberfaellig) KuemmeroError else KuemmeroGreen, fontWeight = FontWeight.Bold)
                                        Text(a.kunde.ifBlank { "Kunde" }, color = KuemmeroText, fontWeight = FontWeight.SemiBold)
                                        if (a.leistung.isNotBlank()) Text(a.leistung, color = KuemmeroText, maxLines = 2)
                                        Text("Auftrag öffnen →", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        item {
                            Text("Schnellaktionen", style = MaterialTheme.typography.titleLarge, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        hauptseite = "Aufträge"
                                        auftragDetailIndex = null
                                        bearbeiteIndex = null
                                        nummer = ""
                                        datum = datumJetzt
                                        leistungsdatum = datumJetzt
                                        gueltigBis = ""
                                        kunde = ""; strasse = ""; ort = ""; leistung = ""
                                        stunden = ""; material = ""; materialBonUri = ""; fahrtKm = ""
                                        status = "Offen"; zahlungsstatus = "Offen"; bezahltAm = ""
                                        terminDatum = ""; terminUhrzeit = ""; notiz = ""
                                        fotosVorher = emptyList(); fotosNachher = emptyList()
                                        unterschriftPfad = ""; unterschriftDatum = ""
                                        auftragFormOffen = true
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                ) { Text("➕ Neuer Auftrag", fontWeight = FontWeight.Bold) }
                                Button(
                                    onClick = { neuerKundeDialog = true },
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                ) { Text("👤 Neuer Kunde", fontWeight = FontWeight.Bold) }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        hauptseite = "Aufträge"
                                        auftragFormOffen = false
                                        auftragDetailIndex = null
                                        bearbeiteIndex = null
                                        statusFilter = "Erledigt"
                                        zahlungsFilterOffen = false
                                        auftragsSuche = ""
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                ) { Text("🧾 Rechnung", fontWeight = FontWeight.Bold) }
                                Button(
                                    onClick = {
                                        hauptseite = "Aufträge"
                                        auftragFormOffen = false
                                        auftragDetailIndex = null
                                        bearbeiteIndex = null
                                        statusFilter = "In Bearbeitung"
                                        zahlungsFilterOffen = false
                                        auftragsSuche = ""
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                ) { Text("⏱ Arbeitszeit", fontWeight = FontWeight.Bold) }
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
                                        kvNummer = kuemmeroNaechsteDokumentNummer("KV", Calendar.getInstance().get(Calendar.YEAR), kostenvoranschlaege.map { it.nummer })
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
                                        kvFahrtKm = ""
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
                                                    kvFahrtKm = if (zahl(fahrtKostenProKm, 0.40) > 0.0) (k.fahrt / zahl(fahrtKostenProKm, 0.40)).toString().replace(".", ",") else ""
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
                                                        nummer = kuemmeroNaechsteDokumentNummer("AUF", Calendar.getInstance().get(Calendar.YEAR), auftraege.map { it.nummer }),
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
                                        OutlinedTextField(kvNummer, { kvNummer = it }, label = { Text("Nummer *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvDatum, { kvDatum = it }, label = { Text("Datum *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvGueltigBis, { kvGueltigBis = it }, label = { Text("Gültig bis *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Kunde *", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
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
                                        OutlinedTextField(kvStrasse, { kvStrasse = it }, label = { Text("Adresse *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(kvOrt, { kvOrt = it }, label = { Text("PLZ und Ort *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Leistungsposition", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                            OutlinedButton(
                                                onClick = { leistungsAuswahlZiel = "kv" },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                                shape = RoundedCornerShape(26.dp),
                                                border = BorderStroke(2.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) {
                                                Text(if (kvLeistung.isBlank()) "Position auswählen" else "Ausgewählt: $kvLeistung", fontWeight = FontWeight.Bold)
                                            }
                                            OutlinedTextField(kvLeistung, { kvLeistung = it }, label = { Text("Leistung / eigene Beschreibung *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        }
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
                                        OutlinedTextField(kvFahrtKm, { kvFahrtKm = it }, label = { Text("Kilometer") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                        Text("Fahrkosten: ${euro(runde2(zahl(kvFahrtKm) * zahl(fahrtKostenProKm, 0.40)))} (${zahl(kvFahrtKm)} km × ${euro(zahl(fahrtKostenProKm, 0.40))}/km)", color = KuemmeroGreen, fontWeight = FontWeight.SemiBold)
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
                                            "Gesamtsumme: ${euro(gesamtbetrag(zahl(kvStunden), zahl(kvMaterial), runde2(zahl(kvFahrtKm) * zahl(fahrtKostenProKm, 0.40)), zahl(kvStundensatz, 42.0), zahl(kvErstellungskosten)))}",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = KuemmeroGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Button(
                                            onClick = {
                                                val fehlendePflichtfelder = listOf(
                                                    "KV-Nummer" to kvNummer.trim(),
                                                    "Datum" to kvDatum.trim(),
                                                    "Gültig bis" to kvGueltigBis.trim(),
                                                    "Kunde" to kvKunde.trim(),
                                                    "Adresse" to kvStrasse.trim(),
                                                    "PLZ und Ort" to kvOrt.trim(),
                                                    "Leistung" to kvLeistung.trim()
                                                ).filter { it.second.isBlank() }.map { it.first }
                                                if (fehlendePflichtfelder.isNotEmpty()) {
                                                    android.widget.Toast.makeText(context, "Bitte Pflichtfelder ausfüllen: ${fehlendePflichtfelder.joinToString(", ")}", android.widget.Toast.LENGTH_LONG).show()
                                                } else {
                                                    val neueKvNummer = kvNummer.trim()
                                                    val doppelteKvNummer = kostenvoranschlaege.withIndex().any {
                                                        it.index != kvBearbeiteIndex && it.value.nummer.equals(neueKvNummer, ignoreCase = true)
                                                    }
                                                    if (neueKvNummer.isBlank()) {
                                                        android.widget.Toast.makeText(context, "Bitte eine KV-Nummer eingeben.", android.widget.Toast.LENGTH_SHORT).show()
                                                    } else if (doppelteKvNummer) {
                                                        android.widget.Toast.makeText(context, "Diese KV-Nummer ist bereits vergeben. Bitte eine andere Nummer verwenden.", android.widget.Toast.LENGTH_LONG).show()
                                                    } else {
                                                        val k = Kostenvoranschlag(
                                                            neueKvNummer, kvDatum.trim(), kvGueltigBis.trim(),
                                                            kvKunde.trim(), kvStrasse.trim(), kvOrt.trim(), kvLeistung.trim(),
                                                            zahl(kvStunden), zahl(kvMaterial), runde2(zahl(kvFahrtKm) * zahl(fahrtKostenProKm, 0.40)), zahl(kvStundensatz, 42.0),
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
                    "Mahnungen" -> {
                        val offeneRechnungen = auftraege.mapIndexed { index, a -> index to a }
                            .filter { (_, a) -> a.rechnungsnummer.isNotBlank() && a.zahlungsstatus != "Bezahlt" }
                        val ueberfaelligeRechnungen = offeneRechnungen.filter { (_, a) -> rechnungIstUeberfaellig(a, heuteText) }

                        item {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (ueberfaelligeRechnungen.isNotEmpty()) KuemmeroMint else KuemmeroSurface
                                ),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(2.dp, if (ueberfaelligeRechnungen.isNotEmpty()) KuemmeroError else KuemmeroGreenLight)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "Mahnungen",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = KuemmeroGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                        OutlinedButton(
                                            onClick = { mahnungEinstellungenOffen = true },
                                            shape = RoundedCornerShape(20.dp),
                                            border = BorderStroke(1.dp, KuemmeroGreen),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                        ) {
                                            Text("⚙")
                                        }
                                    }
                                    Text(
                                        if (ueberfaelligeRechnungen.isEmpty())
                                            "Keine überfälligen, unbezahlten Rechnungen."
                                        else
                                            "${ueberfaelligeRechnungen.size} Rechnung/Rechnungen sind überfällig.",
                                        color = if (ueberfaelligeRechnungen.isEmpty()) KuemmeroText else KuemmeroError,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Mahnungen werden niemals automatisch erstellt. Du entscheidest selbst, wann die 1. oder 2. Mahnung erstellt wird.",
                                        color = KuemmeroText,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        if (mahnungTestmodus) {
                            item {
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = KuemmeroMint),
                                    border = BorderStroke(2.dp, KuemmeroGreen),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("🧪 TEST-MODUS", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                        Text("Diese Mahnung ist eine reine Testrechnung. Echte Rechnungsdaten werden nicht verändert.", color = KuemmeroText)
                                        Text("Rechnung: TEST-RECHNUNG · Betrag: ${euro(420.0)}", color = KuemmeroText)
                                        if (!testMahnung1Erstellt) {
                                            Button(
                                                onClick = {
                                                    testMahnung1Datum = heuteText
                                                    testMahnung1Frist = standardMahnung1Frist(context)
                                                    testMahnung1Gebuehr = mahnungEinstellungGebuehr
                                                    testMahnung1Text = mahnungEinstellungText
                                                    testMahnung1DialogOffen = true
                                                },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                                shape = RoundedCornerShape(26.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                            ) { Text("Test-Mahnung erstellen", fontWeight = FontWeight.Bold) }
                                        } else {
                                            Text("1. Test-Mahnung erstellt · ${testMahnung1Datum.ifBlank { "ohne Datum" }} · Frist: ${testMahnung1Frist.ifBlank { "ohne Frist" }}", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                            OutlinedButton(
                                                onClick = { testMahnung1DialogOffen = true },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                                shape = RoundedCornerShape(25.dp),
                                                border = BorderStroke(2.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) { Text("Test-Mahnung ändern") }
                                            OutlinedButton(
                                                onClick = { testMahnungLoeschBestaetigung = true },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                                shape = RoundedCornerShape(25.dp),
                                                border = BorderStroke(2.dp, KuemmeroError),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroError)
                                            ) { Text("Test-Mahnung löschen") }
                                        }
                                    }
                                }
                            }
                        }

                        if (offeneRechnungen.isEmpty()) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                    Text(
                                        "Keine offenen Rechnungen vorhanden.",
                                        modifier = Modifier.padding(16.dp),
                                        color = KuemmeroText
                                    )
                                }
                            }
                        } else {
                            offeneRechnungen.forEach { (index, a) ->
                                item {
                                    Card(
                                        Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                                        shape = RoundedCornerShape(18.dp)
                                    ) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                a.kunde.ifBlank { "Kunde" },
                                                style = MaterialTheme.typography.titleLarge,
                                                color = KuemmeroGreen,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (a.kunde.isNotBlank()) kundenAkteName = a.kunde
                                                    }
                                                    .padding(vertical = 4.dp)
                                            )
                                            OutlinedButton(
                                                onClick = {
                                                    val auftragIndex = auftraege.indexOfFirst {
                                                        it.nummer == a.nummer &&
                                                            it.rechnungsnummer.equals(a.rechnungsnummer, ignoreCase = true)
                                                    }
                                                    if (auftragIndex >= 0) {
                                                        hauptseite = "Aufträge"
                                                        auftragFormOffen = false
                                                        bearbeiteIndex = null
                                                        auftragDetailIndex = auftragIndex
                                                    } else {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "Die zugehörige Rechnung wurde nicht gefunden.",
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                                shape = RoundedCornerShape(24.dp),
                                                border = BorderStroke(2.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) {
                                                Text("Rechnung: ${a.rechnungsnummer}  →", fontWeight = FontWeight.Bold)
                                            }
                                            Text("Betrag: ${euro(gesamtbetrag(a.stunden, a.material, a.fahrt, a.stundensatz, a.erstellungskosten))}", color = KuemmeroText)
                                            Text("Fällig am: ${a.faelligAm.ifBlank { "nicht angegeben" }}", color = KuemmeroText)

                                            if (rechnungIstUeberfaellig(a, heuteText)) {
                                                Text("⚠ Überfällig – Zahlung offen", color = KuemmeroError, fontWeight = FontWeight.Bold)
                                                if (!a.mahnung1Erstellt) {
                                                    Text("Noch keine Mahnung erstellt.", color = KuemmeroText)
                                                    Button(
                                                        onClick = {
                                                            mahnung1Index = index
                                                            mahnung1Datum = heuteText
                                                            mahnung1Frist = standardMahnung1Frist(context)
                                                            mahnung1Gebuehr = mahnungEinstellungGebuehr
                                                            mahnung1Text = mahnungEinstellungText
                                                        },
                                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                                        shape = RoundedCornerShape(26.dp),
                                                        colors = ButtonDefaults.buttonColors(containerColor = KuemmeroError)
                                                    ) { Text("1. Mahnung erstellen", fontWeight = FontWeight.Bold) }
                                                } else {
                                                    Text(
                                                        "1. Mahnung erstellt · ${a.mahnung1Datum.ifBlank { "ohne Datum" }} · Frist: ${a.mahnung1Frist.ifBlank { "ohne Frist" }}",
                                                        color = KuemmeroGreen, fontWeight = FontWeight.SemiBold
                                                    )
                                                    Button(
                                                        onClick = {
                                                            mahnung1Index = index
                                                            mahnung1Datum = a.mahnung1Datum.ifBlank { heuteText }
                                                            mahnung1Frist = a.mahnung1Frist.ifBlank { standardMahnung1Frist(context) }
                                                            mahnung1Gebuehr = if (a.mahnung1Gebuehr > 0.0) String.format(Locale.GERMANY, "%.2f", a.mahnung1Gebuehr) else mahnungEinstellungGebuehr
                                                            mahnung1Text = a.mahnung1Text.ifBlank { mahnungEinstellungText }
                                                        },
                                                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                                        shape = RoundedCornerShape(25.dp),
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                                    ) { Text("1. Mahnung bearbeiten / neu erstellen") }
                                                    if (a.mahnung2Erstellt) {
                                                        Text(
                                                            "2. Mahnung erstellt · ${a.mahnung2Datum.ifBlank { "ohne Datum" }} · Frist: ${a.mahnung2Frist.ifBlank { "ohne Frist" }}",
                                                            color = KuemmeroError, fontWeight = FontWeight.Bold
                                                        )
                                                        OutlinedButton(
                                                            onClick = {
                                                                mahnung2Index = index
                                                                mahnung2Datum = a.mahnung2Datum.ifBlank { heuteText }
                                                                mahnung2Frist = a.mahnung2Frist.ifBlank { standardMahnung1Frist(context) }
                                                                mahnung2Gebuehr = if (a.mahnung2Gebuehr > 0.0) String.format(Locale.GERMANY, "%.2f", a.mahnung2Gebuehr) else mahnungEinstellungGebuehr
                                                                mahnung2Text = a.mahnung2Text.ifBlank { "Bitte begleichen Sie den weiterhin offenen Rechnungsbetrag innerhalb der angegebenen Zahlungsfrist." }
                                                            },
                                                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                                            shape = RoundedCornerShape(25.dp),
                                                            border = BorderStroke(2.dp, KuemmeroError),
                                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroError)
                                                        ) { Text("2. Mahnung bearbeiten / neu erstellen") }
                                                    } else if (mahnung1IstUeberfaellig(a, heuteText)) {
                                                        Text("Zahlungsfrist der 1. Mahnung abgelaufen – 2. Mahnung möglich.", color = KuemmeroError, fontWeight = FontWeight.SemiBold)
                                                        Button(
                                                            onClick = {
                                                                mahnung2Index = index
                                                                mahnung2Datum = heuteText
                                                                mahnung2Frist = standardMahnung1Frist(context)
                                                                mahnung2Gebuehr = mahnungEinstellungGebuehr
                                                                mahnung2Text = "Bitte begleichen Sie den weiterhin offenen Rechnungsbetrag innerhalb der angegebenen Zahlungsfrist."
                                                            },
                                                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                                            shape = RoundedCornerShape(25.dp),
                                                            colors = ButtonDefaults.buttonColors(containerColor = KuemmeroError)
                                                        ) { Text("2. Mahnung erstellen", fontWeight = FontWeight.Bold) }
                                                    } else {
                                                        Text("1. Mahnung läuft noch bis ${a.mahnung1Frist}.", color = KuemmeroText)
                                                    }
                                                }
                                            } else {
                                                Text("Noch nicht überfällig – keine Mahnung erforderlich.", color = KuemmeroGreen, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
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
                            OutlinedButton(
                                onClick = { hauptseite = "Mahnungen" },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) { Text("!  Mahnungen", fontWeight = FontWeight.Bold) }
                        }
                        item {
                            OutlinedButton(
                                onClick = { hauptseite = "Rechnungsarchiv" },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) { Text("🧾 Rechnungsarchiv", fontWeight = FontWeight.Bold) }
                        }
                        item {
                            OutlinedButton(
                                onClick = { hauptseite = "Auswertung" },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) { Text("📊 Monats-/Jahresübersicht", fontWeight = FontWeight.Bold) }
                        }
                        item {
                            OutlinedButton(
                                onClick = { globaleSuche = ""; hauptseite = "Suche" },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) { Text("🔎 Globale Suche", fontWeight = FontWeight.Bold) }
                        }
                        item {
                            OutlinedButton(
                                onClick = { createDataExport.launch(dokumentSpeicherIntent("text/csv", "KÜMMERO-Datenexport-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.GERMANY).format(Date())}.csv")) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) { Text("📤 Datenexport (CSV)", fontWeight = FontWeight.Bold) }
                        }
                        item {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = KuemmeroSurface),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("📁 Dokumentenordner", style = MaterialTheme.typography.titleMedium, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (dokumenteSpeicherOrdnerUri.isBlank())
                                            "Kein zentraler Ordner ausgewählt. Beim Speichern kann ein Ziel gewählt werden."
                                        else
                                            "Zentraler Ordner ausgewählt. Neue PDFs und Datenexporte öffnen standardmäßig diesen Ordner." ,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = KuemmeroText
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { dokumenteOrdnerLauncher.launch(null) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(22.dp),
                                            border = BorderStroke(2.dp, KuemmeroGreen),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                        ) {
                                            Text(if (dokumenteSpeicherOrdnerUri.isBlank()) "Ordner auswählen" else "Ordner ändern")
                                        }
                                        if (dokumenteSpeicherOrdnerUri.isNotBlank()) {
                                            OutlinedButton(
                                                onClick = {
                                                    dokumentePrefs.edit().remove(DOKUMENTE_SPEICHERORDNER_URI_KEY).apply()
                                                    dokumenteSpeicherOrdnerUri = ""
                                                    android.widget.Toast.makeText(context, "Dokumentenordner entfernt.", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                shape = RoundedCornerShape(22.dp),
                                                border = BorderStroke(1.dp, KuemmeroError),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroError)
                                            ) { Text("Entfernen") }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            OutlinedButton(
                                onClick = { hauptseite = "Meine Leistungen" },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) { Text("🛠 Meine Leistungen", fontWeight = FontWeight.Bold) }
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
                                    OutlinedButton(onClick = { dropboxBestaetigung = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("☁️ Jetzt in Dropbox sichern") }
                                    Button(onClick = { restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)) { Text("Daten wiederherstellen", fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Fahrkosten", style = MaterialTheme.typography.titleMedium, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    Text("Preis pro Kilometer für neue Aufträge und Kostenvoranschläge.", color = KuemmeroText, fontSize = 13.sp)
                                    OutlinedTextField(
                                        value = fahrtKostenProKm,
                                        onValueChange = { fahrtKostenProKm = it },
                                        label = { Text("Fahrkosten pro km (€)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        colors = feldFarben,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            val wert = zahl(fahrtKostenProKm, 0.40)
                                            fahrtKostenProKm = String.format(Locale.GERMANY, "%.2f", wert)
                                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                                                .putString(FAHRTKOSTEN_PRO_KM_KEY, fahrtKostenProKm.replace(',', '.'))
                                                .apply()
                                            android.widget.Toast.makeText(context, "Fahrkosten gespeichert: ${euro(wert)}/km", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                                    ) { Text("Fahrkosten speichern", fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Unternehmensdaten für Rechnungen", style = MaterialTheme.typography.titleMedium, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    OutlinedTextField(unternehmerName, { unternehmerName = it }, label = { Text("Name / Inhaber *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(unternehmerStrasse, { unternehmerStrasse = it }, label = { Text("Straße / Hausnummer *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(unternehmerPlzOrt, { unternehmerPlzOrt = it }, label = { Text("PLZ / Ort *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(unternehmerTelefon, { unternehmerTelefon = it }, label = { Text("Telefon") }, colors = feldFarben, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                                    OutlinedTextField(unternehmerEmail, { unternehmerEmail = it }, label = { Text("E-Mail") }, colors = feldFarben, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                                    OutlinedTextField(steuernummer, { steuernummer = it }, label = { Text("Steuernummer / USt-ID / KU-IdNr. *") }, colors = feldFarben, modifier = Modifier.fillMaxWidth())
                                    Button(onClick = {
                                        val fehlendeUnternehmensdaten = listOf(
                                            "Name / Inhaber" to unternehmerName.trim(),
                                            "Straße / Hausnummer" to unternehmerStrasse.trim(),
                                            "PLZ / Ort" to unternehmerPlzOrt.trim(),
                                            "Steuernummer / USt-ID / KU-IdNr." to steuernummer.trim()
                                        ).filter { it.second.isBlank() }.map { it.first }
                                        if (fehlendeUnternehmensdaten.isNotEmpty()) {
                                            android.widget.Toast.makeText(context, "Bitte Pflichtfelder ausfüllen: ${fehlendeUnternehmensdaten.joinToString(", ")}", android.widget.Toast.LENGTH_LONG).show()
                                            return@Button
                                        }
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
                    "Meine Leistungen" -> {
                        item {
                            Text("Meine Leistungen", style = MaterialTheme.typography.headlineSmall, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                            Text("Hier kannst du deine Auswahl selbst erweitern, ändern und deaktivieren.", color = KuemmeroText)
                            Text(
                                "⚠ Die Liste ist keine rechtliche Freigabe. Nur Tätigkeiten anbieten, die dein Gewerbe und deine Qualifikation tatsächlich abdecken.",
                                color = KuemmeroError,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                        item {
                            Button(
                                onClick = {
                                    leistungspositionBearbeiteIndex = null
                                    leistungspositionName = ""
                                    leistungspositionBeschreibung = ""
                                    leistungspositionEinheit = "Pauschale"
                                    leistungspositionPreis = ""
                                    leistungspositionAktiv = true
                                    leistungspositionDialog = true
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreen)
                            ) { Text("+ Neue Leistung", fontWeight = FontWeight.Bold) }
                        }
                        leistungspositionen.forEachIndexed { index, leistungPos ->
                            item {
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = if (leistungPos.aktiv) KuemmeroSurface else KuemmeroMint),
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.5.dp, if (leistungPos.aktiv) KuemmeroGreenLight else KuemmeroGreen)
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(leistungPos.name, fontWeight = FontWeight.Bold, color = KuemmeroText)
                                                if (leistungPos.beschreibung.isNotBlank()) Text(leistungPos.beschreibung, color = KuemmeroText, style = MaterialTheme.typography.bodySmall)
                                                Text("${leistungPos.einheit} · ${euro(leistungPos.preis)}", color = KuemmeroGreen, fontWeight = FontWeight.SemiBold)
                                            }
                                            Text(if (leistungPos.aktiv) "Aktiv" else "Inaktiv", color = if (leistungPos.aktiv) KuemmeroGreen else KuemmeroText, fontWeight = FontWeight.Bold)
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    if (index > 0) {
                                                        val list = leistungspositionen.toMutableList()
                                                        val temp = list[index - 1]
                                                        list[index - 1] = list[index]
                                                        list[index] = temp
                                                        leistungspositionen = list
                                                        speichereLeistungspositionen(context, list)
                                                    }
                                                },
                                                enabled = index > 0,
                                                modifier = Modifier.weight(0.5f),
                                                shape = RoundedCornerShape(22.dp),
                                                border = BorderStroke(1.5.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) { Text("↑") }
                                            OutlinedButton(
                                                onClick = {
                                                    if (index < leistungspositionen.lastIndex) {
                                                        val list = leistungspositionen.toMutableList()
                                                        val temp = list[index + 1]
                                                        list[index + 1] = list[index]
                                                        list[index] = temp
                                                        leistungspositionen = list
                                                        speichereLeistungspositionen(context, list)
                                                    }
                                                },
                                                enabled = index < leistungspositionen.lastIndex,
                                                modifier = Modifier.weight(0.5f),
                                                shape = RoundedCornerShape(22.dp),
                                                border = BorderStroke(1.5.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) { Text("↓") }
                                            OutlinedButton(
                                                onClick = {
                                                    leistungspositionBearbeiteIndex = index
                                                    leistungspositionName = leistungPos.name
                                                    leistungspositionBeschreibung = leistungPos.beschreibung
                                                    leistungspositionEinheit = leistungPos.einheit
                                                    leistungspositionPreis = if (leistungPos.preis == 0.0) "" else leistungPos.preis.toString().replace('.', ',')
                                                    leistungspositionAktiv = leistungPos.aktiv
                                                    leistungspositionDialog = true
                                                },
                                                modifier = Modifier.weight(1.5f),
                                                shape = RoundedCornerShape(22.dp),
                                                border = BorderStroke(1.5.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) { Text("✏ Bearbeiten") }
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    leistungsPreisIndex = index
                                                    leistungsPreisEingabe = if (leistungPos.preis == 0.0) "" else String.format(Locale.GERMANY, "%.2f", leistungPos.preis)
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(22.dp),
                                                border = BorderStroke(1.5.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) { Text("💶 Preis") }
                                            OutlinedButton(
                                                onClick = {
                                                    val list = leistungspositionen.toMutableList()
                                                    list[index] = leistungPos.copy(aktiv = !leistungPos.aktiv)
                                                    leistungspositionen = list
                                                    speichereLeistungspositionen(context, list)
                                                },
                                                modifier = Modifier.weight(1.2f),
                                                shape = RoundedCornerShape(22.dp),
                                                border = BorderStroke(1.5.dp, KuemmeroGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                                            ) { Text(if (leistungPos.aktiv) "Deaktivieren" else "Aktivieren") }
                                            OutlinedButton(
                                                onClick = { leistungspositionLoeschIndex = index },
                                                modifier = Modifier.weight(0.9f),
                                                shape = RoundedCornerShape(22.dp),
                                                border = BorderStroke(1.5.dp, KuemmeroError),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroError)
                                            ) { Text("Löschen") }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    leistungspositionBearbeiteIndex = null
                                    leistungspositionName = ""
                                    leistungspositionBeschreibung = ""
                                    leistungspositionEinheit = "Pauschale"
                                    leistungspositionPreis = ""
                                    leistungspositionAktiv = true
                                    leistungspositionDialog = true
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(1.5.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) {
                                Text("+ Weitere Leistung hinzufügen", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    "Rechnungsarchiv" -> {
                        item { Text("Rechnungsarchiv", style = MaterialTheme.typography.headlineSmall, color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                        item {
                            OutlinedTextField(
                                value = rechnungArchivSuche,
                                onValueChange = { rechnungArchivSuche = it },
                                label = { Text("Rechnungen suchen") },
                                placeholder = { Text("Kunde, Rechnungsnummer, Leistung ...") },
                                singleLine = true, colors = feldFarben, modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Alle", SimpleDateFormat("yyyy", Locale.GERMANY).format(Date()), (Calendar.getInstance().get(Calendar.YEAR) - 1).toString()).forEach { jahr ->
                                    val aktiv = rechnungArchivJahr == jahr
                                    Surface(Modifier.height(42.dp).clickable { rechnungArchivJahr = jahr }, shape = RoundedCornerShape(21.dp), color = if (aktiv) KuemmeroGreen else KuemmeroMint, border = BorderStroke(1.5.dp, KuemmeroGreen)) {
                                        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { Text(jahr, color = if (aktiv) Color.White else KuemmeroText, fontWeight = FontWeight.SemiBold) }
                                    }
                                }
                            }
                        }
                        val archiv = auftraege.filter { a ->
                            a.rechnungsnummer.isNotBlank() &&
                            (rechnungArchivJahr == "Alle" || a.rechnungsdatum.endsWith(rechnungArchivJahr)) &&
                            (rechnungArchivSuche.isBlank() || listOf(a.kunde, a.rechnungsnummer, a.leistung).any { it.contains(rechnungArchivSuche, ignoreCase = true) })
                        }.sortedByDescending { it.rechnungsdatum }
                        if (archiv.isEmpty()) {
                            item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) { Text("Keine Rechnungen im Archiv gefunden.", Modifier.padding(16.dp), color = KuemmeroText) } }
                        } else {
                            itemsIndexed(archiv) { _, a ->
                                val index = auftraege.indexOfFirst { it.nummer == a.nummer }
                                Card(Modifier.fillMaxWidth().clickable { if (index >= 0) { hauptseite = "Aufträge"; auftragDetailIndex = index; auftragFormOffen = false } }, colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(a.rechnungsnummer, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                        Text(a.kunde.ifBlank { "Kunde" }, color = KuemmeroText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("${a.rechnungsdatum.ifBlank { "ohne Rechnungsdatum" }} · ${euro(gesamtbetrag(a.stunden, a.material, a.fahrt, a.stundensatz, a.erstellungskosten))}", color = KuemmeroText)
                                        Text("Auftrag öffnen →", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    "Auswertung" -> {
                        item { Text("Monats-/Jahresübersicht", style = MaterialTheme.typography.headlineSmall, color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                        val aktuellesJahr = Calendar.getInstance().get(Calendar.YEAR)
                        val jahrAuftraege = auftraege.filter { a ->
                            val datumWert = a.leistungsdatum.ifBlank { a.datum }
                            datumWert.endsWith(aktuellesJahr.toString())
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroGreen), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("Jahr $aktuellesJahr", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                                    Text("Umsatz: ${euro(jahrAuftraege.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) })}", color = Color.White)
                                    Text("Aufträge: ${jahrAuftraege.size}", color = Color.White)
                                    Text("Bezahlt: ${euro(jahrAuftraege.filter { it.zahlungsstatus == "Bezahlt" }.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) })}", color = Color.White)
                                    Text("Offen: ${euro(jahrAuftraege.filter { it.zahlungsstatus != "Bezahlt" }.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) })}", color = Color.White)
                                }
                            }
                        }
                        for (monat in 1..12) {
                            val monatAuftraege = jahrAuftraege.filter { a ->
                                try {
                                    val d = datumFormat.parse(a.leistungsdatum.ifBlank { a.datum }) ?: return@filter false
                                    val c = Calendar.getInstance().apply { time = d }
                                    c.get(Calendar.MONTH) + 1 == monat
                                } catch (_: Exception) { false }
                            }
                            if (monatAuftraege.isNotEmpty()) {
                                item {
                                    val name = SimpleDateFormat("MMMM", Locale.GERMANY).format(Calendar.getInstance().apply { set(Calendar.MONTH, monat - 1) }.time).replaceFirstChar { it.uppercase(Locale.GERMANY) }
                                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(16.dp)) {
                                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column { Text(name, color = KuemmeroGreen, fontWeight = FontWeight.Bold); Text("${monatAuftraege.size} Aufträge", color = KuemmeroText) }
                                            Text(euro(monatAuftraege.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz, it.erstellungskosten) }), color = KuemmeroText, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        item { Text("Die Übersicht dient der betrieblichen Orientierung und ersetzt keine Buchführung oder Steuerberatung.", color = KuemmeroText, style = MaterialTheme.typography.bodySmall) }
                    }
                    "Suche" -> {
                        item { Text("Globale Suche", style = MaterialTheme.typography.headlineSmall, color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                        item {
                            OutlinedTextField(
                                value = globaleSuche,
                                onValueChange = { globaleSuche = it },
                                label = { Text("Suche in KÜMMERO") },
                                placeholder = { Text("Kunde, Auftrag, Rechnung, Leistung ...") },
                                singleLine = true, colors = feldFarben, modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (globaleSuche.trim().isNotBlank()) {
                            val q = globaleSuche.trim()
                            val kundenTreffer = kunden.filter { listOf(it.name, it.adresse, it.ort, it.telefon, it.email).any { v -> v.contains(q, true) } }
                            val auftragTreffer = auftraege.filter { listOf(it.nummer, it.kunde, it.kundenStrasse, it.kundenOrt, it.leistung, it.rechnungsnummer, it.status).any { v -> v.contains(q, true) } }
                            val kvTreffer = kostenvoranschlaege.filter { listOf(it.nummer, it.kunde, it.leistung).any { v -> v.contains(q, true) } }
                            item { Text("Kunden (${kundenTreffer.size})", color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                            kundenTreffer.take(10).forEach { k ->
                                item { Card(Modifier.fillMaxWidth().clickable { hauptseite = "Kunden"; kundenAkteName = k.name }, colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) { Text(k.name, fontWeight = FontWeight.Bold, color = KuemmeroText); Text("Kunde öffnen →", color = KuemmeroGreen) } } }
                            }
                            item { Text("Aufträge / Rechnungen (${auftragTreffer.size})", color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                            auftragTreffer.take(15).forEach { a ->
                                item { val index = auftraege.indexOfFirst { it.nummer == a.nummer }; Card(Modifier.fillMaxWidth().clickable { if (index >= 0) { hauptseite = "Aufträge"; auftragDetailIndex = index; auftragFormOffen = false } }, colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) { Text(a.kunde.ifBlank { "Kunde" }, fontWeight = FontWeight.Bold, color = KuemmeroText); Text("${a.nummer}${if (a.rechnungsnummer.isBlank()) "" else " · ${a.rechnungsnummer}"}", color = KuemmeroGreen); if (a.leistung.isNotBlank()) Text(a.leistung, color = KuemmeroText) } } }
                            }
                            item { Text("Kostenvoranschläge (${kvTreffer.size})", color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                            kvTreffer.take(10).forEach { k ->
                                item { Card(Modifier.fillMaxWidth().clickable { hauptseite = "Kostenvoranschläge" }, colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) { Text(k.kunde.ifBlank { "Kunde" }, fontWeight = FontWeight.Bold, color = KuemmeroText); Text(k.nummer, color = KuemmeroGreen); Text("Kostenvoranschlag öffnen →", color = KuemmeroGreen) } } }
                            }
                            if (kundenTreffer.isEmpty() && auftragTreffer.isEmpty() && kvTreffer.isEmpty()) {
                                item { Text("Keine Treffer gefunden.", color = KuemmeroText) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (leistungspositionDialog) {
        AlertDialog(
            onDismissRequest = { leistungspositionDialog = false },
            title = { Text(if (leistungspositionBearbeiteIndex == null) "Neue Leistung" else "Leistung bearbeiten") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(leistungspositionName, { leistungspositionName = it }, label = { Text("Bezeichnung") }, singleLine = true, colors = feldFarben)
                    OutlinedTextField(leistungspositionBeschreibung, { leistungspositionBeschreibung = it }, label = { Text("Beschreibung (optional)") }, colors = feldFarben)
                    OutlinedTextField(leistungspositionEinheit, { leistungspositionEinheit = it }, label = { Text("Einheit") }, singleLine = true, colors = feldFarben)
                    OutlinedTextField(leistungspositionPreis, { leistungspositionPreis = it }, label = { Text("Standardpreis €") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = feldFarben)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = leistungspositionAktiv, onCheckedChange = { leistungspositionAktiv = it }, colors = CheckboxDefaults.colors(checkedColor = KuemmeroGreen))
                        Text("Bei Auftrag/Kostenvoranschlag anzeigen")
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        val name = leistungspositionName.trim()
                        if (name.isBlank()) {
                            android.widget.Toast.makeText(context, "Bitte eine Bezeichnung eingeben.", 0).show()
                        } else {
                            val neu = Leistungsposition(name, leistungspositionBeschreibung.trim(), leistungspositionEinheit.trim().ifBlank { "Pauschale" }, zahl(leistungspositionPreis), leistungspositionAktiv)
                            val list = leistungspositionen.toMutableList()
                            val idx = leistungspositionBearbeiteIndex
                            if (idx != null && idx in list.indices) list[idx] = neu else list.add(neu)
                            leistungspositionen = list
                            speichereLeistungspositionen(context, list)
                            leistungspositionDialog = false
                        }
                    }) { Text("Speichern", color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = {
                        val name = leistungspositionName.trim()
                        if (name.isBlank()) {
                            android.widget.Toast.makeText(context, "Bitte eine Bezeichnung eingeben.", 0).show()
                        } else {
                            val neu = Leistungsposition(name, leistungspositionBeschreibung.trim(), leistungspositionEinheit.trim().ifBlank { "Pauschale" }, zahl(leistungspositionPreis), leistungspositionAktiv)
                            val list = leistungspositionen.toMutableList()
                            val idx = leistungspositionBearbeiteIndex
                            if (idx != null && idx in list.indices) list[idx] = neu else list.add(neu)
                            leistungspositionen = list
                            speichereLeistungspositionen(context, list)
                            leistungspositionBearbeiteIndex = null
                            leistungspositionName = ""
                            leistungspositionBeschreibung = ""
                            leistungspositionEinheit = "Pauschale"
                            leistungspositionPreis = ""
                            leistungspositionAktiv = true
                            // Dialog bleibt offen: direkt die nächste Leistung anlegen.
                        }
                    }) { Text("+ Weitere", color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                }
            },
            dismissButton = { TextButton(onClick = { leistungspositionDialog = false }) { Text("Abbrechen") } }
        )
    }

    if (leistungsPreisIndex != null) {
        val idx = leistungsPreisIndex
        val position = idx?.let { leistungspositionen.getOrNull(it) }
        if (position != null) {
            AlertDialog(
                onDismissRequest = { leistungsPreisIndex = null },
                title = { Text("Standardpreis ändern") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(position.name, fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                        Text("Aktueller Preis: ${euro(position.preis)} / ${position.einheit}", color = KuemmeroText)
                        OutlinedTextField(
                            value = leistungsPreisEingabe,
                            onValueChange = { leistungsPreisEingabe = it },
                            label = { Text("Neuer Preis (€)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = feldFarben
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val preis = zahl(leistungsPreisEingabe)
                        if (idx != null && idx in leistungspositionen.indices) {
                            val list = leistungspositionen.toMutableList()
                            list[idx] = position.copy(preis = preis)
                            leistungspositionen = list
                            speichereLeistungspositionen(context, list)
                        }
                        leistungsPreisIndex = null
                    }) { Text("Speichern", color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { leistungsPreisIndex = null }) { Text("Abbrechen") } }
            )
        }
    }

    if (leistungspositionLoeschIndex != null) {
        val idx = leistungspositionLoeschIndex
        val name = idx?.let { leistungspositionen.getOrNull(it)?.name } ?: "Leistung"
        AlertDialog(
            onDismissRequest = { leistungspositionLoeschIndex = null },
            title = { Text("Leistung löschen?") },
            text = { Text("Soll \"$name\" wirklich aus deiner Leistungsliste gelöscht werden?") },
            confirmButton = {
                TextButton(onClick = {
                    if (idx != null && idx in leistungspositionen.indices) {
                        val list = leistungspositionen.toMutableList()
                        list.removeAt(idx)
                        leistungspositionen = list
                        speichereLeistungspositionen(context, list)
                    }
                    leistungspositionLoeschIndex = null
                }) { Text("Löschen", color = KuemmeroError, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { leistungspositionLoeschIndex = null }) { Text("Abbrechen") } }
        )
    }

    if (leistungsAuswahlZiel != null) {
        AlertDialog(
            onDismissRequest = { leistungsAuswahlZiel = null },
            title = { Text("Leistungsposition auswählen") },
            text = {
                val aktuellerText = if (leistungsAuswahlZiel == "auftrag") leistung else kvLeistung
                val ausgewaehlt = aktuellerText
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    leistungspositionen.filter { it.aktiv }.forEach { leistungs ->
                        val position = leistungs.name
                        val istAusgewaehlt = ausgewaehlt.any { it.substringBefore(" — ").trim() == position }
                        OutlinedButton(
                            onClick = {
                                if (istAusgewaehlt) {
                                    val neueAuswahl = ausgewaehlt.filter { it.substringBefore(" — ").trim() != position }
                                    val neuerText = neueAuswahl.joinToString("\n")
                                    if (leistungsAuswahlZiel == "auftrag") leistung = neuerText
                                    if (leistungsAuswahlZiel == "kv") kvLeistung = neuerText
                                } else if (leistungs.preis > 0.0) {
                                    leistungsPreisName = leistungs.name
                                    leistungsPreisEinheit = leistungs.einheit
                                    leistungsPreisVorschlag = String.format(Locale.GERMANY, "%.2f", leistungs.preis)
                                    leistungsPreisDialog = true
                                } else {
                                    val neueAuswahl = ausgewaehlt + leistungs.name
                                    val neuerText = neueAuswahl.joinToString("\n")
                                    if (leistungsAuswahlZiel == "auftrag") leistung = neuerText
                                    if (leistungsAuswahlZiel == "kv") kvLeistung = neuerText
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(2.dp, if (istAusgewaehlt) KuemmeroGreen else KuemmeroGreenLight),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (istAusgewaehlt) KuemmeroMint else Color.Transparent,
                                contentColor = KuemmeroText
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(if (istAusgewaehlt) "✓" else "○", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(position, textAlign = TextAlign.Start)
                                    if (leistungs.preis > 0.0) {
                                        Text(
                                            "Preisvorschlag: ${euro(leistungs.preis)} / ${leistungs.einheit}",
                                            color = KuemmeroGreen,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Start
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { leistungsAuswahlZiel = null }) {
                    Text("Fertig", color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { leistungsAuswahlZiel = null }) { Text("Abbrechen") }
            }
        )
    }

    if (leistungsPreisDialog) {
        AlertDialog(
            onDismissRequest = { leistungsPreisDialog = false },
            title = { Text("Preisvorschlag") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(leistungsPreisName, fontWeight = FontWeight.Bold, color = KuemmeroGreen)
                    Text("Hinterlegter Preis: ${leistungsPreisVorschlag.replace(',', '.').let { zahl(it) }.let { euro(it) }} / $leistungsPreisEinheit", color = KuemmeroText)
                    OutlinedTextField(
                        value = leistungsPreisVorschlag,
                        onValueChange = { leistungsPreisVorschlag = it },
                        label = { Text("Preis für diesen Vorgang (€)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feldFarben,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Der Preis ist nur ein Vorschlag. Du kannst ihn für diesen Auftrag/Kostenvoranschlag ändern.",
                        color = KuemmeroText,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val preis = zahl(leistungsPreisVorschlag)
                    val zeile = if (preis > 0.0) {
                        "$leistungsPreisName — ${euro(preis)} / $leistungsPreisEinheit"
                    } else {
                        leistungsPreisName
                    }
                    val aktuellerText = if (leistungsAuswahlZiel == "auftrag") leistung else kvLeistung
                    val vorhandene = aktuellerText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                    val ohnePosition = vorhandene.filter { it.substringBefore(" — ").trim() != leistungsPreisName }
                    val neuerText = (ohnePosition + zeile).joinToString("\n")
                    if (leistungsAuswahlZiel == "auftrag") leistung = neuerText
                    if (leistungsAuswahlZiel == "kv") kvLeistung = neuerText
                    leistungsPreisDialog = false
                }) { Text("Übernehmen", color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { leistungsPreisDialog = false }) { Text("Abbrechen") }
            }
        )
    }
}

}
