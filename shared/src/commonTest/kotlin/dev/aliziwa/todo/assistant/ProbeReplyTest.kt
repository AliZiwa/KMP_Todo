package dev.aliziwa.todo.assistant

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settings "Test" button must not pass on an engine that only emits empty JSON. */
class ProbeReplyTest {
    @Test
    fun realTextPasses() {
        assertTrue(isMeaningfulProbeReply("OK"))
        assertTrue(isMeaningfulProbeReply("ok!"))
        assertTrue(isMeaningfulProbeReply("""{"reply":"hi"}"""))
    }

    @Test
    fun emptyOrEmptyJsonFails() {
        assertFalse(isMeaningfulProbeReply(""))
        assertFalse(isMeaningfulProbeReply("   \n "))
        assertFalse(isMeaningfulProbeReply("{}"))
        assertFalse(isMeaningfulProbeReply("{ }"))
        assertFalse(isMeaningfulProbeReply("\n{\n}\n"))
        assertFalse(isMeaningfulProbeReply("[]"))
        assertFalse(isMeaningfulProbeReply("null"))
        assertFalse(isMeaningfulProbeReply("\"\""))
    }
}
