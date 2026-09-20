package com.dropshare.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * DropShare Quick Settings tile.
 * The user adds this tile to the Quick Settings panel once; Android controls
 * where it appears. Tapping it opens DropShare without exposing file data.
 */
class DropShareTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun updateTile() {
        qsTile?.let { tile ->
            tile.label = getString(R.string.quick_settings_tile_label)
            tile.contentDescription = getString(R.string.quick_settings_tile_description)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_dropshare_logo)
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }
}
