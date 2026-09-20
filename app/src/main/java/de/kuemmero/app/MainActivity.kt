package de.kuemmero.app

import android.os.Bundle
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

private fun ladeAuftraege(context: Context): List<Auftrag> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val gespeicherteDaten = prefs.getString(AUFTRAEGE_KEY, "[]") ?: "[]"

    val json = try {
        JSONArray(gespeicherteDaten)
    } catch (e: Exception) {
        JSONArray()
    }

    return List(json.length()) { i ->
        val obj = json.optJSONObject(i) ?: JSONObject()

        Auftrag(
            kunde = obj.optString("kunde", ""),
            kundenStrasse = obj.optString("kundenStrasse", ""),
            kundenOrt = obj.optString("kundenOrt", ""),
            leistung = obj.optString("leistung", ""),
            stunden = jsonDouble(obj, "stunden"),
            material = jsonDouble(obj, "material"),
            fahrt = jsonDouble(obj, "fahrt"),
            stundensatz = jsonDouble(obj, "stundensatz", 42.0)
        )
    }
}

private fun jsonDouble(
    obj: JSONObject,
    key: String,
    standardwert: Double = 0.0
): Double {
    val wert = obj.opt(key) ?: return standardwert

    return when (wert) {
        is Number -> wert.toDouble()
        is String -> wert
            .replace(",", ".")
            .trim()
            .toDoubleOrNull() ?: standardwert
        else -> standardwert
    }
}

private const val STUNDENSATZ_KEY = "stundensatz"
private fun erstelleBackup(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    return JSONObject().apply {
        put(
            "stundensatz",
            prefs.getString(STUNDENSATZ_KEY, "42.00") ?: "42.00"
        )
        put(
            "auftraege",
            JSONArray(prefs.getString(AUFTRAEGE_KEY, "[]"))
        )
    }.toString(2)
}
private fun stelleBackupWiederHer(
    context: Context,
    backupText: String
): Int {
    val prefs = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    val text = backupText
        .removePrefix("\uFEFF")
        .trim()

    if (text.isBlank()) {
        throw Exception("Backup-Datei ist leer")
    }

    var auftraege = JSONArray()
    var stundensatz =
        prefs.getString(STUNDENSATZ_KEY, "42.00") ?: "42.00"

    when {
        text.startsWith("{") -> {
            val backup = JSONObject(text)
            val daten = backup.opt("auftraege")

            auftraege = when (daten) {
                is JSONArray -> daten
                is String -> JSONArray(daten)
                else -> throw Exception("Im Backup wurde kein gültiger Auftragsspeicher gefunden")
            }

            val rate = backup.opt("stundensatz")
            if (rate != null && rate.toString().isNotBlank()) {
                stundensatz = rate.toString()
            }
        }

        text.startsWith("[") -> {
            // Älteres Backup-Format: direktes JSON-Array
            auftraege = JSONArray(text)
        }

        else -> {
            throw Exception("Ungültiges Backup-Format")
        }
    }

    // Erst nach erfolgreicher Prüfung dauerhaft speichern.
    val gespeichert = prefs.edit()
        .putString(STUNDENSATZ_KEY, stundensatz)
        .putString(AUFTRAEGE_KEY, auftraege.toString())
        .commit()

    if (!gespeichert) {
        throw Exception("Daten konnten nicht gespeichert werden")
    }

    return auftraege.length()
}

private fun speichereAuftraege(
    context: Context,
    auftraege: List<Auftrag>
) {
    val json = JSONArray()

    auftraege.forEach { auftrag ->
        json.put(
            JSONObject().apply {
                put("kunde", auftrag.kunde)
                put("kundenStrasse", auftrag.kundenStrasse)
                put("kundenOrt", auftrag.kundenOrt)
                put("leistung", auftrag.leistung)
                put("stunden", auftrag.stunden)
                put("material", auftrag.material)
                put("fahrt", auftrag.fahrt)
                put("stundensatz", auftrag.stundensatz)
                put(
    "gesamt",
    auftrag.stunden * auftrag.stundensatz +
            auftrag.material +
            auftrag.fahrt
)
            }
        )
    }

    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(AUFTRAEGE_KEY, json.toString())
        .apply()
}

