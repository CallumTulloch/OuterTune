package com.dd3boh.outertune.models.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class DecodedOriginalEvidence(
    val original: ArtTrackOriginalName? = null,
    val assessment: OriginalNameAssessment? = null,
    val inputVersion: Int? = null,
    val inputModel: String? = null,
    val inputFingerprint: String? = null,
)

/** Cache immutable decoded values, not JSON trees. Identity and the exact payload are the key;
 * neither an old verdict nor another row's proof can be reused after an update or withdrawal.
 * Both entry count and retained text are bounded, including unsuccessful decodes.
 */
internal class OriginalEvidenceCache(
    private val maxEntries: Int = 16_384,
    private val maxCharacters: Int = 8_000_000,
) {
    private data class Key(val payload: String, val target: OriginalNameTarget, val name: String) {
        val characters: Int get() = payload.length + target.id.length + name.length
    }
    private data class Entry(val key: Key, val decoded: DecodedOriginalEvidence)
    private val values = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var characters = 0

    fun decode(value: String?, target: OriginalNameTarget, name: String): DecodedOriginalEvidence =
        entry(value, target, name)?.decoded ?: DecodedOriginalEvidence()

    /** Room creates a new proof String for every read. Share the existing exact text before a
     * snapshot is used by the evaluator and its transaction checks. Rebinding the cache to each
     * reader makes concurrently retained snapshots repeatedly compare the whole proof again.
     * This uses the same bounded entries; it neither interns globally nor retains a second copy.
     */
    fun canonicalize(value: String?, target: OriginalNameTarget, name: String): String? =
        entry(value, target, name)?.key?.payload ?: value

    private fun entry(value: String?, target: OriginalNameTarget, name: String): Entry? {
        if (value.isNullOrBlank()) return null
        val key = Key(value, target, name)
        synchronized(values) { values[key]?.let { return it } }
        // Parsing is outside the monitor so fetch, evaluation and Room readers do not serialize.
        val root = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
        val input = root?.get("assessmentInputSet") as? JsonObject
        fun text(field: String) = (input?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val decoded = DecodedOriginalEvidence(
            original = ArtTrackOriginalNameCodec.decodeRoot(root, target, name),
            assessment = OriginalNameAssessmentCodec.decodeRoot(root, target, name),
            inputVersion = (input?.get("version") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull,
            inputModel = text("model"),
            inputFingerprint = text("fingerprint"),
        )
        val entry = Entry(key, decoded)
        if (key.characters > maxCharacters || maxEntries <= 0) return entry
        synchronized(values) {
            values[key]?.let { return it }
            values[key] = entry
            characters += key.characters
            val entries = values.entries.iterator()
            while (values.size > maxEntries || characters > maxCharacters) {
                characters -= entries.next().key.characters
                entries.remove()
            }
        }
        return entry
    }
}

internal val originalEvidenceCache = OriginalEvidenceCache()
