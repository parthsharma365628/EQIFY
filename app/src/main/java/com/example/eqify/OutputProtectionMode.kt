package com.example.eqify

enum class OutputProtectionMode {
    BALANCED,
    SAFE,
    OFF;

    companion object {
        fun fromStorageValue(value: String?): OutputProtectionMode? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