private fun erstelleAngebotPdf(
    context: Context,
    angebotsnummer: String,
    datum: String,
    kunde: String,
    kundenStrasse: String,
    kundenOrt: String,
    leistung: String,
    stunden: Double,
    material: Double,
    fahrt: Double,
    stundensatz: Double
): PdfDocument {
    val pdf = PdfDocument()

    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
    val page = pdf.startPage(pageInfo)
    val canvas = page.canvas
    val paint = Paint()

    paint.textSize = 28f
canvas.drawText("KÜMMERO", 40f, 60f, paint)

paint.textSize = 14f
canvas.drawText("Haus & Alltag – wir kümmern uns.", 40f, 92f, paint)
canvas.drawText("Hausmeisterservice & Seniorenbetreuung", 40f, 112f, paint)
canvas.drawText("Markus Becker", 40f, 132f, paint)
canvas.drawText("Königsberger Straße 16", 40f, 152f, paint)
canvas.drawText("58675 Hemer", 40f, 172f, paint)
canvas.drawText("Tel.: +49 176 16712509", 40f, 192f, paint)
canvas.drawText("E-Mail: kuemmero@web.de", 40f, 212f, paint)

paint.textSize = 20f
canvas.drawText("ANGEBOT", 40f, 250f, paint)

paint.textSize = 14f
canvas.drawText("Angebotsnummer: $angebotsnummer", 40f, 285f, paint)
canvas.drawText("Datum: $datum", 40f, 305f, paint)
canvas.drawText("Kunde: $kunde", 40f, 340f, paint)
canvas.drawText("Straße: $kundenStrasse", 40f, 365f, paint)
canvas.drawText("PLZ und Ort: $kundenOrt", 40f, 390f, paint)
canvas.drawText("Leistung:", 40f, 415f, paint)
canvas.drawText(leistung, 40f, 440f, paint)

paint.textSize = 12f

// Tabellenüberschrift
canvas.drawText("Position", 40f, 475f, paint)
canvas.drawText("Menge", 260f, 475f, paint)
canvas.drawText("Einzelpreis", 340f, 475f, paint)
canvas.drawText("Betrag", 470f, 475f, paint)

// Trennlinie
canvas.drawLine(40f, 482f, 550f, 482f, paint)

// Arbeitszeit
canvas.drawText("Arbeitszeit", 40f, 505f, paint)
canvas.drawText("%.2f Std.".format(stunden), 260f, 505f, paint)
canvas.drawText("%.2f €".format(stundensatz), 340f, 505f, paint)
canvas.drawText("%.2f €".format(stunden * stundensatz), 470f, 505f, paint)

// Material
canvas.drawText("Material", 40f, 530f, paint)
canvas.drawText("1", 260f, 530f, paint)
canvas.drawText("%.2f €".format(material), 340f, 530f, paint)
canvas.drawText("%.2f €".format(material), 470f, 530f, paint)

// Fahrtkosten
canvas.drawText("Fahrtkosten", 40f, 555f, paint)
canvas.drawText("1", 260f, 555f, paint)
canvas.drawText("%.2f €".format(fahrt), 340f, 555f, paint)
canvas.drawText("%.2f €".format(fahrt), 470f, 555f, paint)

// Trennlinie
canvas.drawLine(40f, 565f, 550f, 565f, paint)

val gesamt = stunden * stundensatz + material + fahrt

paint.textSize = 20f
canvas.drawText(
    "Gesamtsumme: %.2f €".format(gesamt),
    40f, 600f, paint
)

    paint.textSize = 12f
canvas.drawText(
    "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.",
    40f, 635f, paint
)

canvas.drawText(
    "Vielen Dank für Ihr Vertrauen.",
    40f, 665f, paint
)

    pdf.finishPage(page)
    return pdf
}
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KuemmeroApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KuemmeroApp() {
    
    val context = LocalContext.current
    var kunde by remember { mutableStateOf("") }
    var kundenStrasse by remember { mutableStateOf("") }
    var kundenOrt by remember { mutableStateOf("") }
    var angebotsnummer by remember {
    mutableStateOf(
        "ANG-" + java.text.SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            java.util.Locale.GERMANY
        ).format(java.util.Date())
    )
    }
    var datum by remember { mutableStateOf(java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY).format(java.util.Date())) }
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
    var loeschIndex by remember { mutableStateOf<Int?>(null) }

    // Erstellt eine neue Backup-Datei.
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { output ->
                output.write(erstelleBackup(context).toByteArray())
            }
        }
    }

    // Überschreibt eine bereits vorhandene Backup-Datei.
    val backupUpdateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it, "wt")?.use { output ->
                    output.write(erstelleBackup(context).toByteArray())
                } ?: throw Exception("Backup-Datei konnte nicht geöffnet werden")

                android.widget.Toast.makeText(
                    context,
                    "Backup aktualisiert",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "Backup konnte nicht aktualisiert werden: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

val restoreLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri ->
    uri?.let {
        try {
            val backupText = context.contentResolver.openInputStream(it)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readText()
                }
            } ?: throw Exception("Backup-Datei konnte nicht gelesen werden")

            val anzahl = stelleBackupWiederHer(context, backupText)

            stundensatz =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(STUNDENSATZ_KEY, "42.00") ?: "42.00"

            auftraege = ladeAuftraege(context)

            android.widget.Toast.makeText(
                context,
                "Daten erfolgreich wiederhergestellt ($anzahl Aufträge)",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Wiederherstellung fehlgeschlagen: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}

    val arbeitsstunden = stunden.replace(",-", "").replace(",", ".").toDoubleOrNull() ?: 0.0
    val fahrtKosten = fahrt.replace(",-", "").replace(",", ".").toDoubleOrNull() ?: 0.0
    val materialKosten = material.replace(",-", "").replace(",", ".").toDoubleOrNull() ?: 0.0

    val arbeitskosten = arbeitsstunden * (stundensatz.replace(",", ".").toDoubleOrNull() ?: 0.0)
    val gesamt = arbeitskosten + materialKosten + fahrtKosten
val pdfLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/pdf")
) { uri ->
    uri?.let {
        val pdf = erstelleAngebotPdf(
            context,
            angebotsnummer,
            datum,
            kunde,
            kundenStrasse,
            kundenOrt,
            leistung,
            arbeitsstunden,
            materialKosten,
            fahrtKosten,
stundensatz.toDoubleOrNull() ?: 42.0
)

        context.contentResolver.openOutputStream(it)?.use { output ->
            pdf.writeTo(output)
        }

        pdf.close()
    }
}
    loeschIndex?.let { index ->
        if (index in auftraege.indices) {
            AlertDialog(
                onDismissRequest = {
                    loeschIndex = null
                },
                title = {
                    Text("Auftrag löschen?")
                },
                text = {
                    Text("Soll der Auftrag von ${auftraege[index].kunde} wirklich gelöscht werden?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            auftraege = auftraege.toMutableList().apply {
                                removeAt(index)
                            }
                            speichereAuftraege(context, auftraege)
                            loeschIndex = null
                        }
                    ) {
                        Text("Löschen")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            loeschIndex = null
                        }
                    ) {
                        Text("Abbrechen")
                    }
                }
            )
        }
    }

    MaterialTheme {

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("KÜMMERO")
                    }
                )
            },
            containerColor = Color(0xFFF1F8F3),
        ) { padding ->

            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                item {
                    Text(
                        "Haus & Alltag – wir kümmern uns.",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
item {
    OutlinedTextField(
        value = angebotsnummer,
        onValueChange = { angebotsnummer = it },
        label = { Text("Angebotsnummer") },
        modifier = Modifier.fillMaxWidth()
    )
}

item {
    OutlinedTextField(
        value = datum,
        onValueChange = { datum = it },
        label = { Text("Datum") },
        modifier = Modifier.fillMaxWidth()
    )
}
                item {
                    OutlinedTextField(
                        value = kunde,
                        onValueChange = { kunde = it },
                        label = { Text("Kunde") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
    OutlinedTextField(
        value = kundenStrasse,
        onValueChange = { kundenStrasse = it },
        label = { Text("Straße und Hausnummer") },
        modifier = Modifier.fillMaxWidth()
    )
                }
                item {
    OutlinedTextField(
        value = kundenOrt,
        onValueChange = { kundenOrt = it },
        label = { Text("PLZ und Ort") },
        modifier = Modifier.fillMaxWidth()
    )
                }

                item {
                    OutlinedTextField(
                        value = leistung,
                        onValueChange = { leistung = it },
                        label = { Text("Leistung") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = stunden,
                        onValueChange = { stunden = it },
                        label = { Text("Arbeitsstunden") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = material,
                        onValueChange = { material = it },
                        label = { Text("Material (€)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = fahrt,
                        onValueChange = { fahrt = it },
                        label = { Text("Fahrtkosten (€)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
    value = stundensatz,
    onValueChange = {
    stundensatz = it
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(STUNDENSATZ_KEY, it)
        .apply()
},
    label = { Text("Stundensatz (€ / Stunde)") },
    modifier = Modifier.fillMaxWidth()
)
                }

                item {
                    Text(
                        "Gesamt: %.2f €".format(gesamt),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                item {
                    Button(
                        onClick = {
                            backupLauncher.launch("kuemmero-backup.json")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Neue Sicherung erstellen")
                    }
                }

                item {
                    OutlinedButton(
                        onClick = {
                            backupUpdateLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "text/plain",
                                    "application/octet-stream"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Vorhandene Sicherung überschreiben")
                    }
                }
                item {
    Button(
        onClick = {
            restoreLauncher.launch(
                arrayOf(
                    "application/json",
                    "text/plain",
                    "application/octet-stream"
                )
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Daten wiederherstellen")
    }
                }

                item {
                    Button(
                        onClick = {

                            if (kunde.isNotBlank()) {

                                auftraege = auftraege + Auftrag(
                                    kunde = kunde,
                                    kundenStrasse = kundenStrasse,
                                    kundenOrt = kundenOrt,
                                    leistung = leistung,
                                    stunden = arbeitsstunden,
                                    material = materialKosten,
                                    fahrt = fahrtKosten,
                                    stundensatz = stundensatz.replace(",", ".").toDoubleOrNull() ?: 0.0
                                )
                                speichereAuftraege(context, auftraege)

                                kunde = ""
                                kundenStrasse = ""
                                kundenOrt = ""
                                leistung = ""
                                stunden = ""
                                material = ""
                                fahrt = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Auftrag speichern")
                    }
                }
item {
    Button(
        onClick = {
            if (kunde.isNotBlank()) {
                val dateiname = if (angebotsnummer.isNotBlank()) {
                    "KÜMMERO-Angebot-$angebotsnummer.pdf"
                } else {
                    "KÜMMERO-Angebot.pdf"
                }

                pdfLauncher.launch(dateiname)
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("PDF-Angebot erstellen")
    }
}
                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        "Gespeicherte Aufträge",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                itemsIndexed(auftraege) { index, auftrag ->

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {

                            Text(
                                auftrag.kunde,
                                style = MaterialTheme.typography.titleMedium
                            )

                            if (auftrag.kundenStrasse.isNotBlank() || auftrag.kundenOrt.isNotBlank()) {
                                Text(
                                    listOf(auftrag.kundenStrasse, auftrag.kundenOrt)
                                        .filter { it.isNotBlank() }
                                        .joinToString(", ")
                                )
                            }

                            Text(auftrag.leistung)

                            Text(
                                "Gesamt: %.2f €".format(
                                    auftrag.stunden * auftrag.stundensatz +
                                    auftrag.material +
                                    auftrag.fahrt
                                )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedButton(
                                onClick = {
                                    loeschIndex = index
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Auftrag löschen")
                            }
                        }
                    }
                }
            }
        }
    }
}
