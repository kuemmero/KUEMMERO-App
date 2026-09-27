package de.kuemmero.app

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * KÜMMERO – Buchhaltung
 *
 * Übersicht für Rechnungen, Einnahmen, Ausgaben und Buchungen.
 * Manuelle Buchungen werden aktuell lokal in SharedPreferences gespeichert.
 */

private const val BUCHHALTUNG_KEY = "buchhaltung_eintraege"
private const val BUCHHALTUNG_BELEGE_KEY = "buchhaltung_belege"
private const val BUCHHALTUNG_PREFS_NAME = "kuemmero_speicher"

private val BuchGreen = Color(0xFF07883F)
private val BuchGreenDark = Color(0xFF075B31)
private val BuchBackground = Color(0xFFF1F9F1)
private val BuchCard = Color.White
private val BuchBlue = Color(0xFFE8F3FC)
private val BuchGreenCard = Color(0xFFE8F7EC)
private val BuchOrange = Color(0xFFFFF3DE)
private val BuchRose = Color(0xFFFFECEC)
private val BuchGray = Color(0xFFF0F3F5)

private fun euro(wert: Double): String =
    String.format(Locale.GERMANY, "%.2f €", wert)

data class Buchung(
    val typ: String,
    val datum: String,
    val beleg: String,
    val partner: String,
    val kategorie: String,
    val betrag: Double,
    val status: String
)

data class BuchBeleg(
    val datum: String,
    val nummer: String,
    val beschreibung: String,
    val kategorie: String,
    val betrag: Double,
    val buchungTyp: String,
    val dateiUri: String
)

