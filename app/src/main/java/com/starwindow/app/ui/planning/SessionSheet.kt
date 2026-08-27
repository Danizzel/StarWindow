package com.starwindow.app.ui.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starwindow.app.data.planning.PlannedSession
import com.starwindow.app.data.planning.ReminderLead
import com.starwindow.app.ui.theme.StarWindowColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One planned night, opened from the calendar.
 *
 * Three things live here, and they are three different kinds of decision. **Reminders** are about
 * the weeks before — the app has to speak up while there is still time to act. The **note** is the
 * thing that will have been forgotten by then ("Ha-Filter", "Zufahrt gesperrt, hinten parken").
 * And **Pfad zeigen** is about the night itself: it takes the whole arc the object will trace and
 * draws it over the camera image, so a roof or a tree can be ruled out from the balcony in
 * daylight, weeks ahead — which is the one question standing outside for a minute cannot answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    session: PlannedSession,
    zone: ZoneId,
    /** False when the system will silently drop anything this screen schedules. */
    notificationsAllowed: Boolean,
    onToggleReminder: (ReminderLead) -> Unit,
    onNoteChange: (String) -> Unit,
    onShowPath: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The field is local while it is being typed and written through on the way out: persisting on
    // every keystroke would rewrite the plan file — and reschedule four alarms — per letter.
    var note by remember(session.id) { mutableStateOf(session.note) }
    LaunchedEffect(session.id) { note = session.note }

    ModalBottomSheet(
        onDismissRequest = {
            if (note != session.note) onNoteChange(note)
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = StarWindowColors.NightSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    session.objectLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = StarWindowColors.Starlight,
                )
                Text(
                    text = "Nacht auf ${dateFormat.format(session.date.plusDays(1))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = StarWindowColors.AnchorPoint,
                )
                if (session.usableMinutes > 0) {
                    Text(
                        text = "%.1f h belichtbar · bis %.0f° · Mond %.0f %%".format(
                            session.usableHours, session.bestAltitudeDeg, session.moonIlluminationPercent,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Muted,
                    )
                }
            }

            Button(onClick = onShowPath, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Timeline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Pfad zeigen")
            }
            Text(
                text = "Öffnet die Kamera und zeichnet die Bahn ein, die das Objekt in dieser Nacht " +
                    "zieht – mit Stundenpunkten und einem Pfeil dorthin. So lässt sich schon " +
                    "tagsüber prüfen, ob ein Dach oder ein Baum im Weg steht.",
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )

            HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Erinnerung",
                    style = MaterialTheme.typography.titleSmall,
                    color = StarWindowColors.Starlight,
                )
                Text(
                    text = "Jeweils um ${timeFormat.format(ReminderLead.NOTIFY_TIME)} Uhr.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
                if (!notificationsAllowed && session.reminders.isNotEmpty()) {
                    Text(
                        text = "Benachrichtigungen sind für StarWindow abgeschaltet – diese " +
                            "Erinnerungen bleiben gespeichert, kommen aber nicht an. In den " +
                            "Android-Einstellungen bei den Benachrichtigungen wieder freigeben.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Crosshair,
                    )
                }
                Spacer(Modifier.height(4.dp))
                ReminderLead.ordered.forEach { lead ->
                    ReminderRow(
                        lead = lead,
                        checked = lead in session.reminders,
                        // A lead whose moment has already gone by cannot fire, and offering it as
                        // if it could would be a promise the app cannot keep.
                        inThePast = lead.triggerMillis(session.date, zone) <= System.currentTimeMillis(),
                        onToggle = { onToggleReminder(lead) },
                    )
                }
            }

            HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Notiz",
                    style = MaterialTheme.typography.titleSmall,
                    color = StarWindowColors.Starlight,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text(
                            "Filter, Brennweite, Treffpunkt …",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StarWindowColors.Night,
                        unfocusedContainerColor = StarWindowColors.Night,
                    ),
                )
                Text(
                    "Steht später mit in der Erinnerung.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        if (note != session.note) onNoteChange(note)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Fertig")
                }
                OutlinedButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Löschen")
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(
    lead: ReminderLead,
    checked: Boolean,
    inThePast: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lead.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
                color = if (inThePast) StarWindowColors.Muted else StarWindowColors.Starlight,
            )
            Text(
                text = if (inThePast) "Zeitpunkt liegt schon zurück" else lead.hint,
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
        Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = !inThePast)
    }
}

private val dateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

private fun DateTimeFormatter.format(date: LocalDate): String = date.format(this)
