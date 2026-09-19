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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class Auftrag(
    val kunde: String,
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
    val json = JSONArray(prefs.getString(AUFTRAEGE_KEY, "[]"))

    return List(json.length()) { i ->
        val obj = json.getJSONObject(i)

        Auftrag(
            kunde = obj.getString("kunde"),
            leistung = obj.getString("leistung"),
            stunden = obj.getDouble("stunden"),
            material = obj.getDouble("material"),
            fahrt = obj.getDouble("fahrt"),
            stundensatz = obj.getDouble("stundensatz")
        )
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
) {
    val backup = JSONObject(backupText)

    val prefs = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    prefs.edit()
        .putString(
            STUNDENSATZ_KEY,
            backup.getString("stundensatz")
        )
        .putString(
            AUFTRAEGE_KEY,
            backup.getJSONArray("auftraege").toString()
        )
        .apply()
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
                put("leistung", auftrag.leistung)
                put("stunden", auftrag.stunden)
                put("material", auftrag.material)
                put("fahrt", auftrag.fahrt)
                put("stundensatz", auftrag.stundensatz)
            }
        )
    }

    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(AUFTRAEGE_KEY, json.toString())
        .apply()
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
    var angebotsnummer by remember { mutableStateOf("") }
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
    val backupLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json")
) { uri ->
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { output ->
            output.write(erstelleBackup(context).toByteArray())
        }
    }
}

val restoreLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri ->
    uri?.let {
        context.contentResolver.openInputStream(it)?.use { input ->
            val backupText = input.bufferedReader().use { reader ->
                reader.readText()
            }

            stelleBackupWiederHer(context, backupText)

            stundensatz =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(STUNDENSATZ_KEY, "42.00") ?: "42.00"

            auftraege = ladeAuftraege(context)
        }
    }
}

    val arbeitsstunden = stunden.replace(",-", "").replace(",", ".").toDoubleOrNull() ?: 0.0
    val fahrtKosten = fahrt.replace(",-", "").replace(",", ".").toDoubleOrNull() ?: 0.0
    val materialKosten = material.replace(",-", "").replace(",", ".").toDoubleOrNull() ?: 0.0

    val arbeitskosten = arbeitsstunden * (stundensatz.replace(",", ".").toDoubleOrNull() ?: 0.0)
    val gesamt = arbeitskosten + materialKosten + fahrtKosten

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
        Text("Daten sichern")
    }
                }
                item {
    Button(
        onClick = {
            restoreLauncher.launch(
                arrayOf("application/json", "text/plain")
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
                                    leistung = leistung,
                                    stunden = arbeitsstunden,
                                    material = materialKosten,
                                    fahrt = fahrtKosten,
                                    stundensatz = stundensatz.replace(",", ".").toDoubleOrNull() ?: 0.0
                                )
                                speichereAuftraege(context, auftraege)

                                kunde = ""
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
                    HorizontalDivider()
                }

                item {
                    Text(
                        "Gespeicherte Aufträge",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                items(auftraege) { auftrag ->

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

                            Text(auftrag.leistung)

                            Text(
                                "Gesamt: %.2f €".format(
                                    auftrag.stunden * auftrag.stundensatz +
                                    auftrag.material +
                                    auftrag.fahrt
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
