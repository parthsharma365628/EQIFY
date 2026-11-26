package com.liteplane.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

class EqProfileCodecTest {

    private val tone = listOf(3f, 2f, 0f, -1f, 0f, 1f, 2f, 1.5f)
    private val correctionGains = listOf(-1.2f, 0.4f, 0f, 0.3f, 0f, -2.1f, 1f, 3.2f)
    private val full = SharedEqProfile(
        name = "Night Bass",
        tone = tone,
        correction = SharedCorrection(SharedCorrection.Kind.DATASET, "Sennheiser HD 600", correctionGains),
        bass = 3f
    )

    private fun reason(input: String) =
        assertThrows(ProfileShareException::class.java) { EqProfileCodec.decode(input) }.reason

    private fun codeFor(json: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(json.toByteArray(Charsets.UTF_8)); deflater.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(512)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return EqProfileCodec.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun json(tone: String = "[0,0,0,0,0,0,0,0]", extra: String = "", v: String = "1",
                     freqs: String = "[60,170,310,600,1000,3000,6000,12000]") =
        """{"app":"eqify","v":$v,"name":"X","freqs":$freqs,"tone":$tone$extra}"""

    // ── Round trips ──────────────────────────────────────────────────

    @Test fun roundTripFullProfile() {
        val code = EqProfileCodec.encode(full)
        assertTrue(code.startsWith("EQIFY1:"))
        assertEquals(full, EqProfileCodec.decode(code))
    }

    @Test fun roundTripToneOnly() {
        val p = SharedEqProfile("Flat-ish", listOf(0f, 0f, 0.5f, 0f, 0f, 0f, -0.5f, 0f))
        val decoded = EqProfileCodec.decode(EqProfileCodec.encode(p))
        assertEquals(p, decoded)
        assertNull(decoded.correction)
        assertNull(decoded.bass)
    }

    @Test fun roundTripCustomCorrection() {
        val p = full.copy(correction = SharedCorrection(SharedCorrection.Kind.CUSTOM, "My IEM", correctionGains), bass = null)
        assertEquals(p, EqProfileCodec.decode(EqProfileCodec.encode(p)))
    }

    @Test fun gainsAreRoundedToTenthDb() {
        val p = SharedEqProfile("R", listOf(1.04f, 1.06f, -1.04f, -1.06f, 0f, 0f, 0f, 11.99f))
        assertEquals(listOf(1.0f, 1.1f, -1.0f, -1.1f, 0f, 0f, 0f, 12f), EqProfileCodec.decode(EqProfileCodec.encode(p)).tone)
    }

    @Test fun codeIsShortEnoughToPaste() {
        val p = full.copy(name = "A fairly long profile name here!!")
        assertTrue(EqProfileCodec.encode(p).length < 400)
    }

    @Test fun plainJsonFileRoundTrips() {
        assertEquals(full, EqProfileCodec.decode(EqProfileCodec.encodeJson(full)))
    }

    @Test fun codeInsideSurroundingTextIsFound() {
        val text = "Try my EQ \"Night Bass\":\n${EqProfileCodec.encode(full)}\nenjoy"
        assertEquals(full, EqProfileCodec.decode(text))
    }

    @Test fun lastCodeWinsWhenNameContainsPrefix() {
        val decoded = EqProfileCodec.decode("EQIFY1:notreal then ${EqProfileCodec.encode(full)}")
        assertEquals(full, decoded)
    }

    @Test fun importLinkRoundTrips() {
        val link = EqProfileCodec.importLink(EqProfileCodec.encode(full))
        assertTrue(link.startsWith("eqify://import?p=EQIFY1:"))
        assertEquals(full, EqProfileCodec.decode(link))
    }

    @Test fun percentEncodedLinkIsAccepted() {
        val code = EqProfileCodec.encode(full)
        val encoded = "eqify://import?p=" + code.replace("EQIFY1:", "EQIFY1%3a")
        assertEquals(full, EqProfileCodec.decode(encoded))
    }

    @Test fun importLinkRequiresACode() {
        assertThrows(IllegalArgumentException::class.java) { EqProfileCodec.importLink("hello") }
    }

    @Test fun unknownFieldsAreIgnored() {
        val decoded = EqProfileCodec.decode(json(extra = ""","future":{"a":1},"genre":"Rock""""))
        assertEquals(List(8) { 0f }, decoded.tone)
    }

    @Test fun namesAreSanitized() {
        val decoded = EqProfileCodec.decode(json().replace("\"X\"", "\"  Late\\u0000  \\n Night \""))
        assertEquals("Late Night", decoded.name)
        val blank = EqProfileCodec.decode(json().replace("\"X\"", "\"   \""))
        assertEquals(EqProfileCodec.FALLBACK_NAME, blank.name)
        val long = EqProfileCodec.decode(json().replace("\"X\"", "\"${"a".repeat(80)}\""))
        assertEquals(PresetNameRules.MAX_LENGTH, long.name.length)
    }

    // ── Rejections ───────────────────────────────────────────────────

    @Test fun rejectsTextWithoutCode() =
        assertEquals(ProfileShareException.Reason.NOT_EQIFY, reason("hello world"))

    @Test fun rejectsWrongApp() =
        assertEquals(ProfileShareException.Reason.NOT_EQIFY, reason(json().replace("eqify", "other")))

    @Test fun rejectsBadBase64() =
        assertEquals(ProfileShareException.Reason.CORRUPTED, reason("EQIFY1:a"))

    @Test fun rejectsEmptyPayload() =
        assertEquals(ProfileShareException.Reason.CORRUPTED, reason("EQIFY1: "))

    @Test fun rejectsTruncatedCode() {
        val code = EqProfileCodec.encode(full)
        assertEquals(ProfileShareException.Reason.CORRUPTED, reason(code.dropLast(code.length / 3)))
    }

    @Test fun rejectsNonJsonPayload() =
        assertEquals(ProfileShareException.Reason.CORRUPTED, reason(codeFor("not json at all {")))

    @Test fun rejectsNewerVersion() =
        assertEquals(ProfileShareException.Reason.NEWER_VERSION, reason(json(v = "2")))

    @Test fun rejectsMissingOrStringVersion() {
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(v = "\"1\"")))
        assertEquals(ProfileShareException.Reason.INVALID_DATA,
            reason(json().replace("\"v\":1,", "")))
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(v = "1.5")))
    }

    @Test fun rejectsWrongFrequencies() =
        assertEquals(ProfileShareException.Reason.INVALID_DATA,
            reason(json(freqs = "[60,170,310,600,1000,3000,6000,16000]")))

    @Test fun rejectsWrongBandCount() {
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(tone = "[0,0,0,0,0,0,0]")))
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(tone = "[0,0,0,0,0,0,0,0,0]")))
    }

    @Test fun rejectsOutOfRangeAndNonNumericGains() {
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(tone = "[0,0,0,0,0,0,0,12.5]")))
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(tone = "[-13,0,0,0,0,0,0,0]")))
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(tone = "[\"1\",0,0,0,0,0,0,0]")))
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(tone = "[NaN,0,0,0,0,0,0,0]")))
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(tone = "[Infinity,0,0,0,0,0,0,0]")))
    }

    @Test fun rejectsUnknownCorrectionKind() =
        assertEquals(ProfileShareException.Reason.INVALID_DATA,
            reason(json(extra = ""","correction":{"kind":"cloud","name":"A","gains":[0,0,0,0,0,0,0,0]}""")))

    @Test fun rejectsBadCorrectionGains() =
        assertEquals(ProfileShareException.Reason.INVALID_DATA,
            reason(json(extra = ""","correction":{"kind":"custom","name":"A","gains":[0,0]}""")))

    @Test fun rejectsBassOutOfRange() {
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(extra = ""","bass":-1""")))
        assertEquals(ProfileShareException.Reason.INVALID_DATA, reason(json(extra = ""","bass":13""")))
    }

    @Test fun rejectsOversizedInput() =
        assertEquals(ProfileShareException.Reason.TOO_LARGE, reason("x".repeat(EqProfileCodec.MAX_INPUT_CHARS + 1)))

    @Test fun rejectsDecompressionBomb() {
        val bomb = json(extra = ""","pad":"${" ".repeat(EqProfileCodec.MAX_INPUT_CHARS * 4)}"""")
        val code = codeFor(bomb)
        assertTrue(code.length < EqProfileCodec.MAX_INPUT_CHARS)
        assertEquals(ProfileShareException.Reason.TOO_LARGE, reason(code))
    }

    @Test fun encodeRefusesInvalidProfiles() {
        assertThrows(IllegalArgumentException::class.java) {
            EqProfileCodec.encode(SharedEqProfile("x", List(7) { 0f }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EqProfileCodec.encode(SharedEqProfile("x", List(8) { 20f }))
        }
    }
}
