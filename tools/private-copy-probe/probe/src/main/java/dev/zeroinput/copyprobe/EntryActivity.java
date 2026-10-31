package dev.zeroinput.copyprobe;

import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.widget.LinearLayout;

public final class EntryActivity extends ProtectedActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { app().clear(); setIntent(new Intent()); }
        else if (Intent.ACTION_SEND.equals(getIntent().getAction()) || Intent.ACTION_PROCESS_TEXT.equals(getIntent().getAction())) {
            app().clear();
            char[] value = IncomingText.read(getIntent());
            String route = Intent.ACTION_SEND.equals(getIntent().getAction()) ? "share" : "process-text";
            setIntent(new Intent());
            if (value != null) { app().acceptText(value, route); review(); return; }
        }
        LinearLayout root = column();
        label(root, R.string.app_name);
        label(root, R.string.research_notice);
        button(root, R.string.add_tile, this::addTile);
        button(root, R.string.start_capture, () -> { startActivity(new Intent(this, ConsentActivity.class)); finish(); });
        button(root, R.string.open_draft, this::review);
        button(root, R.string.drop_title, () -> startActivity(new Intent(this, DropActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        button(root, R.string.ime_settings, () -> startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)));
        button(root, R.string.choose_ime, () -> getSystemService(android.view.inputmethod.InputMethodManager.class).showInputMethodPicker());
        button(root, R.string.cancel, () -> { app().clear(); finish(); });
    }
    private void review() {
        startActivity(new Intent(this, ReviewActivity.class)); finish();
    }
    private void addTile() {
        getSystemService(StatusBarManager.class).requestAddTileService(
            new ComponentName(this, CaptureTile.class), getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_probe), getMainExecutor(), result -> { });
    }
}
