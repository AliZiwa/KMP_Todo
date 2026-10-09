package dev.aliziwa.todo.llm

/**
 * A suggested on-device model. The user may always paste any Hugging Face *resolve* URL instead;
 * [browseUrl] opens the repo so they can pick a different quantisation.
 */
data class ModelSuggestion(
    val id: String,
    val displayName: String,
    /** Direct download URL (Hugging Face `resolve` link). */
    val url: String,
    val fileName: String,
    val extension: String,
    val approxBytes: Long,
    val browseUrl: String,
    val note: String,
)

/** Default suggestions per engine family (see docs/ARCHITECTURE.md). */
object ModelCatalog {

    val qwen25_1_5b_instruct_q4 = ModelSuggestion(
        id = "qwen2.5-1.5b-instruct-q4_k_m",
        displayName = "Qwen2.5 1.5B Instruct (Q4_K_M)",
        url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        extension = "gguf",
        approxBytes = 1_120_000_000L,
        browseUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        note = "Recommended for macOS and iOS (llama.cpp, Metal). ~1.1 GB.",
    )

    val qwen25_0_5b_instruct_q4 = ModelSuggestion(
        id = "qwen2.5-0.5b-instruct-q4_k_m",
        displayName = "Qwen2.5 0.5B Instruct (Q4_K_M)",
        url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        extension = "gguf",
        approxBytes = 400_000_000L,
        browseUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        note = "Smaller fallback when disk or RAM is tight. ~0.4 GB.",
    )

    val gemma3_1b_it_litertlm = ModelSuggestion(
        id = "gemma3-1b-it-litertlm",
        displayName = "Gemma 3 1B IT (LiteRT-LM)",
        url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.litertlm",
        fileName = "Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.litertlm",
        extension = "litertlm",
        approxBytes = 1_000_000_000L,
        browseUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main",
        note = "Android (LiteRT-LM). Browse the repo if this exact build is renamed.",
    )

    private val all = listOf(
        qwen25_1_5b_instruct_q4,
        qwen25_0_5b_instruct_q4,
        gemma3_1b_it_litertlm,
    )

    /**
     * Suggestions the given engine can actually load, best first. The head of this list is the
     * recommended default: Qwen2.5 **1.5B** on gguf platforms (macOS/iOS) and Gemma 3 1B on
     * Android. The 0.5B build is only a fallback for tight disk/RAM, never the default.
     */
    fun suggestionsFor(supportedExtensions: List<String>): List<ModelSuggestion> {
        if (supportedExtensions.isEmpty()) return all
        val normalized = supportedExtensions.map { it.lowercase().removePrefix(".") }
        return all.filter { it.extension in normalized }
    }

    /** The recommended model for an engine, or null when nothing in the catalog fits. */
    fun defaultFor(supportedExtensions: List<String>): ModelSuggestion? =
        suggestionsFor(supportedExtensions).firstOrNull()

    /** Best-effort file name from an arbitrary download URL. */
    fun fileNameFromUrl(url: String): String? {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val name = withoutQuery.trimEnd('/').substringAfterLast('/')
        return name.takeIf { it.isNotBlank() && it.contains('.') }
    }

    fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()
}
