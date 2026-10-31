package dev.zeroinput.copyprobe;

import android.app.PendingIntent;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class CaptureTile extends TileService {
    @Override public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        ProbeApp app = (ProbeApp) getApplication();
        boolean ready = app.gate.state(ProbeApp.now()) == SessionGate.State.READY;
        tile.setLabel(getString(ready ? R.string.open_draft : R.string.tile_label));
        tile.setState(Tile.STATE_INACTIVE); tile.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        if (isLocked()) return;
        ProbeApp app = (ProbeApp) getApplication();
        Class<?> destination = app.gate.state(ProbeApp.now()) == SessionGate.State.READY ? ReviewActivity.class : ConsentActivity.class;
        Intent intent = new Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
}
