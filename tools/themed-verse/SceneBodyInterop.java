import dev.zeroinput.ime.core.stego.WordContextBody;
import dev.zeroinput.ime.core.stego.VerseTestSurface;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Feed authenticated public packets to the compiled Debug codec without providing the originals. */
class SceneBodyInterop {
    public static void main(String[] args) throws Exception {
        var codec = new WordContextBody(Files.readAllBytes(Path.of(args[0])));
        var input = new DataInputStream(System.in);
        var output = new DataOutputStream(System.out);
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int length = input.readUnsignedShort();
            if (length < 4 || length > 2048) throw new IllegalArgumentException("body length");
            var packet = input.readNBytes(length);
            if (packet.length != length) throw new EOFException();
            var original = codec.decode(packet, () -> true);
            // Fixed two-layer research envelope, including the 36-byte inner header.
            var regenerated = codec.encode(original, n -> 204 + ((36 + n + 63) / 64) * 64, () -> true);
            for (byte[] field : new byte[][] {original, regenerated}) {
                output.writeShort(field.length);
                output.write(field);
            }
        }
        int surfaces = input.readUnsignedShort();
        if (surfaces > 0) checkFrozenSurfaces(input, output, Path.of(args[1]), surfaces);
        if (input.read() != -1) throw new IllegalArgumentException("trailing batch input");
        output.flush();
    }

    private static void checkFrozenSurfaces(DataInputStream input, DataOutputStream output,
                                            Path assets, int count) throws Exception {
        var five = new VerseTestSurface(Files.readAllBytes(assets.resolve("opening.bin")), VerseTestSurface.Form.FIVE);
        var seven = new VerseTestSurface(Files.readAllBytes(assets.resolve("seven.bin")), VerseTestSurface.Form.SEVEN);
        for (int i = 0; i < count; i++) {
            var surface = switch (input.readUnsignedByte()) {
                case 5 -> five;
                case 7 -> seven;
                default -> throw new IllegalArgumentException("unknown verse width");
            };
            int size = input.readUnsignedShort();
            if (size < 1 || size > 16384) throw new IllegalArgumentException("carrier size");
            byte[] data = new byte[size];
            input.readFully(data);
            var text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();
            var envelope = surface.decodeWrapped(text, 2548, () -> true);
            output.writeShort(envelope.length);
            output.write(envelope);
        }
    }
}