private fun ladeBuchungen(context: Context): List<Buchung> {
    val raw = context
        .getSharedPreferences(BUCHHALTUNG_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(BUCHHALTUNG_KEY, "") ?: ""

    if (raw.isBlank()) return emptyList()

    return raw.split("\n").mapNotNull { line ->
        val teile = line.split("|")
        if (teile.size != 7) null else Buchung(
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

private fun speichereBuchungen(context: Context, buchungen: List<Buchung>) {
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

    context.getSharedPreferences(BUCHHALTUNG_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(BUCHHALTUNG_KEY, raw)
        .apply()
}

private fun ladeBelege(context: Context): List<BuchBeleg> {
    val raw = context
        .getSharedPreferences(BUCHHALTUNG_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(BUCHHALTUNG_BELEGE_KEY, "") ?: ""

    if (raw.isBlank()) return emptyList()

    return raw.split("\n").mapNotNull { line ->
        val teile = line.split("|")
        if (teile.size != 7) null else BuchBeleg(
            datum = teile[0],
            nummer = teile[1],
            beschreibung = teile[2],
            kategorie = teile[3],
            betrag = teile[4].toDoubleOrNull() ?: 0.0,
            buchungTyp = teile[5],
            dateiUri = teile[6]
        )
    }
}

private fun speichereBelege(context: Context, belege: List<BuchBeleg>) {
    val raw = belege.joinToString("\n") {
        listOf(
            it.datum,
            it.nummer,
            it.beschreibung,
            it.kategorie,
            it.betrag.toString(),
            it.buchungTyp,
            it.dateiUri
        ).joinToString("|")
    }

    context.getSharedPreferences(BUCHHALTUNG_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(BUCHHALTUNG_BELEGE_KEY, raw)
        .apply()
}

@Composable
private fun BuchStatCard(
    modifier: Modifier = Modifier,
    background: Color,
    symbol: String,
    title: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = background)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(symbol, fontSize = 25.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    fontSize = if (title == "Rechnungsumsatz") 12.sp else 14.sp,
                    color = BuchGreenDark,
                    maxLines = if (title == "Rechnungsumsatz") 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    value,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF102A20)
                )
            }
        }
    }
}

@Composable
private fun BuchActionTile(
    symbol: String,
    title: String,
    subtitle: String,
    background: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = background)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.White.copy(alpha = 0.65f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(symbol, fontSize = 27.sp)
            }
            Spacer(Modifier.height(7.dp))
            Text(title, fontWeight = FontWeight.Bold, color = Color(0xFF102A20))
            Text(subtitle, fontSize = 12.sp, color = Color(0xFF60716A), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun BuchhaltungScreen(
    context: Context,
    auftraege: List<Auftrag>,
    onBack: () -> Unit,
    onRechnungClick: (Auftrag) -> Unit
) {
    var buchungen by remember { mutableStateOf(ladeBuchungen(context)) }
    var eingabeOffen by remember { mutableStateOf(false) }
    var typ by remember { mutableStateOf("Einnahme") }
    var datum by remember { mutableStateOf("") }
    var beleg by remember { mutableStateOf("") }
    var partner by remember { mutableStateOf("") }
    var kategorie by remember { mutableStateOf("Sonstiges") }
    var betrag by remember { mutableStateOf("") }
    var auswertungOffen by remember { mutableStateOf(false) }
    var rechnungenOffen by remember { mutableStateOf(false) }
    var belege by remember { mutableStateOf(ladeBelege(context)) }
    var belegeOffen by remember { mutableStateOf(false) }
    var belegEingabeOffen by remember { mutableStateOf(false) }
    var belegDatum by remember { mutableStateOf("") }
    var belegNummer by remember { mutableStateOf("") }
    var belegBeschreibung by remember { mutableStateOf("") }
    var belegKategorie by remember { mutableStateOf("Sonstiges") }
    var belegBetrag by remember { mutableStateOf("") }
    var belegTyp by remember { mutableStateOf("Ausgabe") }
    var belegDateiUri by remember { mutableStateOf("") }
    var belegZumLoeschen by remember { mutableStateOf<BuchBeleg?>(null) }

    val belegDateiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Manche Dateianbieter unterstützen keine dauerhafte URI-Freigabe.
            }
            belegDateiUri = uri.toString()
        }
    }

    val rechnungen = auftraege.filter { it.rechnungsnummer.isNotBlank() }
    val offeneRechnungen = rechnungen.count { it.zahlungsstatus != "Bezahlt" }

    fun rechnungsBetrag(a: Auftrag): Double =
        (a.stunden * a.stundensatz) +
                a.material +
                a.fahrt +
                a.zuschlagBetrag +
                a.erstellungskosten

    val rechnungsUmsatz = rechnungen.sumOf { rechnungsBetrag(it) }
    val bezahlteRechnungsSumme = rechnungen
        .filter { it.zahlungsstatus == "Bezahlt" }
        .sumOf { rechnungsBetrag(it) }
    val offeneRechnungsSumme = rechnungen
        .filter { it.zahlungsstatus != "Bezahlt" }
        .sumOf { rechnungsBetrag(it) }
    // Bezahlte Rechnungen sind tatsächliche Einnahmen der Buchhaltung.
    // Manuell erfasste Einnahmen werden zusätzlich berücksichtigt, aber nicht doppelt,
    // wenn die Buchung bereits über eine Rechnungsnummer mit einer Rechnung verknüpft ist.
    val manuelleEinnahmen = buchungen
        .filter { buchung ->
            buchung.typ == "Einnahme" &&
                    rechnungen.none { rechnung ->
                        rechnung.rechnungsnummer.isNotBlank() &&
                                buchung.beleg.trim().equals(rechnung.rechnungsnummer.trim(), ignoreCase = true)
                    }
        }
        .sumOf { it.betrag }
    val erfassteEinnahmen = bezahlteRechnungsSumme + manuelleEinnahmen
    val erfassteAusgaben = buchungen.filter { it.typ == "Ausgabe" }.sumOf { it.betrag }
    val erfasstesErgebnis = erfassteEinnahmen - erfassteAusgaben

    val heute = remember { Date() }
    val monatJahr = remember(heute) { SimpleDateFormat("MM.yyyy", Locale.GERMANY).format(heute) }
    val jahr = remember(heute) { SimpleDateFormat("yyyy", Locale.GERMANY).format(heute) }

    fun buchungPasstZu(buchung: Buchung, muster: String): Boolean =
        buchung.datum.trim().contains(muster)

    fun buchungPasstZu(datumText: String, muster: String): Boolean =
        datumText.trim().contains(muster)

    val manuelleEinnahmenBuchungen = buchungen.filter { buchung ->
        buchung.typ == "Einnahme" &&
                rechnungen.none { rechnung ->
                    rechnung.rechnungsnummer.isNotBlank() &&
                            buchung.beleg.trim().equals(rechnung.rechnungsnummer.trim(), ignoreCase = true)
                }
    }
    val monatlicheRechnungseinnahmen = rechnungen
        .filter {
            it.zahlungsstatus == "Bezahlt" &&
                    buchungPasstZu(it.bezahltAm.ifBlank { it.rechnungsdatum }, monatJahr)
        }
        .sumOf { rechnungsBetrag(it) }
    val monatlicheEinnahmen = monatlicheRechnungseinnahmen + manuelleEinnahmenBuchungen
        .filter { buchungPasstZu(it.datum, monatJahr) }
        .sumOf { it.betrag }
    val monatlicheAusgaben = buchungen
        .filter { it.typ == "Ausgabe" && buchungPasstZu(it.datum, monatJahr) }
        .sumOf { it.betrag }
    val jaehrlicheRechnungseinnahmen = rechnungen
        .filter {
            it.zahlungsstatus == "Bezahlt" &&
                    buchungPasstZu(it.bezahltAm.ifBlank { it.rechnungsdatum }, jahr)
        }
        .sumOf { rechnungsBetrag(it) }
    val jaehrlicheEinnahmen = jaehrlicheRechnungseinnahmen + manuelleEinnahmenBuchungen
        .filter { buchungPasstZu(it.datum, jahr) }
        .sumOf { it.betrag }
    val jaehrlicheAusgaben = buchungen
        .filter { it.typ == "Ausgabe" && buchungPasstZu(it.datum, jahr) }
        .sumOf { it.betrag }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BuchBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← Zurück", color = BuchGreen, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = BuchCard)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(BuchGreen.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▥", fontSize = 32.sp, color = BuchGreen)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "Buchhaltung",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF102A20)
                        )
                        Text(
                            "Übersicht • Einnahmen • Ausgaben • Rechnungen",
                            fontSize = 13.sp,
                            color = Color(0xFF60716A)
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BuchStatCard(
                    modifier = Modifier.weight(1f),
                    background = BuchBlue,
                    symbol = "▤",
                    title = "Rechnungen",
                    value = rechnungen.size.toString()
                )
                BuchStatCard(
                    modifier = Modifier.weight(1f),
                    background = BuchOrange,
                    symbol = "⌛",
                    title = "Offene Rechnungen",
                    value = offeneRechnungen.toString()
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BuchStatCard(
                    modifier = Modifier.weight(1f),
                    background = BuchGreenCard,
                    symbol = "◉",
                    title = "Rechnungsumsatz",
                    value = euro(rechnungsUmsatz)
                )
                BuchStatCard(
                    modifier = Modifier.weight(1f),
                    background = BuchGreenCard,
                    symbol = "↓",
                    title = "Einnahmen",
                    value = euro(erfassteEinnahmen)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BuchStatCard(
                    modifier = Modifier.weight(1f),
                    background = BuchGreenCard,
                    symbol = "✓",
                    title = "Bezahlt €",
                    value = euro(bezahlteRechnungsSumme)
                )
                BuchStatCard(
                    modifier = Modifier.weight(1f),
                    background = BuchOrange,
                    symbol = "⌛",
                    title = "Offen €",
                    value = euro(offeneRechnungsSumme)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BuchStatCard(
                    modifier = Modifier.weight(1f),
                    background = BuchRose,
                    symbol = "↑",
                    title = "Ausgaben",
                    value = euro(erfassteAusgaben)
                )
                BuchStatCard(
                    modifier = Modifier.weight(1f),
                    background = BuchGray,
                    symbol = "▥",
                    title = "Ergebnis",
                    value = euro(erfasstesErgebnis)
                )
            }
        }

        item {
            Button(
                onClick = { eingabeOffen = !eingabeOffen },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BuchGreen)
            ) {
                Text(
                    if (eingabeOffen) "Eingabe schließen" else "⊕  Neue Einnahme / Ausgabe erfassen",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (!eingabeOffen) {
                    Spacer(Modifier.weight(1f))
                    Text("›", fontSize = 28.sp)
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    val testDatum = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
                    val testNummer = "TEST-001"
                    val testBetrag = 123.45
                    val testBeleg = BuchBeleg(
                        datum = testDatum,
                        nummer = testNummer,
                        beschreibung = "Testbeleg",
                        kategorie = "Muster-Test",
                        betrag = testBetrag,
                        buchungTyp = "Einnahme",
                        dateiUri = ""
                    )
                    val testBuchung = Buchung(
                        typ = "Einnahme",
                        datum = testDatum,
                        beleg = testNummer,
                        partner = "Musterkunde",
                        kategorie = "Muster-Test",
                        betrag = testBetrag,
                        status = "Bezahlt"
                    )
                    belege = belege.filterNot { it.nummer.equals(testNummer, ignoreCase = true) } + testBeleg
                    buchungen = buchungen.filterNot { it.beleg.equals(testNummer, ignoreCase = true) } + testBuchung
                    speichereBelege(context, belege)
                    speichereBuchungen(context, buchungen)
                    belegeOffen = true
                    android.widget.Toast.makeText(
                        context,
                        "Muster-Test angelegt: 123,45 € · TEST-001",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(2.dp, BuchGreen),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BuchGreen)
            ) {
                Text("🧪 Muster-Test anlegen", fontWeight = FontWeight.Bold)
            }
        }

        if (eingabeOffen) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Neue Buchung", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BuchGreenDark)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = typ == "Einnahme",
                                onClick = { typ = "Einnahme" },
                                label = { Text("Einnahme") }
                            )
                            FilterChip(
                                selected = typ == "Ausgabe",
                                onClick = { typ = "Ausgabe" },
                                label = { Text("Ausgabe") }
                            )
                        }
                        OutlinedTextField(value = datum, onValueChange = { datum = it }, label = { Text("Datum") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = beleg, onValueChange = { beleg = it }, label = { Text("Beleg-/Rechnungs-Nr.") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = partner, onValueChange = { partner = it }, label = { Text("Kunde / Lieferant") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = kategorie, onValueChange = { kategorie = it }, label = { Text("Kategorie / Leistung") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = betrag, onValueChange = { betrag = it }, label = { Text("Betrag €") }, modifier = Modifier.fillMaxWidth())
                        if (beleg.isNotBlank()) {
                            val passenderBeleg = belege.firstOrNull {
                                it.nummer.trim().equals(beleg.trim(), ignoreCase = true)
                            }
                            Text(
                                if (passenderBeleg != null)
                                    "📎 Beleg verknüpft: ${passenderBeleg.beschreibung}"
                                else
                                    "Beleg-Nr. kann mit einem gespeicherten Beleg verknüpft werden."
                                ,
                                fontSize = 12.sp,
                                color = if (passenderBeleg != null) BuchGreen else Color(0xFF60716A)
                            )
                        }
                        Button(
                            onClick = {
                                val wert = betrag.replace(",", ".").toDoubleOrNull()
                                if (wert != null && wert >= 0.0) {
                                    val neueListe = buchungen + Buchung(
                                        typ = typ,
                                        datum = datum,
                                        beleg = beleg,
                                        partner = partner,
                                        kategorie = kategorie,
                                        betrag = wert,
                                        status = if (typ == "Einnahme") "Offen" else "Erfasst"
                                    )
                                    buchungen = neueListe
                                    speichereBuchungen(context, neueListe)
                                    datum = ""
                                    beleg = ""
                                    partner = ""
                                    betrag = ""
                                    eingabeOffen = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = BuchGreen)
                        ) {
                            Text("Buchung speichern")
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Schnellzugriff", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color(0xFF102A20))
                Text("4 Bereiche", fontSize = 12.sp, color = Color(0xFF60716A))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BuchActionTile("▤", "Rechnungen", "Liste & Status", BuchGreenCard) { rechnungenOffen = true }
                BuchActionTile("▣", "Ausgaben", "Erfassen", BuchBlue) { eingabeOffen = true; typ = "Ausgabe" }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BuchActionTile("▤", "Belege", "Fotos & PDF", BuchOrange) { belegeOffen = true }
                BuchActionTile("▥", "Auswertung", "Monat / Jahr", Color(0xFFF1EAFE)) { auswertungOffen = true }
            }
        }

        item {
            Text("Letzte Buchungen", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color(0xFF102A20))
        }

        // Bezahlte Rechnungen werden automatisch als Einnahmen in den
        // "Letzten Buchungen" angezeigt. Bereits manuell erfasste Buchungen
        // werden anhand der Rechnungsnummer nicht doppelt angezeigt.
        val bezahlteRechnungenAlsBuchungen = rechnungen
            .filter { it.zahlungsstatus == "Bezahlt" }
            .map { rechnung ->
                Buchung(
                    typ = "Einnahme",
                    datum = rechnung.bezahltAm.ifBlank { rechnung.rechnungsdatum },
                    beleg = rechnung.rechnungsnummer,
                    partner = rechnung.kunde.ifBlank { "Kunde" },
                    kategorie = "Rechnung",
                    betrag = rechnungsBetrag(rechnung),
                    status = "Bezahlt"
                )
            }

        val alleAnzeigenBuchungen = (buchungen + bezahlteRechnungenAlsBuchungen)
            .distinctBy { "${it.beleg}|${it.typ}|${it.betrag}|${it.datum}" }

        if (alleAnzeigenBuchungen.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BuchGreen.copy(alpha = 0.55f), RoundedCornerShape(22.dp)),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("▤", fontSize = 42.sp, color = Color(0xFF60716A))
                        Spacer(Modifier.height(8.dp))
                        Text("Noch keine Buchungen vorhanden.", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF60716A), textAlign = TextAlign.Center)
                        Text("Über den Button oben kannst du Einnahmen oder Ausgaben erfassen.", fontSize = 14.sp, color = Color(0xFF60716A), textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            items(alleAnzeigenBuchungen.asReversed().take(20)) { buchung ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${buchung.typ}: ${euro(buchung.betrag)}", fontWeight = FontWeight.Bold, color = BuchGreenDark)
                        Text("${buchung.datum} • ${buchung.partner} • ${buchung.kategorie}", color = Color(0xFF45554F))
                        if (buchung.beleg.isNotBlank()) {
                            val passenderBeleg = belege.firstOrNull {
                                it.nummer.trim().equals(buchung.beleg.trim(), ignoreCase = true)
                            }
                            Text("Beleg: ${buchung.beleg}", fontSize = 13.sp)
                            Text(
                                if (passenderBeleg != null)
                                    "📎 Beleg verknüpft – ${passenderBeleg.beschreibung}"
                                else
                                    "Kein gespeicherter Beleg mit dieser Nummer gefunden.",
                                fontSize = 12.sp,
                                color = if (passenderBeleg != null) BuchGreen else Color(0xFFB35A00)
                            )
                        }
                        Text("Status: ${buchung.status}", fontSize = 12.sp, color = Color(0xFF60716A))
                    }
                }
            }
        }
    }

    if (rechnungenOffen) {
        AlertDialog(
            onDismissRequest = { rechnungenOffen = false },
            title = { Text("Rechnungen", fontWeight = FontWeight.Bold, color = BuchGreenDark) },
            text = {
                if (rechnungen.isEmpty()) {
                    Text("Noch keine Rechnungen vorhanden.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(rechnungen) { rechnung ->
                            val betragRechnung = rechnungsBetrag(rechnung)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        rechnungenOffen = false
                                        onRechnungClick(rechnung)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (rechnung.zahlungsstatus == "Bezahlt") BuchGreenCard else BuchOrange
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        rechnung.rechnungsnummer,
                                        fontWeight = FontWeight.Bold,
                                        color = BuchGreenDark
                                    )
                                    Text(rechnung.kunde.ifBlank { "Kunde" }, fontWeight = FontWeight.SemiBold)
                                    Text(euro(betragRechnung), fontWeight = FontWeight.Bold)
                                    Text(
                                        "Status: ${rechnung.zahlungsstatus}${if (rechnung.faelligAm.isNotBlank()) " • fällig ${rechnung.faelligAm}" else ""}",
                                        fontSize = 12.sp,
                                        color = Color(0xFF60716A)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { rechnungenOffen = false }) {
                    Text("Schließen", color = BuchGreen, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (auswertungOffen) {
        AlertDialog(
            onDismissRequest = { auswertungOffen = false },
            title = { Text("Auswertung", fontWeight = FontWeight.Bold, color = BuchGreenDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Aktueller Monat: $monatJahr", fontWeight = FontWeight.Bold)
                    Text("Einnahmen: ${euro(monatlicheEinnahmen)}")
                    Text("Ausgaben: ${euro(monatlicheAusgaben)}")
                    Text("Ergebnis: ${euro(monatlicheEinnahmen - monatlicheAusgaben)}", fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    Text("Aktuelles Jahr: $jahr", fontWeight = FontWeight.Bold)
                    Text("Einnahmen: ${euro(jaehrlicheEinnahmen)}")
                    Text("Ausgaben: ${euro(jaehrlicheAusgaben)}")
                    Text("Ergebnis: ${euro(jaehrlicheEinnahmen - jaehrlicheAusgaben)}", fontWeight = FontWeight.Bold)
                    Text(
                        "Die Auswertung verwendet das Datum im Format TT.MM.JJJJ der manuell erfassten Buchungen.",
                        fontSize = 12.sp,
                        color = Color(0xFF60716A)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { auswertungOffen = false }) {
                    Text("Schließen", color = BuchGreen, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (belegeOffen) {
        AlertDialog(
            onDismissRequest = { belegeOffen = false },
            title = { Text("Belege", fontWeight = FontWeight.Bold, color = BuchGreenDark) },
            text = {
                if (belege.isEmpty()) {
                    Text("Noch keine Belege vorhanden. Über \"Neuer Beleg\" kannst du ein Foto oder PDF zuordnen.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 430.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(belege.asReversed()) { beleg ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(
                                        beleg.beschreibung.ifBlank { "Beleg" },
                                        fontWeight = FontWeight.Bold,
                                        color = BuchGreenDark
                                    )
                                    Text("${beleg.datum} • ${beleg.buchungTyp} • ${euro(beleg.betrag)}")
                                    if (beleg.nummer.isNotBlank()) Text("Nr.: ${beleg.nummer}", fontSize = 12.sp)
                                    Text("Kategorie: ${beleg.kategorie}", fontSize = 12.sp, color = Color(0xFF60716A))
                                    Text(
                                        if (beleg.dateiUri.isNotBlank()) "Datei angehängt" else "Keine Datei angehängt",
                                        fontSize = 12.sp,
                                        color = if (beleg.dateiUri.isNotBlank()) BuchGreen else Color(0xFFB35A00)
                                    )
                                    val verknuepfteBuchungen = buchungen.filter {
                                        it.beleg.isNotBlank() &&
                                                it.beleg.trim().equals(beleg.nummer.trim(), ignoreCase = true)
                                    }
                                    if (beleg.nummer.isNotBlank() && verknuepfteBuchungen.isNotEmpty()) {
                                        Text(
                                            "🔗 Mit ${verknuepfteBuchungen.size} Buchung(en) verknüpft",
                                            fontSize = 12.sp,
                                            color = BuchGreen,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (beleg.dateiUri.isNotBlank()) {
                                            TextButton(onClick = {
                                                try {
                                                    val uri = android.net.Uri.parse(beleg.dateiUri)
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(
                                                            uri,
                                                            context.contentResolver.getType(uri) ?: "application/octet-stream"
                                                        )
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(Intent.createChooser(intent, "Beleg öffnen mit"))
                                                } catch (_: ActivityNotFoundException) {
                                                    // Keine passende App auf dem Gerät vorhanden.
                                                } catch (_: Exception) {
                                                    // Ungültige oder nicht mehr erreichbare Datei-URI.
                                                }
                                            }) {
                                                Text("Datei öffnen", color = BuchGreen, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        TextButton(onClick = { belegZumLoeschen = beleg }) {
                                            Text("Löschen", color = Color(0xFFB3261E), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        belegDatum = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
                        belegNummer = ""
                        belegBeschreibung = ""
                        belegKategorie = "Sonstiges"
                        belegBetrag = ""
                        belegTyp = "Ausgabe"
                        belegDateiUri = ""
                        belegEingabeOffen = true
                    }) {
                        Text("Neuer Beleg", color = BuchGreen, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { belegeOffen = false }) {
                        Text("Schließen", color = BuchGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }

    if (belegZumLoeschen != null) {
        AlertDialog(
            onDismissRequest = { belegZumLoeschen = null },
            title = { Text("Beleg löschen?", fontWeight = FontWeight.Bold, color = BuchGreenDark) },
            text = {
                Text(
                    "Soll der Beleg \"${belegZumLoeschen?.beschreibung?.ifBlank { "Beleg" }}\" wirklich gelöscht werden?",
                )
            },
            dismissButton = {
                TextButton(onClick = { belegZumLoeschen = null }) {
                    Text("Abbrechen", color = BuchGreen)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val zuLoeschen = belegZumLoeschen
                    if (zuLoeschen != null) {
                        belege = belege.filterNot { it == zuLoeschen }
                        speichereBelege(context, belege)
                    }
                    belegZumLoeschen = null
                }) {
                    Text("Löschen", color = Color(0xFFB3261E), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (belegEingabeOffen) {
        AlertDialog(
            onDismissRequest = { belegEingabeOffen = false },
            title = { Text("Neuen Beleg erfassen", fontWeight = FontWeight.Bold, color = BuchGreenDark) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = belegDatum,
                        onValueChange = { belegDatum = it },
                        label = { Text("Datum (TT.MM.JJJJ)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = belegNummer,
                        onValueChange = { belegNummer = it },
                        label = { Text("Beleg-Nr. / Rechnungs-Nr.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = belegBeschreibung,
                        onValueChange = { belegBeschreibung = it },
                        label = { Text("Beschreibung / Lieferant") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = belegKategorie,
                        onValueChange = { belegKategorie = it },
                        label = { Text("Kategorie") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = belegBetrag,
                        onValueChange = { belegBetrag = it.replace(',', '.') },
                        label = { Text("Betrag €") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = belegTyp == "Ausgabe",
                            onClick = { belegTyp = "Ausgabe" },
                            label = { Text("Ausgabe") }
                        )
                        FilterChip(
                            selected = belegTyp == "Einnahme",
                            onClick = { belegTyp = "Einnahme" },
                            label = { Text("Einnahme") }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            belegDateiLauncher.launch(arrayOf("application/pdf", "image/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (belegDateiUri.isBlank()) "Foto / PDF auswählen" else "Datei ausgewählt ✓")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { belegEingabeOffen = false }) {
                    Text("Abbrechen", color = Color(0xFF60716A))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val betragWert = belegBetrag.replace(',', '.').toDoubleOrNull()
                        if (belegDatum.isNotBlank() && belegBeschreibung.isNotBlank() && betragWert != null) {
                            val neuerBeleg = BuchBeleg(
                                datum = belegDatum.trim(),
                                nummer = belegNummer.trim(),
                                beschreibung = belegBeschreibung.trim(),
                                kategorie = belegKategorie.trim().ifBlank { "Sonstiges" },
                                betrag = betragWert,
                                buchungTyp = belegTyp,
                                dateiUri = belegDateiUri
                            )
                            belege = belege + neuerBeleg
                            speichereBelege(context, belege)
                            belegEingabeOffen = false
                            belegeOffen = true
                        }
                    }
                ) {
                    Text("Beleg speichern", color = BuchGreen, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

}
