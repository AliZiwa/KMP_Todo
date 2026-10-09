package dev.aliziwa.todo.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun theGgufDefaultIsTheOnePointFiveBillionQwen() {
        val default = ModelCatalog.defaultFor(listOf("gguf"))
        assertEquals(ModelCatalog.qwen25_1_5b_instruct_q4, default)
        assertTrue(default!!.displayName.contains("1.5B"))
        assertTrue(default.url.endsWith(".gguf"))
    }

    @Test
    fun theSmallerQwenIsOfferedButNeverTheDefault() {
        val suggestions = ModelCatalog.suggestionsFor(listOf("gguf"))
        assertTrue(suggestions.contains(ModelCatalog.qwen25_0_5b_instruct_q4))
        assertTrue(suggestions.indexOf(ModelCatalog.qwen25_0_5b_instruct_q4) > 0)
    }

    @Test
    fun androidGetsTheLitertlmBuild() {
        assertEquals(ModelCatalog.gemma3_1b_it_litertlm, ModelCatalog.defaultFor(listOf("litertlm")))
    }

    @Test
    fun extensionsAreMatchedLenientlyAndUnknownOnesYieldNothing() {
        assertEquals(ModelCatalog.qwen25_1_5b_instruct_q4, ModelCatalog.defaultFor(listOf(".GGUF")))
        assertTrue(ModelCatalog.suggestionsFor(listOf("onnx")).isEmpty())
        assertNull(ModelCatalog.defaultFor(listOf("onnx")))
    }

    @Test
    fun fileNamesAreDerivedFromDownloadUrls() {
        assertEquals(
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            ModelCatalog.fileNameFromUrl(ModelCatalog.qwen25_1_5b_instruct_q4.url),
        )
        assertEquals("a.gguf", ModelCatalog.fileNameFromUrl("https://x/y/a.gguf?download=true"))
        assertNull(ModelCatalog.fileNameFromUrl("https://example.com/repo"))
        assertEquals("gguf", ModelCatalog.extensionOf("model.GGUF"))
    }
}
