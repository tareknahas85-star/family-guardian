package com.tarek.guardian.widget

import com.tarek.guardian.R
import com.tarek.guardian.L10n
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.Toast
import com.tarek.guardian.LocaleManager

/**
 * Quick Settings Tile: toggle language (System / English / Arabic).
 */
class LangTileService : TileService() {

    override fun onClick() {
        val tag = when (LocaleManager.current(this)) {
            LocaleManager.EN -> LocaleManager.AR
            LocaleManager.AR -> LocaleManager.SYSTEM
            else -> LocaleManager.EN
        }
        LocaleManager.apply(tag)
        Toast.makeText(this, L10n.s(R.string.lang_toast, tag.ifBlank { L10n.s(R.string.lang_system) }), Toast.LENGTH_SHORT).show()
        updateTile()
    }

    override fun onStartListening() {
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.label = L10n.s(R.string.language)
        tile.contentDescription = when (LocaleManager.current(this)) {
            LocaleManager.EN -> L10n.s(R.string.lang_en)
            LocaleManager.AR -> "العربية"
            else -> L10n.s(R.string.lang_system)
        }
        tile.state = android.service.quicksettings.Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
