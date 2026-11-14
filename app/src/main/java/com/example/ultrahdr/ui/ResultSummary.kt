package com.example.ultrahdr.ui

import com.example.ultrahdr.processing.MediaStoreSaver
import com.example.ultrahdr.processing.Outcome
import com.example.ultrahdr.processing.PhotoResult

enum class Tone { SUCCESS, INFO, ERROR }

enum class LineKind { UPDATED, COPIED, SKIPPED, FAILED, NOT_STARTED }

data class SummaryLine(val kind: LineKind, val text: String)

/** Everything the result card says, worked out in one place so it can be tested without a screen. */
data class Summary(
    val title: String,
    val tone: Tone,
    val lines: List<SummaryLine>,
    /** Reasons, grouped: which photos were copied or failed, and why. */
    val notes: List<String>,
    val reassurance: String?
)

fun summarize(results: Collection<PhotoResult>, total: Int, stopped: Boolean): Summary {
    val updated = results.count { it.outcome == Outcome.UPDATED }
    val copied = results.count { it.outcome == Outcome.COPIED }
    val alreadyHdr = results.count { it.outcome == Outcome.ALREADY_HDR }
    val noHighlights = results.count { it.outcome == Outcome.NO_HIGHLIGHTS }
    val failed = results.count { it.outcome == Outcome.FAILED }
    val converted = updated + copied
    val notStarted = (total - results.size).coerceAtLeast(0)

    val title = when {
        converted == 1 -> "1 photo is now HDR"
        converted > 1 -> "$converted photos are now HDR"
        failed == 1 && results.size == 1 -> "This photo couldn't be converted"
        failed > 0 -> "These photos couldn't be converted"
        else -> "Nothing needed changing"
    }
    val tone = when {
        converted > 0 -> Tone.SUCCESS
        failed > 0 -> Tone.ERROR
        else -> Tone.INFO
    }

    val lines = buildList {
        if (updated > 0) add(SummaryLine(LineKind.UPDATED, "$updated updated in place in Gallery"))
        if (copied > 0) {
            // Gallery shows the Pictures/UltraHDR folder as an album with this name.
            val what = if (copied == 1) "copy" else "copies"
            add(SummaryLine(LineKind.COPIED, "$copied $what in the ${MediaStoreSaver.COPY_FOLDER} album"))
        }
        if (alreadyHdr > 0) add(SummaryLine(LineKind.SKIPPED, "$alreadyHdr skipped, already HDR"))
        if (noHighlights > 0) {
            add(SummaryLine(LineKind.SKIPPED, "$noHighlights skipped, no bright highlights"))
        }
        // A single failed photo is already the title; a count under it would only repeat it.
        if (failed > 0 && results.size > 1) add(SummaryLine(LineKind.FAILED, "$failed failed"))
        if (stopped && notStarted > 0) add(SummaryLine(LineKind.NOT_STARTED, "$notStarted not started"))
    }

    val notes = results
        .filter { it.note != null && (it.outcome == Outcome.COPIED || it.outcome == Outcome.FAILED) }
        .groupBy { it.note!! }
        .map { (note, group) -> "${names(group.map { it.name })}: $note" }

    val convertedResults = results.filter { it.converted }
    val reassurance = if (convertedResults.isNotEmpty() && convertedResults.all { it.keptOriginalPixels }) {
        "The original picture data was kept exactly. Only brightness information was added."
    } else {
        null
    }

    return Summary(title, tone, lines, notes, reassurance)
}

private fun names(all: List<String>): String = when {
    all.size <= 2 -> all.joinToString(" and ")
    else -> "${all[0]}, ${all[1]} and ${all.size - 2} more"
}
