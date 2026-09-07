package com.dd3boh.outertune.models.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalNameAssessmentTest {
    private val videoId = "Abc_12-xyZ0"
    private val songTarget = OriginalNameTarget(OriginalNameKind.SONG, videoId)
    private val assessment = OriginalNameAssessment(
        target = songTarget,
        originalName = "Original Title",
        sourceVideoId = videoId,
        sourceUrl = "https://www.youtube.com/watch?v=$videoId",
        language = OriginalNameLanguage.ENGLISH,
        confidence = 0.91f,
        method = "mlkit:standalone-v1",
        inputFingerprint = "sha256:fixture-input",
        evaluatedAt = 1_700_000_000_000L,
    )

    private fun decode(value: String?) = OriginalNameAssessmentCodec.decode(value, songTarget, assessment.originalName)
    private fun document(value: OriginalNameAssessment = assessment) =
        Json.parseToJsonElement(OriginalNameAssessmentCodec.encode(value)).jsonObject

    private fun changed(key: String, value: JsonElement): String = JsonObject(document() + (key to value)).toString()

    @Test
    fun `round trip preserves name text inputs language confidence and estimation method`() {
        for (method in listOf("mlkit:standalone-v1", "mlkit:context-v1")) {
            val original = assessment.copy(originalName = "Café \"Original\" / 夜\nMix\\", method = method)
            assertEquals(original, OriginalNameAssessmentCodec.decode(
                OriginalNameAssessmentCodec.encode(original), original.target, original.originalName,
            ))
        }
    }

    @Test
    fun `artist and album assessments may cite a different Art Track video identity`() {
        for (target in listOf(songTarget,
            OriginalNameTarget(OriginalNameKind.ARTIST, "UCartist"),
            OriginalNameTarget(OriginalNameKind.ALBUM, "MPREalbum"),
        )) {
            val original = assessment.copy(target = target)
            assertEquals(original, OriginalNameAssessmentCodec.decode(
                OriginalNameAssessmentCodec.encode(original), target, original.originalName,
            ))
        }
        val wrongSong = assessment.copy(target = songTarget.copy(id = "Xbc_12-xyZ0"))
        assertEncodingRejected(wrongSong)
        val wrongTarget = JsonObject(mapOf("kind" to JsonPrimitive("SONG"), "id" to JsonPrimitive("Xbc_12-xyZ0")))
        assertNull(OriginalNameAssessmentCodec.decode(changed("target", wrongTarget), wrongSong.target, wrongSong.originalName))
    }

    @Test
    fun `stored assessment cannot cross expected entity ids kinds or names`() {
        val json = OriginalNameAssessmentCodec.encode(assessment)
        for (target in listOf(songTarget.copy(id = "other"),
            OriginalNameTarget(OriginalNameKind.ARTIST, videoId),
            OriginalNameTarget(OriginalNameKind.ALBUM, videoId),
        )) {
            assertNull(OriginalNameAssessmentCodec.decode(json, target, assessment.originalName))
        }
        for (name in listOf("ORIGINAL TITLE", "Original Title (Live)", "Original-Title", "")) {
            assertNull(OriginalNameAssessmentCodec.decode(json, songTarget, name))
        }
    }

    @Test
    fun `name binding accepts only trimming and equivalent Unicode without changing stored spelling`() {
        val original = assessment.copy(originalName = "Cafe\u0301 Original")
        val decoded = OriginalNameAssessmentCodec.decode(OriginalNameAssessmentCodec.encode(original), songTarget, "  Café Original  ")
        assertEquals(original, decoded)
        assertEquals("Cafe\u0301 Original", decoded!!.originalName)
        assertNull(OriginalNameAssessmentCodec.decode(OriginalNameAssessmentCodec.encode(original), songTarget, "CAFE\u0301 ORIGINAL"))
    }

    @Test
    fun `legacy absent and unsupported format or resolver versions remain unconfirmed`() {
        assertNull(decode(null))
        assertNull(decode(""))
        assertNull(decode("not-json"))
        assertNull(decode("[]"))
        assertNull(decode("{\"source\":\"YOUTUBE_MAIN\",\"language\":\"ENGLISH\",\"verification\":\"CONFIRMED\"}"))
        for (key in listOf("formatVersion", "resolverVersion")) {
            assertNull(decode(JsonObject(document() - key).toString()))
            for (value in listOf(JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive("1"), JsonPrimitive(1.0), JsonNull)) {
                assertNull("$key=$value", decode(changed(key, value)))
            }
        }
        assertEncodingRejected(assessment.copy(formatVersion = 2))
        assertEncodingRejected(assessment.copy(resolverVersion = 2))
    }

    @Test
    fun `malformed or empty required fields are rejected rather than defaulted`() {
        for (key in listOf("originalName", "sourceVideoId", "sourceUrl", "language", "method", "inputFingerprint")) {
            assertNull("Missing $key", decode(JsonObject(document() - key).toString()))
            for (value in listOf(JsonPrimitive(" "), JsonPrimitive(1), JsonPrimitive(true), JsonNull, JsonArray(emptyList()))) {
                assertNull("$key=$value", decode(changed(key, value)))
            }
        }
        assertNull(decode(changed("language", JsonPrimitive("LATIN"))))
        assertNull(decode(changed("target", JsonPrimitive(videoId))))
        assertNull(decode(changed("target", JsonObject(mapOf("kind" to JsonPrimitive("VIDEO"), "id" to JsonPrimitive(videoId))))))
        assertNull(decode(changed("target", JsonObject(mapOf("kind" to JsonPrimitive("SONG"), "id" to JsonPrimitive(123))))))
        assertEncodingRejected(assessment.copy(method = " "))
        assertEncodingRejected(assessment.copy(inputFingerprint = " "))
        assertEncodingRejected(assessment.copy(originalName = " "))
    }

    @Test
    fun `confidence must be a finite number in the closed unit interval before Float conversion`() {
        for (confidence in listOf(0f, 1f)) {
            val original = assessment.copy(confidence = confidence)
            assertEquals(original, decode(OriginalNameAssessmentCodec.encode(original)))
        }
        for (confidence in listOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEncodingRejected(assessment.copy(confidence = confidence))
        }
        for (value in listOf(JsonPrimitive(-0.1), JsonPrimitive(1.00000001), JsonPrimitive(-1e-50),
            JsonPrimitive("0.9"), JsonPrimitive(true), JsonNull, JsonPrimitive(Double.NaN), JsonPrimitive(Double.POSITIVE_INFINITY),
        )) {
            assertNull("confidence=$value", decode(changed("confidence", value)))
        }
    }

    @Test
    fun `source video ids require exactly eleven ASCII URL safe characters`() {
        for (invalid in listOf("short", "Abc_12-xyZ00", "Abc_12-xyZé", "Ａbc_12-xyZ0", "Abc_12 xyZ0", "Abc_12/xyZ0")) {
            assertEncodingRejected(assessment.copy(sourceVideoId = invalid, sourceUrl = "https://www.youtube.com/watch?v=$invalid"))
            assertNull(decode(changed("sourceVideoId", JsonPrimitive(invalid))))
        }
    }

    @Test
    fun `source URL must be the exact canonical Main URL for the cited video`() {
        for (invalid in listOf(
            "https://www.youtube.com/watch?v=Xbc_12-xyZ0",
            "http://www.youtube.com/watch?v=$videoId",
            "https://www.youtube.com.evil.example/watch?v=$videoId",
            "https://www.youtube.com@evil.example/watch?v=$videoId",
            "https://music.youtube.com/watch?v=$videoId",
            "https://youtu.be/$videoId",
            "https://www.youtube.com/watch?v=$videoId&v=Xbc_12-xyZ0",
            "https://www.youtube.com/watch?v=$videoId#other",
        )) {
            assertEncodingRejected(assessment.copy(sourceUrl = invalid))
            assertNull(invalid, decode(changed("sourceUrl", JsonPrimitive(invalid))))
        }
    }

    @Test
    fun `evaluation time must be a positive integral Long`() {
        for (time in listOf(0L, -1L)) assertEncodingRejected(assessment.copy(evaluatedAt = time))
        for (value in listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive("1700000000000"), JsonNull)) {
            assertNull("time=$value", decode(changed("evaluatedAt", value)))
        }
        val original = assessment.copy(evaluatedAt = Long.MAX_VALUE)
        assertEquals(original, decode(OriginalNameAssessmentCodec.encode(original)))
    }

    @Test
    fun `known non English and unknown outcomes remain automatic assessment data`() {
        for (language in listOf(OriginalNameLanguage.OTHER, OriginalNameLanguage.UNKNOWN)) {
            val original = assessment.copy(language = language)
            assertEquals(original, decode(OriginalNameAssessmentCodec.encode(original)))
        }
    }

    private fun assertEncodingRejected(value: OriginalNameAssessment) {
        val failure = runCatching { OriginalNameAssessmentCodec.encode(value) }.exceptionOrNull()
        assertTrue("Invalid assessment must not be saved: $value", failure is IllegalArgumentException)
    }
}
