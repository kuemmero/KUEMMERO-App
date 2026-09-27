package de.kuemmero.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/*
 * KÜMMERO – Buchhaltung
 *
 * Diese Datei ist als eigener Baustein für die bestehende KÜMMERO-App gedacht.
 * Sie greift auf die vorhandenen Auftrag-Daten zu und speichert manuell
 * erfasste Buchungen lokal in SharedPreferences.
 */

private const val BUCHHALTUNG_KEY = "buchhaltung_eintraege"

data class Buchung(
    val typ: String,
    val datum: String,
    val beleg: String,
    val partner: String,
    val kategorie: String,
    val betrag: Double,
    val status: String
)

private fun ladeBuchungen(context: Context): List<Buchung> {
    val raw = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(BUCHHALTUNG_KEY, "") ?: ""

    if (raw.isBlank()) return emptyList()

    return raw.split("\n").mapNotNull { line ->
        val teile = line.split("|")
        if (teile.size != 7) {
            null
        } else {
            Buchung(
                typ = teile[0],
                datum = teile[1],
                beleg = teile[2],
                partner = teile[3],
                kategorie = teile[4],
                betrag = teile[5].toDoubleOrNull() ?: 0.0,
                status = teile[6]
            )
        }
    }
}

private fun speichereBuchungen(
    context: Context,
    buchungen: List<Buchung>
) {
    val raw = buchungen.joinToString("\n") {
        listOf(
            it.typ,
            it.datum,
            it.beleg,
            it.partner,
            it.kategorie,
            it.betrag.toString(),
            it.status
        ).joinToString("|")
    }

    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(BUCHHALTUNG_KEY, raw)
        .apply()
}

@Composable
fun BuchhaltungScreen(
    context: Context,
    auftraege: List<Auftrag>,
    onBack: () -> Unit
) {
    var buchungen by remember {
        mutableStateOf(ladeBuchungen(context))
    }

    var eingabeOffen by remember { mutableStateOf(false) }
    var typ by remember { mutableStateOf("Einnahme") }
    var datum by remember { mutableStateOf("") }
    var beleg by remember { mutableStateOf("") }
    var partner by remember { mutableStateOf("") }
    var kategorie by remember { mutableStateOf("Sonstiges") }
    var betrag by remember { mutableStateOf("") }

    val rechnungen = auftraege.filter {
        it.rechnungsnummer.isNotBlank()
    }

    val offeneRechnungen = rechnungen.count {
        it.zahlungsstatus != "Bezahlt"
    }

    val rechnungsUmsatz = rechnungen.sumOf { a ->
        (a.stunden * a.stundensatz) +
                a.material +
                a.fahrt +
                a.zuschlagBetrag +
                a.erstellungskosten
    }

    val erfassteEinnahmen = buchungen
        .filter { it.typ == "Einnahme" }
        .sumOf { it.betrag }

    val erfassteAusgaben = buchungen
        .filter { it.typ == "Ausgabe" }
        .sumOf { it.betrag }

    val erfasstesErgebnis = erfassteEinnahmen - erfassteAusgaben

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(
                        "← Zurück",
                        color = KuemmeroGreen
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    "Buchhaltung",
                    style = MaterialTheme.typography.headlineSmall,
                    color = KuemmeroGreen
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "KÜMMERO – Buchhaltungsübersicht",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text("Rechnungen: ${rechnungen.size}")
                    Text("Offene Rechnungen: $offeneRechnungen")

                    Text(
                        "Rechnungsumsatz: " +
                                String.format(
                                    Locale.GERMANY,
                                    "%.2f €",
                                    rechnungsUmsatz
                                )
                    )

                    Text(
                        "Erfasste Einnahmen: " +
                                String.format(
                                    Locale.GERMANY,
                                    "%.2f €",
                                    erfassteEinnahmen
                                )
                    )

                    Text(
                        "Erfasste Ausgaben: " +
                                String.format(
                                    Locale.GERMANY,
                                    "%.2f €",
                                    erfassteAusgaben
                                )
                    )

                    Text(
                        "Ergebnis: " +
                                String.format(
                                    Locale.GERMANY,
                                    "%.2f €",
                                    erfasstesErgebnis
                                )
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    eingabeOffen = !eingabeOffen
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (eingabeOffen)
                        "Eingabe schließen"
                    else
                        "+ Einnahme / Ausgabe erfassen"
                )
            }
        }

        if (eingabeOffen) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Neue Buchung",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = typ == "Einnahme",
                                onClick = {
                                    typ = "Einnahme"
                                },
                                label = {
                                    Text("Einnahme")
                                }
                            )

                            FilterChip(
                                selected = typ == "Ausgabe",
                                onClick = {
                                    typ = "Ausgabe"
                                },
                                label = {
                                    Text("Ausgabe")
                                }
                            )
                        }

                        OutlinedTextField(
                            value = datum,
                            onValueChange = {
                                datum = it
                            },
                            label = {
                                Text("Datum")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = beleg,
                            onValueChange = {
                                beleg = it
                            },
                            label = {
                                Text("Beleg-/Rechnungs-Nr.")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = partner,
                            onValueChange = {
                                partner = it
                            },
                            label = {
                                Text("Kunde / Lieferant")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = kategorie,
                            onValueChange = {
                                kategorie = it
                            },
                            label = {
                                Text("Kategorie / Leistung")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = betrag,
                            onValueChange = {
                                betrag = it
                            },
                            label = {
                                Text("Betrag €")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                val wert = betrag
                                    .replace(",", ".")
                                    .toDoubleOrNull()

                                if (wert != null && wert >= 0.0) {
                                    val neueBuchung = Buchung(
                                        typ = typ,
                                        datum = datum,
                                        beleg = beleg,
                                        partner = partner,
                                        kategorie = kategorie,
                                        betrag = wert,
                                        status = if (
                                            typ == "Einnahme"
                                        ) {
                                            "Offen"
                                        } else {
                                            "Erfasst"
                                        }
                                    )

                                    val neueListe =
                                        buchungen + neueBuchung

                                    buchungen = neueListe

                                    speichereBuchungen(
                                        context,
                                        neueListe
                                    )

                                    datum = ""
                                    beleg = ""
                                    partner = ""
                                    betrag = ""
                                    eingabeOffen = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Buchung speichern")
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Letzte Buchungen",
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(
            buchungen
                .asReversed()
                .take(20)
        ) { buchung ->
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "${buchung.typ}: " +
                                String.format(
                                    Locale.GERMANY,
                                    "%.2f €",
                                    buchung.betrag
                                ),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        "${buchung.datum} • " +
                                "${buchung.partner} • " +
                                buchung.kategorie
                    )

                    if (buchung.beleg.isNotBlank()) {
                        Text("Beleg: ${buchung.beleg}")
                    }

                    Text("Status: ${buchung.status}")
                }
            }
        }
    }
}
