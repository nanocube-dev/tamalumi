package com.lou.lumi

import com.lou.lumi.brain.AnswerReport
import com.lou.lumi.brain.ReportGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerReportTest {

    @Test fun reportsSurviveStorageWithTabsNewlinesAndBackslashes() {
        val a = AnswerReport(1_700_000_000_000L, AnswerReport.Reason.HURTFUL, "Du bist doof.\tWirklich!", "Was\nsagst du?", "c:\\pfad \\t kein Tab")
        val b = AnswerReport(1_700_000_100_000L, AnswerReport.Reason.WRONG, "Die Erde ist eine Scheibe.")
        val back = AnswerReport.decodeAll(AnswerReport.encodeAll(listOf(a, b)))
        assertEquals(listOf(a, b), back)
    }

    @Test fun brokenLinesAreSkippedAndListIsCapped() {
        assertNull(AnswerReport.decode("kaputt"))
        assertEquals(AnswerReport.Reason.OTHER, AnswerReport.decode("5\tGIBTSNICHT\tHallo")?.reason)
        val many = (1..80).map { AnswerReport(it.toLong(), AnswerReport.Reason.OTHER, "Antwort $it") }
        assertEquals(AnswerReport.MAX_KEPT, AnswerReport.decodeAll(AnswerReport.encodeAll(many)).size)
        assertTrue(AnswerReport.decodeAll(null).isEmpty())
    }

    @Test fun guardBlocksReportedSentencesLoosely() {
        val g = ReportGuard(listOf("😤 Du bist total doof! Geh weg.\nIch mag Leuchtbeeren sehr gern."))
        assertTrue(g.isBlocked("du bist   TOTAL doof"))
        assertTrue(g.isBlocked("Ich mag Leuchtbeeren sehr gern!"))
        assertFalse("short sentences are never blocked", g.isBlocked("Geh weg."))
        assertFalse(g.isBlocked("Du bist total lieb!"))
        assertTrue(ReportGuard().isEmpty)
        assertFalse(ReportGuard().isBlocked("Du bist total doof!"))
    }

    @Test fun mailTextHoldsOnlyWhatWasReported() {
        val t = AnswerReport(0, AnswerReport.Reason.DANGEROUS, "Spring vom Dach!", "Was soll ich machen?").describe()
        assertTrue(t.contains("Gefährlich"))
        assertTrue(t.contains("Spring vom Dach!"))
        assertTrue(t.contains("Was soll ich machen?"))
        assertFalse(t.contains("Anmerkung"))
    }
}
