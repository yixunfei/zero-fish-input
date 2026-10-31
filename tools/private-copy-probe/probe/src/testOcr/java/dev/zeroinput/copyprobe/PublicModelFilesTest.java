package dev.zeroinput.copyprobe;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class PublicModelFilesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] PUBLIC = {1, 2, 3, 4};
    private static String hash() throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(PUBLIC))
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private PublicModelFiles.Prepared prepare(File file, byte[] source) throws Exception {
        return PublicModelFiles.prepare(file, PUBLIC.length, hash(), () -> new ByteArrayInputStream(source), () -> { }, false);
    }
    @Test public void verifiedCacheHitPreservesTimestampWithoutOpeningAssetOrWriting() throws Exception {
        File file = new File(temporary.getRoot(), "model");
        assertEquals(4, prepare(file, PUBLIC).bytesWritten);
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_700_000_000_000L));
        FileTime before = Files.getLastModifiedTime(file.toPath());
        assertEquals(0, PublicModelFiles.prepare(file, 4, hash(), () -> {
            throw new AssertionError("Cache must not reopen asset");
        }, () -> { }, false).bytesWritten);
        assertEquals(before, Files.getLastModifiedTime(file.toPath()));
        assertArrayEquals(PUBLIC, Files.readAllBytes(file.toPath()));
    }
    @Test public void sameSizeTamperingIsRevalidatedAndRebuilt() throws Exception {
        File file = new File(temporary.getRoot(), "model"); prepare(file, PUBLIC);
        Files.write(file.toPath(), new byte[] {1, 2, 3, 5});
        assertEquals(4, prepare(file, PUBLIC).bytesWritten);
        assertArrayEquals(PUBLIC, Files.readAllBytes(file.toPath()));
    }
    @Test public void truncatedCacheIsRebuilt() throws Exception {
        File file = new File(temporary.getRoot(), "model"); Files.write(file.toPath(), new byte[] {1});
        assertEquals(4, prepare(file, PUBLIC).bytesWritten);
    }
    @Test public void invalidAssetCannotActivateAndTemporaryFileIsRemoved() throws Exception {
        for (byte[] invalid : new byte[][] {{1}, {1, 2, 3, 5}, {1, 2, 3, 4, 5}}) {
            File file = new File(temporary.getRoot(), "model");
            assertThrows(IllegalStateException.class, () -> prepare(file, invalid));
            assertFalse(file.exists()); assertEquals(0, temporary.getRoot().list().length);
        }
    }
    @Test public void invalidReplacementPreservesPreviouslyVerifiedFile() throws Exception {
        File file = new File(temporary.getRoot(), "model"); prepare(file, PUBLIC);
        assertThrows(IllegalStateException.class, () -> PublicModelFiles.prepare(file, 4, hash(),
                () -> new ByteArrayInputStream(new byte[] {0}), () -> { }, true));
        assertArrayEquals(PUBLIC, Files.readAllBytes(file.toPath()));
        assertEquals(1, temporary.getRoot().list().length);
    }
    @Test public void cancellationDuringWriteCannotActivateOrLeaveTemporaryFile() throws Exception {
        File file = new File(temporary.getRoot(), "model"); AtomicInteger checks = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> PublicModelFiles.prepare(file, 4, hash(),
                () -> new ByteArrayInputStream(PUBLIC), () -> { if (checks.incrementAndGet() == 2) throw new InterruptedException(); }, false));
        assertFalse(file.exists()); assertEquals(0, temporary.getRoot().list().length);
    }
    @Test public void cancellationBeforeRenamePreservesExistingFile() throws Exception {
        File file = new File(temporary.getRoot(), "model"); prepare(file, PUBLIC);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> PublicModelFiles.prepare(file, 4, hash(),
                () -> new ByteArrayInputStream(PUBLIC), () -> { if (checks.incrementAndGet() == 3) throw new InterruptedException(); }, true));
        assertArrayEquals(PUBLIC, Files.readAllBytes(file.toPath())); assertEquals(1, temporary.getRoot().list().length);
    }
    @Test public void cancelledCacheHitCannotReturnExecutablePath() throws Exception {
        File file = new File(temporary.getRoot(), "model"); prepare(file, PUBLIC);
        assertThrows(InterruptedException.class, () -> PublicModelFiles.prepare(file, 4, hash(),
                () -> new ByteArrayInputStream(PUBLIC), () -> { throw new InterruptedException(); }, false));
    }
    @Test public void cancellationDuringCacheVerificationCannotReturnOrReplaceFile() throws Exception {
        File file = new File(temporary.getRoot(), "model"); prepare(file, PUBLIC);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> PublicModelFiles.prepare(file, 4, hash(),
                () -> { throw new AssertionError("Cancelled cache must not reopen asset"); },
                () -> { if (checks.incrementAndGet() == 2) throw new InterruptedException(); }, false));
        assertArrayEquals(PUBLIC, Files.readAllBytes(file.toPath())); assertEquals(1, temporary.getRoot().list().length);
    }
}
