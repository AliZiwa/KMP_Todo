package dev.aliziwa.todo.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The name is a setting now, so the greeting has to read naturally without one. */
class GreetingTest {

    @Test
    fun aNameIsAppendedAfterAComma() {
        assertEquals("Good morning, Sam", greetingLine(9, "Sam"))
        assertEquals("Good evening, Sam", greetingLine(19, "  Sam  "))
    }

    @Test
    fun noNameLeavesNoDanglingPunctuation() {
        assertEquals("Good morning", greetingLine(9, null))
        assertEquals("Good afternoon", greetingLine(14, ""))
        assertEquals("Good night", greetingLine(23, "   "))
    }
}
