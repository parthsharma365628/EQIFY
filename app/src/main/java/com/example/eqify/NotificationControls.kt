package com.example.eqify

/** The notification's bass button (FEATURES_BATCH2_PLAN.md §2). Pure; unit-tested. */
object NotificationControls {
    /** Off → +3 → +6 → +9 → Off, the same steps as Home's bass control. */
    fun nextBass(currentDb: Float): Float {
        val steps = DeviceProfiles.BASS_STEPS
        val index = steps.indexOf(DeviceProfiles.normaliseBass(currentDb))
        return steps[(index + 1) % steps.size]
    }

    fun bassLabel(currentDb: Float): String {
        val level = DeviceProfiles.normaliseBass(currentDb)
        return if (level <= 0f) "Bass: Off" else "Bass: +${level.toInt()} dB"
    }
}
