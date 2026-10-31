package dev.zeroinput.copyprobe;

/** Bounded, lossless plain-text acceptance; no trimming or Unicode normalization. */
final class TextGate {
    static final int MAX_UNITS = 8192;
    private TextGate() { }

    static char[] copy(CharSequence source) {
        if (source == null || source.length() == 0 || source.length() > MAX_UNITS) return null;
        boolean visible = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value == 0 || Character.isLowSurrogate(value)) return null;
            if (Character.isHighSurrogate(value)) {
                if (++i == source.length() || !Character.isLowSurrogate(source.charAt(i))) return null;
                visible = true;
            } else if (!Character.isWhitespace(value) && !Character.isSpaceChar(value)) visible = true;
        }
        if (!visible) return null;
        char[] result = new char[source.length()];
        for (int i = 0; i < result.length; i++) result[i] = source.charAt(i);
        return result;
    }
}
