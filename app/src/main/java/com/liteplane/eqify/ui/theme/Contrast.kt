package com.liteplane.eqify.ui.theme

import kotlin.math.pow

/** WCAG 2.x contrast math on opaque ARGB ints. Pure Kotlin for JVM tests. */
object Contrast {
    /** Relative luminance (0..1) of an opaque sRGB colour. */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** Contrast ratio from 1:1 to 21:1, symmetric in its arguments. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    const val TEXT_AA = 4.5
    const val LARGE_OR_UI_AA = 3.0
}
