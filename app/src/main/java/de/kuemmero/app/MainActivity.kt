package de.kuemmero.app

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    val status: String = "Offen"
)

private const val PREFS_NAME = "kuemmero_speicher"
private const val AUFTRAEGE_KEY = "auftraege"
private const val KUNDEN_KEY = "kunden"
private const val STUNDENSATZ_KEY = "stundensatz"
private const val BACKUP_URI_KEY = "backup_uri"

private fun zahl(text: String, standard: Double = 0.0): Double =
    text.replace("€", "").replace(" ", "").replace(",", ".").trim()
        .toDoubleOrNull() ?: standard

private fun euro(value: Double): String =
    String.format(Locale.GERMANY, "%.2f €", value)

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
            o.optString("status", "Offen").ifBlank { "Offen" }
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
    stundensatz: Double
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
    c.drawText(euro(stunden * stundensatz), 450f, 448f, p)
    c.drawText("Material", 40f, 473f, p)
    c.drawText(euro(material), 450f, 473f, p)
    c.drawText("Fahrtkosten", 40f, 498f, p)
    c.drawText(euro(fahrt), 450f, 498f, p)
    c.drawLine(40f, 513f, 550f, 513f, p)
    val gesamt = stunden * stundensatz + material + fahrt
    p.textSize = 18f
    c.drawText("Gesamtsumme: ${euro(gesamt)}", 40f, 548f, p)
    p.textSize = 11f
    c.drawText("Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.", 40f, 578f, p)
    c.drawText("Auftragserteilung / Unterschrift Kunde:", 40f, 643f, p)
    c.drawLine(40f, 693f, 280f, 693f, p)
    c.drawText("Unterschrift", 40f, 711f, p)
    c.drawLine(330f, 693f, 550f, 693f, p)
    c.drawText("Datum", 330f, 711f, p)
    c.drawText("Vielen Dank für Ihr Vertrauen.", 40f, 763f, p)
    pdf.finishPage(page)
    return pdf
}


