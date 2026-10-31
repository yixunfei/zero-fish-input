package dev.zeroinput.copyprobe;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Code-point Levenshtein metrics. The report contains positions and classes, never characters. */
final class OcrDifference {
    private OcrDifference() { }
    static JSONObject compare(String reference, char[] output) throws Exception {
        int[] expected = reference.codePoints().toArray();
        int[] actual = points(output);
        int[][] distance = new int[expected.length + 1][actual.length + 1];
        try {
            for (int i = 0; i <= expected.length; i++) distance[i][0] = i;
            for (int j = 0; j <= actual.length; j++) distance[0][j] = j;
            for (int i = 1; i <= expected.length; i++) for (int j = 1; j <= actual.length; j++)
                distance[i][j] = Math.min(distance[i - 1][j - 1] + (expected[i - 1] == actual[j - 1] ? 0 : 1),
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1));
            JSONArray positions = new JSONArray(); int i = expected.length, j = actual.length;
            int substitutions = 0, deletions = 0, insertions = 0, whitespace = 0;
            while (i > 0 || j > 0) {
                if (i > 0 && j > 0 && expected[i - 1] == actual[j - 1]) { i--; j--; continue; }
                String operation; boolean space;
                if (i > 0 && j > 0 && distance[i][j] == distance[i - 1][j - 1] + 1) {
                    i--; j--; substitutions++; operation = "substitution"; space = space(expected[i]) || space(actual[j]);
                } else if (i > 0 && distance[i][j] == distance[i - 1][j] + 1) {
                    i--; deletions++; operation = "deletion"; space = space(expected[i]);
                } else { j--; insertions++; operation = "insertion"; space = space(actual[j]); }
                if (space) whitespace++;
                if (positions.length() < 32) positions.put(new JSONObject().put("operation", operation)
                        .put("reference_position", i).put("output_position", j).put("whitespace", space));
            }
            int edits = distance[expected.length][actual.length];
            return new JSONObject().put("exact", output != null && edits == 0).put("edits", edits)
                    .put("reference_points", expected.length).put("output_points", actual.length)
                    .put("cer", (double) edits / Math.max(1, expected.length)).put("substitutions", substitutions)
                    .put("deletions", deletions).put("insertions", insertions).put("whitespace_edits", whitespace)
                    .put("differences", positions).put("difference_positions_truncated", edits > positions.length());
        } finally {
            Arrays.fill(expected, 0); Arrays.fill(actual, 0);
            for (int[] row : distance) Arrays.fill(row, 0);
        }
    }
    private static boolean space(int value) { return Character.isWhitespace(value) || Character.isSpaceChar(value); }
    private static int[] points(char[] value) {
        if (value == null) return new int[0];
        int[] points = new int[Character.codePointCount(value, 0, value.length)]; int next = 0;
        for (int offset = 0; offset < value.length;) {
            int point = Character.codePointAt(value, offset, value.length); points[next++] = point;
            offset += Character.charCount(point);
        }
        return points;
    }
    static void verifyMetric() throws Exception {
        if (compare("a🙂 b", "a🙂b".toCharArray()).getInt("edits") != 1
                || compare("abc", "axc".toCharArray()).getInt("substitutions") != 1
                || compare("abc", "abxc".toCharArray()).getInt("insertions") != 1
                || !compare("🙂", "🙂".toCharArray()).getBoolean("exact")) throw new AssertionError("Metric self-check");
    }
}
