package de.kuemmero.app

import android.content.Context
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
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
    val rechnungsnummer: String = "",
    val rechnungsdatum: String = "",
    val faelligAm: String = "",
    val arbeitsStart: Long = 0L,
    val arbeitsEnde: Long = 0L,
    val arbeitsSekunden: Long = 0L,
    val arbeitszeitUebernommen: Boolean = false
)

private const val PREFS_NAME = "kuemmero_speicher"
private const val AUFTRAEGE_KEY = "auftraege"
private const val KUNDEN_KEY = "kunden"
private const val STUNDENSATZ_KEY = "stundensatz"
private const val BACKUP_URI_KEY = "backup_uri"

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

private fun gesamtbetrag(stunden: Double, material: Double, fahrt: Double, stundensatz: Double): Double {
    val arbeitskosten = arbeitsbetrag(stunden, stundensatz)
    val materialkosten = runde2(material)
    val fahrtkosten = runde2(fahrt)
    return runde2(arbeitskosten + materialkosten + fahrtkosten)
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
            o.optString("rechnungsnummer", ""),
            o.optString("rechnungsdatum", ""),
            o.optString("faelligAm", ""),
            o.optLong("arbeitsStart", 0L),
            o.optLong("arbeitsEnde", 0L),
            o.optLong("arbeitsSekunden", 0L),
            o.optBoolean("arbeitszeitUebernommen", false)
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
    sichereBackupAutomatisch(context)
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
            put("rechnungsnummer", a.rechnungsnummer)
            put("rechnungsdatum", a.rechnungsdatum)
            put("faelligAm", a.faelligAm)
            put("arbeitsStart", a.arbeitsStart)
            put("arbeitsEnde", a.arbeitsEnde)
            put("arbeitsSekunden", a.arbeitsSekunden)
            put("arbeitszeitUebernommen", a.arbeitszeitUebernommen)
        })
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(AUFTRAEGE_KEY, json.toString()).commit()
    sichereBackupAutomatisch(context)
}

private fun backupText(context: Context): String {
    val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return JSONObject().apply {
        put("stundensatz", p.getString(STUNDENSATZ_KEY, "42.00") ?: "42.00")
        put("auftraege", JSONArray(p.getString(AUFTRAEGE_KEY, "[]") ?: "[]"))
        put("kunden", JSONArray(p.getString(KUNDEN_KEY, "[]") ?: "[]"))
    }.toString(2)
}

