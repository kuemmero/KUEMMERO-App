package de.kuemmero.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class Auftrag(
    val kunde: String,
    val leistung: String,
    val stunden: Double,
    val material: Double,
    val fahrt: Double
)

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

    var kunde by remember { mutableStateOf("") }
    var leistung by remember { mutableStateOf("") }
    var stunden by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    var fahrt by remember { mutableStateOf("") }

    var auftraege by remember {
        mutableStateOf(listOf<Auftrag>())
    }

    val arbeitsstunden = stunden.toDoubleOrNull() ?: 0.0
    val materialKosten = material.toDoubleOrNull() ?: 0.0
    val fahrtKosten = fahrt.toDoubleOrNull() ?: 0.0

    val arbeitskosten = arbeitsstunden * 42.0
    val gesamt = arbeitskosten + materialKosten + fahrtKosten

    MaterialTheme {

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("KÜMMERO")
                    }
                )
            }
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
                    Text("Stundensatz: 42,00 €")
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

                            if (kunde.isNotBlank()) {

                                auftraege = auftraege + Auftrag(
                                    kunde = kunde,
                                    leistung = leistung,
                                    stunden = arbeitsstunden,
                                    material = materialKosten,
                                    fahrt = fahrtKosten
                                )

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
                                    auftrag.stunden * 42 +
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