private fun erstelleRechnungPdf(
    nummer: String,
    rechnungsdatum: String,
    kunde: String,
    strasse: String,
    ort: String,
    leistung: String,
    stunden: Double,
    material: Double,
    fahrt: Double,
    stundensatz: Double
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
    c.drawText("Kunde: $kunde", 40f, 305f, p)
    c.drawText("Adresse: $strasse", 40f, 325f, p)
    c.drawText("PLZ und Ort: $ort", 40f, 345f, p)
    c.drawText("Leistung: $leistung", 40f, 375f, p)

    c.drawLine(40f, 400f, 550f, 400f, p)
    c.drawText("Arbeitszeit", 40f, 425f, p)
    c.drawText("%.2f Std.".format(Locale.GERMANY, stunden), 250f, 425f, p)
    c.drawText(euro(stunden * stundensatz), 450f, 425f, p)
    c.drawText("Material", 40f, 450f, p)
    c.drawText(euro(material), 450f, 450f, p)
    c.drawText("Fahrtkosten", 40f, 475f, p)
    c.drawText(euro(fahrt), 450f, 475f, p)
    c.drawLine(40f, 490f, 550f, 490f, p)

    val gesamt = stunden * stundensatz + material + fahrt
    p.textSize = 18f
    c.drawText("Gesamtsumme: ${euro(gesamt)}", 40f, 530f, p)
    p.textSize = 11f
    c.drawText("Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.", 40f, 558f, p)
    c.drawText("Bitte überweisen Sie den Rechnungsbetrag innerhalb von 14 Tagen.", 40f, 590f, p)
    c.drawText("Vielen Dank für Ihr Vertrauen.", 40f, 640f, p)
    pdf.finishPage(page)
    return pdf
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
                auftrag.stunden, auftrag.material, auftrag.fahrt, auftrag.stundensatz
            )
            val info = PrintDocumentInfo.Builder(dateiname)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KuemmeroApp() }
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
    val listeState = rememberLazyListState()

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

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let {
            val a = auftragFuerPdf
            val pdf = if (a != null) {
                erstellePdf(
                    context, nummer, datum, gueltigBis,
                    a.kunde, a.kundenStrasse, a.kundenOrt, a.leistung,
                    a.stunden, a.material, a.fahrt, a.stundensatz
                )
            } else {
                erstellePdf(
                    context, nummer, datum, gueltigBis, kunde, strasse, ort, leistung,
                    zahl(stunden), zahl(material), zahl(fahrt), zahl(stundensatz, 42.0)
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
                    val pdf = erstelleRechnungPdf(
                        rechnungsnummer,
                        rechnungsdatum,
                        a.kunde,
                        a.kundenStrasse,
                        a.kundenOrt,
                        a.leistung,
                        a.stunden,
                        a.material,
                        a.fahrt,
                        a.stundensatz
                    )
                    context.contentResolver.openOutputStream(it)?.use { out -> pdf.writeTo(out) }
                    pdf.close()
                    auftraege = auftraege.toMutableList().apply {
                        set(index, a.copy(status = "Abgerechnet"))
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
    val gesamt = arbeitsstunden * rate + materialKosten + fahrtKosten
    val umsatz = auftraege.sumOf { it.stunden * it.stundensatz + it.material + it.fahrt }

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
            containerColor = KuemmeroBackground
        ) { padding ->
            LazyColumn(
                state = listeState,
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Offen", "In Bearbeitung", "Erledigt", "Abgerechnet").forEach { option ->
                            FilterChip(
                                selected = status == option,
                                onClick = { status = option },
                                label = { Text(option) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = KuemmeroGreen,
                                    selectedLabelColor = Color.White,
                                    containerColor = KuemmeroMint
                                )
                            )
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
                                    arbeitsstunden, materialKosten, fahrtKosten, rate, status
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
                    OutlinedButton(
                        onClick = { createBackup.launch("kuemmero-backup.json") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(2.dp, KuemmeroGreen),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                    ) {
                        Text("Sicherung speichern", fontWeight = FontWeight.SemiBold)
                    }
                }

                item {
                    Button(
                        onClick = {
                            restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KuemmeroGreenLight)
                    ) {
                        Text("Daten wiederherstellen", fontWeight = FontWeight.Bold)
                    }
                }

                item {
                    OutlinedButton(
                        onClick = {
                            val ok = sichereBackupAutomatisch(context)
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "Sicherung aktualisiert." else "Bitte zuerst eine Backup-Datei speichern.",
                                1
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(26.dp),
                        border = BorderStroke(2.dp, KuemmeroGreen),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                    ) {
                        Text("Sicherung jetzt aktualisieren", fontWeight = FontWeight.SemiBold)
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
                    Text(
                        "Gespeicherte Aufträge",
                        style = MaterialTheme.typography.headlineSmall,
                        color = KuemmeroGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
                itemsIndexed(auftraege) { index, a ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
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
                                euro(a.stunden * a.stundensatz + a.material + a.fahrt),
                                style = MaterialTheme.typography.titleMedium,
                                color = KuemmeroGreen,
                                fontWeight = FontWeight.Bold
                            )

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

                            OutlinedButton(
                                onClick = {
                                    val nextStatus = when (a.status) {
                                        "Offen" -> "In Bearbeitung"
                                        "In Bearbeitung" -> "Erledigt"
                                        "Erledigt" -> "Abgerechnet"
                                        else -> "Offen"
                                    }
                                    auftraege = auftraege.toMutableList().apply {
                                        set(index, a.copy(status = nextStatus))
                                    }
                                    speichereAuftraege(context, auftraege)
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(2.dp, KuemmeroGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KuemmeroGreen)
                            ) {
                                Text("Status weiter →", fontWeight = FontWeight.Bold)
                            }

                            if (a.status == "Erledigt") {
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

                            OutlinedButton(
                                onClick = { loeschIndex = index },
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
        }
    }
}