private fun sichereBackupAutomatisch(context: Context): Boolean {
    val uriText = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(BACKUP_URI_KEY, null) ?: return false
    return try {
        val uri = Uri.parse(uriText)
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(backupText(context).toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    } catch (_: Exception) { false }
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
    fotosVorher: List<String> = emptyList(),
    fotosNachher: List<String> = emptyList()
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
    c.drawText("Markus Becker", 40f, 132f, p)
    c.drawText("58675 Hemer", 40f, 150f, p)
    c.drawText("Telefon: +49 176 16712509", 40f, 168f, p)
    c.drawText("E-Mail: kuemmero@web.de", 40f, 186f, p)
    c.drawText("ANGEBOT", 40f, 233f, p)
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
    c.drawLine(40f, 513f, 550f, 513f, p)
    val gesamt = gesamtbetrag(stunden, material, fahrt, stundensatz)
    p.textSize = 18f
    c.drawText("Gesamtsumme: ${euro(gesamt)}", 40f, 548f, p)
    p.textSize = 11f
    c.drawText("Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.", 40f, 578f, p)
    c.drawText("Auftragserteilung / Unterschrift Kunde:", 40f, 628f, p)
    val signBitmap = ladeUnterschriftBitmap(unterschriftPfad)
    if (signBitmap != null) {
        val maxW = 225f
        val maxH = 34f
        val scale = minOf(maxW / signBitmap.width.toFloat(), maxH / signBitmap.height.toFloat())
        val drawW = signBitmap.width * scale
        val drawH = signBitmap.height * scale
        val dst = android.graphics.RectF(40f, 648f, 40f + drawW, 648f + drawH)
        c.drawBitmap(signBitmap, null, dst, null)
        signBitmap.recycle()
    }
    c.drawLine(40f, 693f, 280f, 693f, p)
    c.drawText("Unterschrift", 40f, 711f, p)
    c.drawLine(330f, 693f, 550f, 693f, p)
    c.drawText("Datum", 330f, 711f, p)
    c.drawText("Vielen Dank für Ihr Vertrauen.", 40f, 763f, p)
    pdf.finishPage(page)
    fuegeFotoSeitenHinzu(context, pdf, fotosVorher, fotosNachher)
    return pdf
}


private fun erstelleRechnungPdf(
    context: Context,
    nummer: String,
    rechnungsdatum: String,
    faelligAm: String,
    kunde: String,
    strasse: String,
    ort: String,
    leistung: String,
    stunden: Double,
    material: Double,
    fahrt: Double,
    stundensatz: Double,
    unterschriftPfad: String = "",
    fotosVorher: List<String> = emptyList(),
    fotosNachher: List<String> = emptyList()
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
    c.drawText("Markus Becker", 40f, 132f, p)
    c.drawText("58675 Hemer", 40f, 150f, p)
    c.drawText("Telefon: +49 176 16712509", 40f, 168f, p)
    c.drawText("E-Mail: kuemmero@web.de", 40f, 186f, p)

    p.textSize = 18f
    c.drawText("RECHNUNG", 40f, 230f, p)
    p.textSize = 12f
    c.drawText("Rechnungsnummer: $nummer", 40f, 255f, p)
    c.drawText("Rechnungsdatum: $rechnungsdatum", 40f, 275f, p)
    c.drawText("Fällig am: $faelligAm", 40f, 295f, p)
    c.drawText("Kunde: $kunde", 40f, 325f, p)
    c.drawText("Adresse: $strasse", 40f, 345f, p)
    c.drawText("PLZ und Ort: $ort", 40f, 365f, p)
    c.drawText("Leistung: $leistung", 40f, 395f, p)

    c.drawLine(40f, 420f, 550f, 420f, p)
    c.drawText("Arbeitszeit", 40f, 445f, p)
    c.drawText("%.2f Std.".format(Locale.GERMANY, stunden), 250f, 445f, p)
    c.drawText(euro(arbeitsbetrag(stunden, stundensatz)), 450f, 445f, p)
    c.drawText("Material", 40f, 470f, p)
    c.drawText(euro(material), 450f, 470f, p)
    c.drawText("Fahrtkosten", 40f, 495f, p)
    c.drawText(euro(fahrt), 450f, 495f, p)
    c.drawLine(40f, 510f, 550f, 510f, p)

    val gesamt = gesamtbetrag(stunden, material, fahrt, stundensatz)
    p.textSize = 18f
    c.drawText("Gesamtsumme: ${euro(gesamt)}", 40f, 550f, p)
    p.textSize = 11f
    c.drawText("Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.", 40f, 578f, p)
    c.drawText("Bitte überweisen Sie den Rechnungsbetrag bis zum $faelligAm.", 40f, 600f, p)
    c.drawText("Kunden-Unterschrift:", 40f, 625f, p)
    val signBitmap = ladeUnterschriftBitmap(unterschriftPfad)
    if (signBitmap != null) {
        val maxW = 260f
        val maxH = 55f
        val scale = minOf(maxW / signBitmap.width.toFloat(), maxH / signBitmap.height.toFloat())
        val drawW = signBitmap.width * scale
        val drawH = signBitmap.height * scale
        val top = 635f + (maxH - drawH) / 2f
        c.drawBitmap(signBitmap, null, android.graphics.RectF(40f, top, 40f + drawW, top + drawH), null)
        signBitmap.recycle()
    } else {
        p.textSize = 10f
        c.drawText("Keine Unterschrift erfasst", 40f, 655f, p)
        p.textSize = 11f
    }
    c.drawLine(40f, 700f, 300f, 700f, p)
    c.drawText("Unterschrift", 40f, 718f, p)
    c.drawText("Vielen Dank für Ihr Vertrauen.", 40f, 730f, p)
    pdf.finishPage(page)
    fuegeFotoSeitenHinzu(context, pdf, fotosVorher, fotosNachher)
    return pdf
}

private fun druckeRechnungPdf(context: Context, auftrag: Auftrag) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val nummer = auftrag.rechnungsnummer.ifBlank { "RE-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date())}" }
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
                auftrag.kunde, auftrag.kundenStrasse, auftrag.kundenOrt, auftrag.leistung,
                auftrag.stunden, auftrag.material, auftrag.fahrt, auftrag.stundensatz,
                auftrag.unterschriftPfad, auftrag.fotosVorher, auftrag.fotosNachher
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
    auftrag: Auftrag
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
                auftrag.unterschriftPfad, auftrag.fotosVorher, auftrag.fotosNachher
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
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    var fahrt by remember { mutableStateOf("") }
    var stundensatz by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(STUNDENSATZ_KEY, "42.00") ?: "42.00"
        )
    }
    var auftraege by remember { mutableStateOf(ladeAuftraege(context)) }
    var kunden by remember { mutableStateOf(ladeKunden(context)) }
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
    var auftragsSeite by remember { mutableStateOf("Liste") }
    val listeState = rememberLazyListState()

    // Laufende Arbeitszeit
    var timerIndex by remember { mutableStateOf<Int?>(null) }
    var timerSekunden by remember { mutableStateOf(0L) }

    // Laufende Arbeitszeit nach App-Neustart automatisch wieder aufnehmen
    LaunchedEffect(auftraege) {
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

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(BACKUP_URI_KEY, it.toString()).apply()
            try {
                context.contentResolver.openOutputStream(it, "wt")?.use { out ->
                    out.write(backupText(context).toByteArray(Charsets.UTF_8))
                }
                android.widget.Toast.makeText(context, "Sicherung gespeichert", 0).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Sicherung fehlgeschlagen", 1).show()
            }
        }
    }

    val restoreBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                    ?: throw Exception("Datei konnte nicht gelesen werden")
                val obj = JSONObject(text)
                val rate = obj.optString("stundensatz", "42.00")
                val arr = obj.optJSONArray("auftraege") ?: JSONArray()
                val kundenArr = obj.optJSONArray("kunden") ?: JSONArray()
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(STUNDENSATZ_KEY, rate)
                    .putString(AUFTRAEGE_KEY, arr.toString())
                    .putString(KUNDEN_KEY, kundenArr.toString()).commit()
                stundensatz = rate
                auftraege = ladeAuftraege(context)
                kunden = ladeKunden(context)
                android.widget.Toast.makeText(context, "Daten wiederhergestellt", 0).show()
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
                    a.stunden, a.material, a.fahrt, a.stundensatz, a.unterschriftPfad,
                    a.fotosVorher, a.fotosNachher
                )
            } else {
                erstellePdf(
                    context, nummer, datum, gueltigBis, kunde, strasse, ort, leistung,
                    zahl(stunden), zahl(material), zahl(fahrt), zahl(stundensatz, 42.0), unterschriftPfad,
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
                try {
                    val rechnungsnummer = "RE-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date())
                    val rechnungsdatum = datumFormat.format(Date())
                    val faelligCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 14) }
                    val faelligAm = datumFormat.format(faelligCal.time)
                    val pdf = erstelleRechnungPdf(
                        context,
                        rechnungsnummer,
                        rechnungsdatum,
                        faelligAm,
                        a.kunde,
                        a.kundenStrasse,
                        a.kundenOrt,
                        a.leistung,
                        a.stunden,
                        a.material,
                        a.fahrt,
                        a.stundensatz,
                        a.unterschriftPfad,
                        a.fotosVorher, a.fotosNachher
                    )
                    context.contentResolver.openOutputStream(it)?.use { out -> pdf.writeTo(out) }
                    pdf.close()
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
    val umsatz = auftraege.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz) }

    val heuteText = datumFormat.format(Date())
    val termineHeute = auftraege.filter { it.terminDatum == heuteText }
        .sortedBy { it.terminUhrzeit }
    val offeneAuftraege = auftraege.count { it.status != "Abgerechnet" }
    val offeneZahlungen = auftraege.filter { it.zahlungsstatus != "Bezahlt" }
    val offeneZahlungSumme = offeneZahlungen.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz) }
    val naechsteTermine = auftraege.filter { it.terminDatum.isNotBlank() }
        .sortedWith(compareBy<Auftrag> {
            try { datumFormat.parse(it.terminDatum)?.time ?: Long.MAX_VALUE } catch (_: Exception) { Long.MAX_VALUE }
        }.thenBy { it.terminUhrzeit })
        .take(8)

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
                    Text("Umsatz: ${euro(kundenAuftraege.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz) })}")
                    val offen = kundenAuftraege.filter { it.zahlungsstatus != "Bezahlt" }
                    Text("Offene Zahlungen: ${euro(offen.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz) })}", color = if (offen.isEmpty()) KuemmeroGreen else KuemmeroError, fontWeight = FontWeight.Bold)
                    val rechnungen = kundenAuftraege.filter { it.rechnungsnummer.isNotBlank() }
                    Text("Rechnungen: ${rechnungen.size}", fontWeight = FontWeight.Bold)
                    rechnungen.takeLast(5).reversed().forEach { r ->
                        Text(
                            "${r.rechnungsnummer} · ${euro(gesamtbetrag(r.stunden, r.material, r.fahrt, r.stundensatz))} · ${if (r.zahlungsstatus == "Bezahlt") "Bezahlt" else "Offen"}",
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
                    loeschIndex = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { loeschIndex = null }) { Text("Abbrechen") } }
        )
    }

    MaterialTheme(colorScheme = KuemmeroColors) {
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
                        Triple("Aufträge", "▣", "Aufträge"),
                        Triple("Kunden", "♙", "Kunden"),
                        Triple("Kalender", "▦", "Kalender"),
                        Triple("Mehr", "⋯", "Mehr")
                    ).forEach { (label, iconText, page) ->
                        NavigationBarItem(
                            selected = hauptseite == page,
                            onClick = {
                                hauptseite = page
                                if (page == "Aufträge") {
                                    auftragsSeite = "Liste"
                                    bearbeiteIndex = null
                                }
                            },
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
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Liste" to "📋 Aufträge", "Neu" to "➕ Neuer Auftrag").forEach { (seite, text) ->
                            val aktiv = auftragsSeite == seite
                            Surface(
                                modifier = Modifier.height(44.dp).clickable {
                                    if (seite == "Neu") {
                                        bearbeiteIndex = null
                                        kunde = ""; strasse = ""; ort = ""; leistung = ""
                                        stunden = ""; material = ""; fahrt = ""
                                        status = "Offen"; zahlungsstatus = "Offen"; bezahltAm = ""
                                        terminDatum = ""; terminUhrzeit = ""; notiz = ""
                                        fotosVorher = emptyList(); fotosNachher = emptyList(); unterschriftPfad = ""
                                    }
                                    auftragsSeite = seite
                                },
                                shape = RoundedCornerShape(22.dp),
                                color = if (aktiv) KuemmeroGreen else KuemmeroMint,
                                border = BorderStroke(1.5.dp, if (aktiv) KuemmeroGreen else Color(0xFF7A8A82))
                            ) {
                                Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                    Text(text, color = if (aktiv) Color.White else KuemmeroGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (bearbeiteIndex != null) {
                            Surface(
                                modifier = Modifier.height(44.dp),
                                shape = RoundedCornerShape(22.dp),
                                color = KuemmeroGreen
                            ) {
                                Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                    Text("✏️ Auftrag bearbeiten", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (auftragsSeite == "Liste") {
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
                }

                if (auftragsSeite != "Liste") {
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
                            Text(if (unterschriftPfad.isBlank()) "✍ Kunden-Unterschrift aufnehmen" else "✓ Unterschrift vorhanden", fontWeight = FontWeight.Bold)
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
                                    arbeitsstunden, materialKosten, fahrtKosten, rate, status, zahlungsstatus, bezahltAm,
                                    terminDatum.trim(), terminUhrzeit.trim(), notiz.trim(), fotosVorher, fotosNachher, unterschriftPfad,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.rechnungsnummer } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.rechnungsdatum } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.faelligAm } ?: "",
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.arbeitsStart } ?: 0L,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.arbeitsEnde } ?: 0L,
                                    bearbeiteIndex?.let { auftraege.getOrNull(it)?.arbeitsSekunden } ?: 0L
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
                                terminDatum = ""
                                terminUhrzeit = ""
                                notiz = ""
                                fotosVorher = emptyList()
                                fotosNachher = emptyList()
                                unterschriftPfad = ""
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
                    KlappBereich("💾 Sicherung", sicherungBereichOffen, { sicherungBereichOffen = !sicherungBereichOffen }) {
                        OutlinedButton(onClick = { createBackup.launch("kuemmero-backup.json") }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("Sicherung speichern", fontWeight = FontWeight.SemiBold) }
                        Button(onClick = { restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)) { Text("Daten wiederherstellen", fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = { val ok = sichereBackupAutomatisch(context); android.widget.Toast.makeText(context, if (ok) "Sicherung aktualisiert." else "Bitte zuerst eine Backup-Datei speichern.", 1).show() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(26.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("Sicherung jetzt aktualisieren", fontWeight = FontWeight.SemiBold) }
                    }
                }
                } // Ende Neu/Bearbeiten

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
                    val offeneSumme = offeneAuftraege.sumOf { gesamtbetrag(it.stunden, it.material, it.fahrt, it.stundensatz) }
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
                itemsIndexed(gefilterteAuftraege) { _, pair ->
                    val index = pair.first
                    val a = pair.second
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { kundenAkteName = a.kunde },
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
                                euro(gesamtbetrag(a.stunden, a.material, a.fahrt, a.stundensatz)),
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
                            }

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
                                        val aktualisiert = a.copy(
                                            arbeitsStart = jetzt,
                                            arbeitsEnde = 0L,
                                            arbeitszeitUebernommen = false
                                        )
                                        auftraege = auftraege.toMutableList().apply { set(index, aktualisiert) }
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

                            Button(
                                onClick = {
                                    bearbeiteIndex = index
                                    nummer = a.nummer.ifBlank { nummer }
                                    datum = a.datum.ifBlank { datum }
                                    gueltigBis = a.gueltigBis.ifBlank { gueltigBis }
                                    kunde = a.kunde
                                    strasse = a.kundenStrasse
                                    ort = a.kundenOrt
                                    leistung = a.leistung
                                    stunden = a.stunden.toString().replace(".", ",")
                                    material = a.material.toString().replace(".", ",")
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
                                    auftragsSeite = "Bearbeiten"
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
                                    val nextStatus = when (a.status) {
                                        "Offen" -> "In Bearbeitung"
                                        "In Bearbeitung" -> "Erledigt"
                                        else -> a.status
                                    }
                                    if (nextStatus != a.status) {
                                        auftraege = auftraege.toMutableList().apply {
                                            set(index, a.copy(status = nextStatus))
                                        }
                                        speichereAuftraege(context, auftraege)
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
                                        rechnungFuerIndex = index
                                        val name = a.kunde.ifBlank { "Kunde" }
                                        rechnungLauncher.launch("KÜMMERO-Rechnung-$name.pdf")
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)
                                ) {
                                    Text("Rechnung erstellen", fontWeight = FontWeight.Bold)
                                }
                            }

                            if (a.status == "Abgerechnet" && a.rechnungsnummer.isNotBlank()) {
                                Button(
                                    onClick = { druckeRechnungPdf(context, a) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)
                                ) {
                                    Text("🧾 Rechnung PDF drucken", fontWeight = FontWeight.Bold)
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
                                        Column(Modifier.weight(1f)) { Text("Termine", color = Color.White); Text("${termineHeute.size}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                                        Column(Modifier.weight(1f)) { Text("Offene Aufträge", color = Color.White); Text("$offeneAuftraege", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                                        Column(Modifier.weight(1f)) { Text("Offen €", color = Color.White); Text(euro(offeneZahlungSumme), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
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
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.5.dp, KuemmeroGreenLight)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(a.terminUhrzeit.ifBlank { "Ohne Uhrzeit" }, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                        Text(a.kunde, color = KuemmeroText, style = MaterialTheme.typography.titleMedium)
                                        Text(a.leistung, color = KuemmeroText)
                                        Text(a.kundenStrasse + if (a.kundenOrt.isBlank()) "" else ", ${a.kundenOrt}", color = KuemmeroText)
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
                        if (kunden.isEmpty()) {
                            item { Text("Noch keine Kunden gespeichert.", color = KuemmeroText) }
                        } else {
                            itemsIndexed(kunden) { _, k ->
                                Card(Modifier.fillMaxWidth().clickable { kundenAkteName = k.name }, colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.5.dp, KuemmeroGreenLight)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(k.name, style = MaterialTheme.typography.titleMedium, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                        Text(k.adresse, color = KuemmeroText)
                                        Text(k.ort, color = KuemmeroText)
                                        Text("Aufträge: ${auftraege.count { it.kunde == k.name }}", color = KuemmeroText)
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
                    "Mehr" -> {
                        item { Text("Mehr", style = MaterialTheme.typography.headlineSmall, color = KuemmeroGreen, fontWeight = FontWeight.Bold) }
                        item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = KuemmeroSurface), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Sicherung & Daten", style = MaterialTheme.typography.titleMedium, color = KuemmeroGreen, fontWeight = FontWeight.Bold)
                                    OutlinedButton(onClick = { createBackup.launch("kuemmero-backup.json") }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("Sicherung speichern") }
                                    Button(onClick = { restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)) { Text("Daten wiederherstellen", fontWeight = FontWeight.Bold) }
                                    OutlinedButton(onClick = { val ok = sichereBackupAutomatisch(context); android.widget.Toast.makeText(context, if (ok) "Sicherung aktualisiert." else "Bitte zuerst eine Backup-Datei speichern.", 1).show() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp), border = BorderStroke(2.dp, KuemmeroGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)) { Text("Sicherung jetzt aktualisieren") }
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
