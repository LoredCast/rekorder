// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Android Quick Settings Tile for one-tap start/stop recording directly from the shade.
 */
public final class RekorderTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        syncTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (RecordingService.isRecordingGlobally()) {
            Intent stop = new Intent(this, RecordingService.class);
            stop.setAction(RecordingService.ACTION_STOP);
            startService(stop);
            syncTile(false);
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(
                        this, 50, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(open);
            }
            return;
        }

        Intent start = new Intent(this, RecordingService.class);
        start.setAction(RecordingService.ACTION_START_DEFAULT);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(start);
        } else {
            startService(start);
        }
        syncTile(true);
    }

    private void syncTile() {
        syncTile(RecordingService.isRecordingGlobally());
    }

    private void syncTile(boolean active) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(active ? "Stop Rec" : "Rekorder");
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(active ? "Recording..." : "Tap to Record");
        }
        tile.updateTile();
    }
}
