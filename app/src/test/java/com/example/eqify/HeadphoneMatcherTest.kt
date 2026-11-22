package com.example.eqify

import com.example.eqify.HeadphoneMatcher.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadphoneMatcherTest {
    private val catalog = listOf(
        "Sony WH-1000XM5", "Sony WH-1000XM4", "Sony WF-1000XM5",
        "Apple AirPods Pro", "Apple AirPods Pro 2", "Apple AirPods",
        "Samsung Galaxy Buds2 Pro", "Samsung Galaxy Buds Pro",
        "Sennheiser HD 600", "Sennheiser HD 650", "Bose QuietComfort 45"
    )

    @Test fun exactNameIsConfident() =
        assertEquals(Result.Confident("Sony WH-1000XM5"), HeadphoneMatcher.match("Sony WH-1000XM5", catalog))

    @Test fun punctuationAndCaseAreIgnored() =
        assertEquals(Result.Confident("Sony WH-1000XM5"), HeadphoneMatcher.match("sony wh1000xm5", catalog))

    @Test fun brandOmittedIsConfidentWhenUnique() =
        assertEquals(Result.Confident("Sony WH-1000XM5"), HeadphoneMatcher.match("WH-1000XM5", catalog))

    @Test fun ownerAndLePrefixesAreStripped() {
        assertEquals(Result.Confident("Apple AirPods Pro"), HeadphoneMatcher.match("Parth's AirPods Pro", catalog))
        assertEquals(Result.Confident("Sony WH-1000XM5"), HeadphoneMatcher.match("LE_WH-1000XM5", catalog))
    }

    @Test fun extraWordsOnTheDeviceStillMatch() =
        assertEquals(Result.Confident("Samsung Galaxy Buds2 Pro"),
            HeadphoneMatcher.match("Samsung Galaxy Buds2 Pro (5A2C)", catalog))

    @Test fun ambiguousMatchesOnlySuggest() {
        // "HD 6" is a prefix of both HD 600 and HD 650: never auto-select.
        val r = HeadphoneMatcher.match("Sennheiser HD6", catalog)
        assert(r is Result.Likely) { "expected Likely, got $r" }
    }

    @Test fun shortGenericNamesNeverMatch() {
        assertEquals(Result.None, HeadphoneMatcher.match("Buds", catalog))
        assertEquals(Result.None, HeadphoneMatcher.match("Pro", catalog))
        assertEquals(Result.None, HeadphoneMatcher.match("", catalog))
    }

    @Test fun unrelatedDevicesDoNotMatch() {
        assertEquals(Result.None, HeadphoneMatcher.match("Galaxy Watch6 (AB12)", catalog))
        assertEquals(Result.None, HeadphoneMatcher.match("Toyota Corolla", catalog))
    }
}
