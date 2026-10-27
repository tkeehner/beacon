package com.tyler.primelauncher.phonelink.android

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: tap to connect / disconnect Phone Link (soft kill switch).
 * Add via Edit tiles after the launcher is installed. Label: "Prime link".
 */
class LinkTileService : TileService() {
    override fun onStartListening() { refresh() }
    override fun onClick() {
        val store = LinkStore(this)
        if (!store.configured) { qsTile?.apply { state = Tile.STATE_UNAVAILABLE; subtitle = "Not paired"; updateTile() }; return }
        if (store.enabled && LinkService.running) {
            LinkService.stop(this)
        } else {
            store.enabled = true; LinkService.start(this)
        }
        // state updates as the service publishes notifications; refresh shortly
        qsTile?.updateTile(); refresh()
    }
    private fun refresh() {
        val store = LinkStore(this); val t = qsTile ?: return
        t.label = "Prime link"
        t.icon = Icon.createWithResource(this, com.tyler.primelauncher.R.drawable.ic_stat)
        when {
            !store.configured -> { t.state = Tile.STATE_UNAVAILABLE; t.subtitle = "Not paired" }
            store.enabled && LinkService.state == "connected" -> { t.state = Tile.STATE_ACTIVE; t.subtitle = "Connected" }
            store.enabled -> { t.state = Tile.STATE_ACTIVE; t.subtitle = LinkService.state }
            else -> { t.state = Tile.STATE_INACTIVE; t.subtitle = "Off" }
        }
        t.updateTile()
    }
}
