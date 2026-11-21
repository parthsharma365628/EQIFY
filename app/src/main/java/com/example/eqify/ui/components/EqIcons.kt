package com.example.eqify.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.eqify.R
import com.example.eqify.ui.theme.EqifyTheme

/**
 * Material Symbols Rounded icons (Apache 2.0, google/material-design-icons),
 * stored as res/drawable/ic_sym_*.xml so no large icon library is needed.
 * Replaces the former text-symbol "icons" (✕ ▾ ⇩ + ★ ✓ ←) and the hand-drawn AppGlyph.
 */
object EqIcons {
    @DrawableRes val Close = R.drawable.ic_sym_close
    @DrawableRes val Add = R.drawable.ic_sym_add
    @DrawableRes val Check = R.drawable.ic_sym_check
    @DrawableRes val CheckCircle = R.drawable.ic_sym_check_circle
    @DrawableRes val Star = R.drawable.ic_sym_star_filled
    @DrawableRes val StarOutline = R.drawable.ic_sym_star
    @DrawableRes val Back = R.drawable.ic_sym_arrow_back
    @DrawableRes val Share = R.drawable.ic_sym_share
    @DrawableRes val Search = R.drawable.ic_sym_search
    @DrawableRes val Delete = R.drawable.ic_sym_delete
    @DrawableRes val Edit = R.drawable.ic_sym_edit
    @DrawableRes val More = R.drawable.ic_sym_more_vert
    @DrawableRes val Expand = R.drawable.ic_sym_keyboard_arrow_down
    @DrawableRes val Info = R.drawable.ic_sym_info
    @DrawableRes val Warning = R.drawable.ic_sym_warning
    @DrawableRes val Error = R.drawable.ic_sym_error
    @DrawableRes val Settings = R.drawable.ic_sym_settings
    @DrawableRes val Play = R.drawable.ic_sym_play_arrow
    @DrawableRes val Headphones = R.drawable.ic_sym_headphones
    @DrawableRes val Equalizer = R.drawable.ic_sym_equalizer
    @DrawableRes val Tune = R.drawable.ic_sym_tune
    @DrawableRes val GraphicEq = R.drawable.ic_sym_graphic_eq
    @DrawableRes val QrCode = R.drawable.ic_sym_qr_code_2
    @DrawableRes val QrScan = R.drawable.ic_sym_qr_code_scanner
    @DrawableRes val FileOpen = R.drawable.ic_sym_file_open
    @DrawableRes val Bluetooth = R.drawable.ic_sym_bluetooth
    @DrawableRes val Copy = R.drawable.ic_sym_content_copy
    @DrawableRes val Paste = R.drawable.ic_sym_content_paste
    @DrawableRes val Refresh = R.drawable.ic_sym_refresh
    @DrawableRes val Palette = R.drawable.ic_sym_palette
    @DrawableRes val Music = R.drawable.ic_sym_music_note
    @DrawableRes val Home = R.drawable.ic_sym_home
    @DrawableRes val Save = R.drawable.ic_sym_save
    @DrawableRes val Reset = R.drawable.ic_sym_restart_alt
    @DrawableRes val Download = R.drawable.ic_sym_download
}

/**
 * A themed icon. Pass a [contentDescription] when the icon carries meaning on
 * its own; use null when adjacent text already says the same thing.
 */
@Composable
fun EqIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = EqifyTheme.colors.textMuted,
    size: Dp = 24.dp
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint
    )
}
