package dev.zeroinput.copyprobe;

import android.content.Intent;
import android.os.Bundle;
import android.view.DragEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Empty split-screen target. Its public landing task is removed before showing a draft. */
public final class DropActivity extends ProtectedActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column();
        label(root, R.string.drop_setup);
        TextView drop = label(root, R.string.drag_hint);
        drop.setBackgroundColor(0xffdbece8);
        drop.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        drop.setOnDragListener((view, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED)
                return event.getClipDescription() != null && event.getClipDescription().hasMimeType("text/plain");
            if (event.getAction() == DragEvent.ACTION_DROP) {
                char[] value = IncomingText.readClip(event.getClipData());
                if (value == null) { drop.setText(R.string.invalid_payload); return false; }
                app().acceptText(value, "drag");
                startActivity(new Intent(this, ReviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
                finishAndRemoveTask();
            }
            return true;
        });
        button(root, R.string.close, this::finishAndRemoveTask);
    }
}
