package io.github.retrofrost.cts.android.rewrite

import org.junit.Assert.*
import org.junit.Test

class CsvImportTest {
    @Test fun familiarHeadersAndOwnedImages() {
        val cards = CsvImport.cards("Number,Title,Badge Header,Badge Value,Badge Unit,Image,Description\n1,Alpha,Age,42,years,https://example.com/a.png,Example")
        val card = cards.single()
        assertEquals("Alpha", card.title)
        assertEquals("42 years", card.badgePrimary)
        assertEquals("Age", card.badgeSecondary)
        assertEquals(card.id, card.imageSubcard.parentCardId)
    }
    @Test fun quotedCommasNewlinesAndEscapedQuotes() {
        val card = CsvImport.cards("\uFEFFTitle,Description\r\n\"A,B\",\"Line 1\nLine \"\"2\"\"\"\r\n").single()
        assertEquals("A,B", card.title)
        assertEquals("Line 1\nLine \"2\"", card.description)
    }
    @Test fun tabSeparatedData() {
        assertEquals("A", CsvImport.cards("Title\tValue\nA\t10").single().title)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnclosedQuotes() {
        CsvImport.cards("Title,Description\nA,\"broken")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownHeaders() {
        CsvImport.cards("foo,bar\nA,B")
    }
}
