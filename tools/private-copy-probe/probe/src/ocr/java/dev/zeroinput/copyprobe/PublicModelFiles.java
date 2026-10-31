package dev.zeroinput.copyprobe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** Only fixed public assets enter this store. Cache hits are content-verified every request. */
final class PublicModelFiles {
    interface Source { InputStream open() throws Exception; }
    interface Cancellation { void check() throws InterruptedException; }
    static final class Prepared {
        final File file;
        final long bytesWritten;
        Prepared(File file, long bytesWritten) { this.file = file; this.bytesWritten = bytesWritten; }
    }
    private PublicModelFiles() { }
    static synchronized Prepared prepare(File target, int size, String sha256, Source source,
            Cancellation cancellation, boolean rewriteForExperiment) throws Exception {
        cancellation.check();
        if (!rewriteForExperiment && valid(target, size, sha256, cancellation)) {
            cancellation.check(); return new Prepared(target, 0);
        }
        File staging = File.createTempFile("public-model-", ".tmp", target.getParentFile());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); int total = 0;
            byte[] buffer = new byte[8192];
            try (InputStream in = source.open(); FileOutputStream out = new FileOutputStream(staging)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    cancellation.check();
                    if (count > size - total) throw new IllegalStateException("Public model size mismatch");
                    total += count; digest.update(buffer, 0, count); out.write(buffer, 0, count);
                }
                if (total != size || !matches(digest.digest(), sha256))
                    throw new IllegalStateException("Public model checksum mismatch");
                out.getFD().sync();
            } finally { Arrays.fill(buffer, (byte) 0); }
            cancellation.check();
            // Fail closed without a non-atomic fallback; partial files never become executable input.
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            cancellation.check(); return new Prepared(target, size);
        } finally { Files.deleteIfExists(staging.toPath()); }
    }
    private static boolean valid(File file, int size, String hash, Cancellation cancellation) throws Exception {
        if (!file.isFile() || file.length() != size) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192]; int total = 0;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            int count;
            while ((count = in.read(buffer)) != -1) {
                cancellation.check(); if (count > size - total) return false;
                total += count; digest.update(buffer, 0, count);
            }
            return total == size && matches(digest.digest(), hash);
        } finally { Arrays.fill(buffer, (byte) 0); }
    }
    private static boolean matches(byte[] digest, String expected) {
        StringBuilder value = new StringBuilder();
        for (byte part : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
        return expected.contentEquals(value);
    }
}
