package de.kuemmero.app

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Auftrag(
    val kunde: String,
    val kundenStrasse: String,
    val kundenOrt: String,
    val leistung: String,
    val stunden: Double,
    val material: Double,
    val fahrt: Double,
    val stundensatz: Double
)

private const val PREFS_NAME = "kuemmero_speicher"
private const val AUFTRAEGE_KEY = "auftraege"
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
            o.optString("kunde"),
            o.optString("kundenStrasse"),
            o.optString("kundenOrt"),
            o.optString("leistung"),
            o.optDouble("stunden", 0.0),
            o.optDouble("material", 0.0),
            o.optDouble("fahrt", 0.0),
            o.optDouble("stundensatz", 42.0)
        )
    }
}

private fun speichereAuftraege(context: Context, liste: List<Auftrag>) {
    val json = JSONArray()
    liste.forEach { a ->
        json.put(JSONObject().apply {
            put("kunde", a.kunde)
            put("kundenStrasse", a.kundenStrasse)
            put("kundenOrt", a.kundenOrt)
            put("leistung", a.leistung)
            put("stunden", a.stunden)
            put("material", a.material)
            put("fahrt", a.fahrt)
            put("stundensatz", a.stundensatz)
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
    c.drawText("ANGEBOT", 40f, 200f, p)
    p.textSize = 12f
    c.drawText("Angebotsnummer: $nummer", 40f, 225f, p)
    c.drawText("Datum: $datum", 40f, 245f, p)
    c.drawText("Gültig bis: $gueltigBis", 40f, 265f, p)
    c.drawText("Kunde: $kunde", 40f, 295f, p)
    c.drawText("Straße: $strasse", 40f, 315f, p)
    c.drawText("PLZ und Ort: $ort", 40f, 335f, p)
    c.drawText("Leistung: $leistung", 40f, 365f, p)

    c.drawLine(40f, 390f, 550f, 390f, p)
    c.drawText("Arbeitszeit", 40f, 415f, p)
    c.drawText("%.2f Std.".format(Locale.GERMANY, stunden), 250f, 415f, p)
    c.drawText(euro(stunden * stundensatz), 450f, 415f, p)
    c.drawText("Material", 40f, 440f, p)
    c.drawText(euro(material), 450f, 440f, p)
    c.drawText("Fahrtkosten", 40f, 465f, p)
    c.drawText(euro(fahrt), 450f, 465f, p)
    c.drawLine(40f, 480f, 550f, 480f, p)

    val gesamt = stunden * stundensatz + material + fahrt
    p.textSize = 18f
    c.drawText("Gesamtsumme: ${euro(gesamt)}", 40f, 515f, p)
    p.textSize = 11f
    c.drawText("Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.", 40f, 545f, p)
    c.drawText("Auftragserteilung / Unterschrift Kunde:", 40f, 610f, p)
    c.drawLine(40f, 660f, 280f, 660f, p)
    c.drawText("Unterschrift", 40f, 678f, p)
    c.drawLine(330f, 660f, 550f, 660f, p)
    c.drawText("Datum", 330f, 678f, p)
    c.drawText("Vielen Dank für Ihr Vertrauen.", 40f, 730f, p)
    pdf.finishPage(page)
    return pdf
}

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

    // Speichert den Auftrag, dessen PDF für einen gespeicherten Auftrag erstellt werden soll.
    var auftragFuerPdf by remember { mutableStateOf<Auftrag?>(null) }

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
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(STUNDENSATZ_KEY, rate)
                    .putString(AUFTRAEGE_KEY, arr.toString()).commit()
                stundensatz = rate
                auftraege = ladeAuftraege(context)
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

    val arbeitsstunden = zahl(stunden)
    val materialKosten = zahl(material)
    val fahrtKosten = zahl(fahrt)
    val rate = zahl(stundensatz, 42.0)
    val gesamt = arbeitsstunden * rate + materialKosten + fahrtKosten
    val umsatz = auftraege.sumOf { it.stunden * it.stundensatz + it.material + it.fahrt }

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

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("KÜMMERO") }) },
            containerColor = Color(0xFFF1F8F3)
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Text("Haus & Alltag – wir kümmern uns.", style = MaterialTheme.typography.titleMedium) }

                item { OutlinedTextField(nummer, { nummer = it }, label = { Text("Angebotsnummer") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(datum, { datum = it }, label = { Text("Datum") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(gueltigBis, { gueltigBis = it }, label = { Text("Gültig bis") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(kunde, { kunde = it }, label = { Text("Kunde") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(strasse, { strasse = it }, label = { Text("Straße und Hausnummer") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(ort, { ort = it }, label = { Text("PLZ und Ort") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(leistung, { leistung = it }, label = { Text("Leistung") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        stunden, { stunden = it }, label = { Text("Arbeitsstunden") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        material, { material = it }, label = { Text("Material (€)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        fahrt, { fahrt = it }, label = { Text("Fahrtkosten (€)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        stundensatz, {
                            stundensatz = it
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                                .putString(STUNDENSATZ_KEY, it).apply()
                        },
                        label = { Text("Stundensatz (€ / Stunde)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item { Text("Aktueller Gesamtbetrag: ${euro(gesamt)}", style = MaterialTheme.typography.headlineSmall) }

                item {
                    Button(onClick = {
                        if (kunde.isBlank()) {
                            android.widget.Toast.makeText(context, "Bitte Kundennamen eingeben.", 0).show()
                        } else {
                            val a = Auftrag(kunde.trim(), strasse.trim(), ort.trim(), leistung.trim(),
                                arbeitsstunden, materialKosten, fahrtKosten, rate)
                            auftraege = auftraege + a
                            speichereAuftraege(context, auftraege)
                            android.widget.Toast.makeText(context, "Auftrag gespeichert.", 0).show()
                            kunde = ""; strasse = ""; ort = ""; leistung = ""
                            stunden = ""; material = ""; fahrt = ""
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Auftrag speichern") }
                }

                item {
                    Button(onClick = {
                        if (kunde.isBlank()) {
                            android.widget.Toast.makeText(context, "Bitte Kundennamen eingeben.", 0).show()
                        } else {
                            pdfLauncher.launch("KÜMMERO-Angebot-$nummer.pdf")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("PDF-Angebot erstellen") }
                }

                item {
                    OutlinedButton(onClick = { createBackup.launch("kuemmero-backup.json") },
                        modifier = Modifier.fillMaxWidth()) { Text("Sicherung speichern") }
                }
                item {
                    Button(onClick = {
                        restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Daten wiederherstellen") }
                }
                item {
                    OutlinedButton(onClick = {
                        val ok = sichereBackupAutomatisch(context)
                        android.widget.Toast.makeText(
                            context,
                            if (ok) "Sicherung aktualisiert." else "Bitte zuerst eine Backup-Datei speichern.",
                            1
                        ).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Sicherung jetzt aktualisieren") }
                }

                item { HorizontalDivider() }
                item { Text("Übersicht", style = MaterialTheme.typography.titleLarge) }
                item { Text("Gespeicherte Aufträge: ${auftraege.size}") }
                item { Text("Gesamtumsatz gespeicherter Aufträge: ${euro(umsatz)}") }

                item { Text("Gespeicherte Aufträge", style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(auftraege) { index, a ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(a.kunde, style = MaterialTheme.typography.titleMedium)
                            if (a.kundenStrasse.isNotBlank() || a.kundenOrt.isNotBlank())
                                Text(listOf(a.kundenStrasse, a.kundenOrt).filter { it.isNotBlank() }.joinToString(", "))
                            Text(a.leistung)
                            Text(euro(a.stunden * a.stundensatz + a.material + a.fahrt))

                            // PDF direkt aus dem gespeicherten Auftrag erstellen.
                            Button(onClick = {
                                auftragFuerPdf = a
                                pdfLauncher.launch("KÜMMERO-Angebot-$nummer-${a.kunde}.pdf")
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("PDF drucken")
                            }

                            OutlinedButton(onClick = { loeschIndex = index }, modifier = Modifier.fillMaxWidth()) {
                                Text("Auftrag löschen")
                            }
                        }
                    }
                }
            }
        }
    }
}
