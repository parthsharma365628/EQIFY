package com.example.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHeadphoneMemoryTest {

    @Test fun keysAreStableAndNormalised() {
        assertEquals("bluetooth:wh1000xm5", DeviceHeadphoneMemory.key(AudioOutputKind.BLUETOOTH, "WH-1000XM5"))
        assertEquals("usb:usbcearphones", DeviceHeadphoneMemory.key(AudioOutputKind.USB, "USB-C Earphones"))
        assertEquals("wired", DeviceHeadphoneMemory.key(AudioOutputKind.WIRED, "Headset"))
        assertEquals("bluetooth", DeviceHeadphoneMemory.key(AudioOutputKind.BLUETOOTH, ""))
    }

    @Test fun roundTripsEveryChoiceKind() {
        val map = mapOf(
            "bluetooth:wh1000xm5" to DeviceChoice.Dataset("Sony WH-1000XM5"),
            "usb:dac|odd=name" to DeviceChoice.Custom("3f2a-uuid"),
            "bluetooth:toyota" to DeviceChoice.NotHeadphones,
            "wired" to DeviceChoice.Dataset("Sennheiser HD 600")
        )
        assertEquals(map, DeviceHeadphoneMemory.decode(DeviceHeadphoneMemory.encode(map)))
    }

    @Test fun malformedInputIsIgnored() {
        assertTrue(DeviceHeadphoneMemory.decode(null).isEmpty())
        assertTrue(DeviceHeadphoneMemory.decode("garbage").isEmpty())
        assertTrue(DeviceHeadphoneMemory.decode("v1:").isEmpty())
        assertEquals(mapOf("wired" to DeviceChoice.NotHeadphones),
            DeviceHeadphoneMemory.decode("v1:wired=none|broken|x=dataset:|=none"))
    }
}
