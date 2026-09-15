package com.example.eqify

/** Internal state for the Android 9+ DynamicsProcessing limiter prototype. */
enum class LimiterDiagnosticState {
    UNSUPPORTED,
    AVAILABLE,
    ATTACHED,
    ENABLED,
    FAILED
}

data class LimiterDiagnosticStatus(
    val state: LimiterDiagnosticState,
    val available: Boolean,
    val attached: Boolean,
    val enabled: Boolean,
    val sessionId: Int? = null,
    val detail: String
)
