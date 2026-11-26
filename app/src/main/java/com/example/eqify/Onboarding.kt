package com.example.eqify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the first-run tour is showing (FEATURES_BATCH3_PLAN.md §F). One flag, in MainActivity's prefs. */
object Onboarding {
    private val _visible = MutableStateFlow(false)
    val visible = _visible.asStateFlow()
    fun show() { _visible.value = true }
    fun hide() { _visible.value = false }
}
