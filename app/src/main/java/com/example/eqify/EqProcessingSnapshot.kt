package com.example.eqify

enum class EqProcessingStatus {
    STARTING,
    ACTIVE,
    PAUSED,
    BYPASSED,
    UNAVAILABLE;

    companion object {
        fun fromStorageValue(value: String?): EqProcessingStatus? =
            entries.firstOrNull { it.name == value }
    }
}

/** Small persisted status shared by external controls such as the Quick Settings tile. */
data class EqProcessingSnapshot(
    val requestedEnabled: Boolean,
    val status: EqProcessingStatus,
    val activeProfile: String
)
