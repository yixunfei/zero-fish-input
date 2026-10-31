package dev.zeroinput.copyprobe;

import android.content.ClipData;
import android.content.Intent;
import java.util.Arrays;

final class IncomingText {
    private IncomingText() { }
    static char[] read(Intent intent) {
        char[] text = null;
        try {
            if (intent == null || !"text/plain".equals(intent.getType())) return null;
            if (intent.getData() != null || intent.hasExtra(Intent.EXTRA_STREAM) || intent.hasExtra(Intent.EXTRA_HTML_TEXT)) return null;
            String key;
            if (Intent.ACTION_SEND.equals(intent.getAction())) key = Intent.EXTRA_TEXT;
            else if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) key = Intent.EXTRA_PROCESS_TEXT;
            else return null;
            text = TextGate.copy(intent.getCharSequenceExtra(key));
            if (text == null) return null;
            ClipData clip = intent.getClipData();
            if (clip != null) {
                char[] attached = readClip(clip);
                boolean equal = Arrays.equals(text, attached);
                if (attached != null) Arrays.fill(attached, '\0');
                if (!equal) { Arrays.fill(text, '\0'); return null; }
            }
            return text;
        } catch (RuntimeException invalidParcel) { if (text != null) Arrays.fill(text, '\0'); return null; }
    }

    // Drag ClipData is an explicit handoff and does not access the system clipboard.
    static char[] readClip(ClipData clip) {
        if (clip == null || clip.getItemCount() != 1 || clip.getDescription().getMimeTypeCount() != 1 ||
                !"text/plain".equals(clip.getDescription().getMimeType(0))) return null;
        ClipData.Item item = clip.getItemAt(0);
        if (item.getUri() != null || item.getIntent() != null || item.getHtmlText() != null) return null;
        return TextGate.copy(item.getText());
    }
}
