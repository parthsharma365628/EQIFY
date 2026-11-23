package com.example.eqify

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.eqify.ui.components.*
import com.example.eqify.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Your week in sound (FEATURES_NEXT_PLAN.md §5) ─────────────────────

private const val RELEASES_LINK = "github.com/parthsharma365628/eqify-releases"

@Composable
private fun shareColor(index: Int, genre: String) = EqifyTheme.colors.let { c ->
    if (genre.equals("Other", ignoreCase = true)) c.textFaint else c.chart[index % c.chart.size]
}

@Composable
fun WeekMixBar(genres: List<GenreShare>, modifier: Modifier = Modifier) {
    val c = EqifyTheme.colors
    Row(
        modifier.height(12.dp).clip(PillShape).background(c.surfaceHighest)
            .semantics { contentDescription = genres.joinToString { "${it.genre} ${it.percent} percent" } },
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        genres.take(6).forEachIndexed { i, g ->
            if (g.count > 0) Box(Modifier.weight(g.count.toFloat()).fillMaxHeight().background(shareColor(i, g.genre)))
        }
    }
}

/** The full sheet opened from Home's "Your week in sound" row. */
@Composable
fun WeekInSoundSheet(stats: WeekStats, onRecent: () -> Unit, onDismiss: () -> Unit) {
    val c = EqifyTheme.colors
    var sharing by remember { mutableStateOf(false) }
    if (sharing) ShareWeekDialog(stats, onDismiss = { sharing = false })

    EqSheet(
        title = "Your week in sound",
        subtitle = "Songs detected in the past 7 days. This counts songs, not listening time.",
        onDismiss = onDismiss,
        actions = {
            TextAction("Recent tracks", onRecent)
            if (stats.shareable) TonalButton("Share", { sharing = true }, icon = EqIcons.Share)
        }
    ) {
        if (stats.isEmpty) {
            EmptyState(EqIcons.Music, "No songs detected yet", body = "Turn on song detection and play some music.")
            return@EqSheet
        }
        // Headline numbers
        Row(horizontalArrangement = Arrangement.spacedBy(EqSpacing.xl)) {
            Stat("${stats.songs}", "songs detected", stats.previousSongs?.let { prev ->
                val d = stats.songs - prev
                when { d > 0 -> "+$d vs last week"; d < 0 -> "$d vs last week"; else -> "same as last week" }
            })
            stats.timeOfDay?.let { Stat(it.label, "most active") }
        }

        SectionHeader("Genres")
        WeekMixBar(stats.genres, Modifier.fillMaxWidth())
        Column {
            stats.genres.take(6).forEachIndexed { i, g ->
                Row(Modifier.fillMaxWidth().padding(vertical = EqSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(PillShape).background(shareColor(i, g.genre)))
                    Spacer(Modifier.width(EqSpacing.s))
                    Text(GenreChoice.label(g.genre), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    g.changePoints?.takeIf { it != 0 }?.let {
                        Text(if (it > 0) "▲$it " else "▼${-it} ", style = MaterialTheme.typography.labelSmall.tabular(),
                            color = c.textMuted,
                            modifier = Modifier.semantics { contentDescription = "${if (it > 0) "up" else "down"} $it points" })
                    }
                    ValueReadout(g.percent.toFloat(), "%", decimals = 0)
                }
            }
        }

        if (stats.dailyTopGenre.any { it != null }) {
            SectionHeader("Day by day")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(EqSpacing.xs)) {
                val days = java.time.LocalDate.now()
                stats.dailyTopGenre.forEachIndexed { i, genre ->
                    val date = days.minusDays((6 - i).toLong())
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val index = stats.genres.indexOfFirst { it.genre == genre }
                        Box(Modifier.fillMaxWidth().height(28.dp).clip(EqifyShapes.small)
                            .background(if (genre == null) c.surfaceHighest else shareColor(index.coerceAtLeast(0), genre))
                            .semantics { contentDescription = "${date.dayOfWeek}: ${genre ?: "nothing detected"}" })
                        Text(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    }
                }
            }
        }

        if (stats.topArtists.isNotEmpty()) {
            SectionHeader("Top artists")
            stats.topArtists.forEachIndexed { i, a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge.tabular(), color = c.textMuted,
                        modifier = Modifier.width(24.dp))
                    Text(a.artist, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.count}", style = MaterialTheme.typography.labelMedium.tabular(), color = c.textMuted)
                }
            }
        }
        Text("Only songs detected while song detection was on are counted.",
            style = MaterialTheme.typography.bodySmall, color = c.textMuted)
    }
}

@Composable
private fun Stat(value: String, label: String, detail: String? = null) {
    val c = EqifyTheme.colors
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall.condensed(), color = c.textPrimary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.textMuted) }
    }
}

// ── Share card ────────────────────────────────────────────────────────

/** The image people share. Nothing is uploaded by EQify; the user picks where it goes. */
@Composable
private fun WeekShareCard(stats: WeekStats, showArtists: Boolean, modifier: Modifier = Modifier) {
    val c = EqifyTheme.colors
    Column(
        modifier.width(320.dp).clip(EqifyShapes.large).background(c.surfaceRaised).padding(EqSpacing.l),
        verticalArrangement = Arrangement.spacedBy(EqSpacing.m)
    ) {
        Text("MY WEEK IN SOUND", style = MaterialTheme.typography.labelMedium, color = c.accent)
        Column {
            Text("Mostly", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            Text(stats.topGenre?.let { GenreChoice.shortLabel(it) } ?: "A bit of everything",
                style = MaterialTheme.typography.headlineMedium.condensed(), color = c.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WeekMixBar(stats.genres, Modifier.fillMaxWidth())
        Text(stats.genres.take(3).joinToString("  ·  ") { "${GenreChoice.shortLabel(it.genre)} ${it.percent}%" },
            style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${stats.songs} songs" + (stats.timeOfDay?.let { ", mostly ${it.label.lowercase()}" } ?: ""),
            style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        if (showArtists && stats.topArtists.isNotEmpty()) {
            Column {
                Text("On repeat", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                stats.topArtists.take(3).forEach {
                    Text(it.artist, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = c.outline)
        Row(verticalAlignment = Alignment.CenterVertically) {
            EqIcon(EqIcons.Equalizer, null, tint = c.accent, size = 18.dp)
            Spacer(Modifier.width(EqSpacing.xs))
            Column {
                Text("EQify", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text(RELEASES_LINK, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
        }
    }
}

@Composable
private fun ShareWeekDialog(stats: WeekStats, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var showArtists by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Share your week", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(EqSpacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
                WeekShareCard(stats, showArtists, Modifier.drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                })
                if (stats.topArtists.isNotEmpty()) {
                    SwitchRow(title = "Show artists", checked = showArtists, onCheckedChange = { showArtists = it })
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = EqifyTheme.colors.error) }
            }
        },
        confirmButton = {
            TextAction(if (busy) "Preparing…" else "Share", enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    error = try {
                        val bitmap = layer.toImageBitmap().asAndroidBitmap()
                        shareImage(context, bitmap)
                        onDismiss(); null
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { "Couldn't create the image. Try again." }
                    busy = false
                }
            })
        },
        dismissButton = { TextAction("Cancel", enabled = !busy, onClick = onDismiss) }
    )
}

private suspend fun shareImage(context: Context, bitmap: Bitmap) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        File(dir, "eqify-week.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "My week in sound, by EQify: https://$RELEASES_LINK")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share your week").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
