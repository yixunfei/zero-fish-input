package dev.zeroinput.copyprobe;

import android.content.Context;
import android.graphics.Bitmap;

interface LineRecognizer {
    char[] recognize(Context context, Bitmap line, boolean invert) throws Exception;
}
