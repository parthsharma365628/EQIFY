package com.example.eqify.ui.components

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the user turned animations off (Settings → Accessibility →
 * Remove animations, or Developer options → Animator duration scale = off).
 * Read once per composition entry; the system setting rarely changes mid-session.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Standard "something changed" motion (UI_REDESIGN.md §4.5), or an instant snap when motion is reduced. */
fun <T> changeMotion(reduceMotion: Boolean, durationMillis: Int = 450): AnimationSpec<T> =
    if (reduceMotion) snap() else tween(durationMillis, easing = FastOutSlowInEasing)
