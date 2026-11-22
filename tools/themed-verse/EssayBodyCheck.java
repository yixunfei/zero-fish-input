import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Independent offline reader for host research modes 8 and 9. Input contains public packets only. */
class EssayBodyCheck {
    private record Node(long weight, int order, byte[] bytes, Node left, Node right) {}
    private final Node root;
    private final Map<Integer, String> simplified = new HashMap<>();

    private static byte[] checked(Path file, int limit, String hash, boolean lf) throws Exception {
        byte[] data;
        try (var stream = Files.newInputStream(file)) {
            data = stream.readNBytes(limit + 1);
        }
        if (data.length > limit) throw new IOException("public resource size");
        if (lf) data = new String(data, UTF_8).replace("\r\n", "\n").getBytes(UTF_8);
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        if (!digest.equals(hash)) throw new IOException("public resource digest");
        return data;
    }

    EssayBodyCheck(Path essay, Path model, Path mapping) throws Exception {
        byte[] dict = checked(essay, 5887784, "196d508a9bb12fc6d711eed83f5ddc4d5c2d382d92d15b4e51175e4d3802cfab", false);
        var weights = new HashMap<String, Long>();
        for (String line : new String(dict, UTF_8).lines().toList()) {
            int tab = line.lastIndexOf('\t');
            String token = line.substring(0, tab);
            long weight = Long.parseLong(line.substring(tab + 1));
            if (weight > 0 && !token.isEmpty() && token.getBytes(UTF_8).length <= 18) weights.put(token, weight);
        }
        byte[] zwc = checked(model, 153939, "be3ab914d4ce250a2c23db6982cb040a131d7f9ba397d648cb95c641991fc01f", false);
        var entries = new DataInputStream(new ByteArrayInputStream(zwc));
        entries.readInt();
        int symbols = entries.readUnsignedShort();
        entries.readUnsignedShort();
        var prior = new HashMap<String, Long>();
        long total = 0;
        for (int i = 0; i < symbols; i++) {
            int length = entries.readUnsignedByte();
            entries.skipNBytes(5);
            long weight = entries.readUnsignedShort();
            total += weight;
            String token = new String(entries.readNBytes(length), UTF_8);
            if (weight > 1 || token.isEmpty()) prior.put(token, weight);
        }
        long scale = sum(weights) / total;
        prior.forEach((token, n) -> weights.merge(token, scale * n, Long::sum));
        weights.put(null, sum(weights) / 128);
        root = tree(weights);
        byte[] ts = checked(mapping, 100000, "6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a", true);
        for (String line : new String(ts, UTF_8).lines().toList()) {
            String[] parts = line.split("\t");
            simplified.put(parts[0].codePointAt(0), parts[1].split(" ")[0]);
        }
    }

    private static long sum(Map<String, Long> weights) {
        return weights.values().stream().mapToLong(Long::longValue).sum();
    }

    private static Node tree(Map<String, Long> weights) {
        record Leaf(byte[] bytes, long weight) {}
        var leaves = new ArrayList<Leaf>(weights.size());
        weights.forEach((s, weight) -> leaves.add(new Leaf(s == null ? new byte[]{-1} : s.getBytes(UTF_8), weight)));
        leaves.sort((a, b) -> Arrays.compareUnsigned(a.bytes, b.bytes));
        var queue = new PriorityQueue<Node>(Comparator.comparingLong(Node::weight).thenComparingInt(Node::order));
        int order = 0;
        for (var leaf : leaves) queue.add(new Node(leaf.weight, order++, leaf.bytes, null, null));
        while (queue.size() > 1) {
            var a = queue.remove();
            var b = queue.remove();
            queue.add(new Node(a.weight + b.weight, order++, null, a, b));
        }
        return queue.remove();
    }

    private static class Invalid extends Exception {}
    private static class Bits {
        final byte[] packet;
        int offset = 24;
        Bits(byte[] packet) { this.packet = packet; }
        int read(int n) throws Invalid {
            if (offset + n > packet.length * 8) throw new Invalid();
            int value = 0;
            for (int i = 0; i < n; i++, offset++)
                value = (value << 1) | ((packet[offset / 8] >> (7 - offset % 8)) & 1);
            return value;
        }
    }

    private byte[] decode(byte[] packet) throws Invalid, CharacterCodingException {
        if (packet.length < 4 || packet.length > 2048 || (packet[0] != 8 && packet[0] != 9)) throw new Invalid();
        int size = (packet[1] & 255) * 256 + (packet[2] & 255);
        if (size < 1 || size > 2048) throw new Invalid();
        byte[] out = new byte[size];
        var bits = new Bits(packet);
        int used = 0;
        while (used < size) {
            Node node = root;
            while (node.bytes == null) node = bits.read(1) == 0 ? node.left : node.right;
            byte[] token = node.bytes;
            if (token.length == 1 && token[0] == -1) {
                if (used == 0) throw new Invalid();
                int length = 3 + bits.read(6);
                int distance = 1 + bits.read(32 - Integer.numberOfLeadingZeros(used - 1));
                if (length > size - used || distance > used) throw new Invalid();
                for (int j = 0; j < length; j++, used++) out[used] = out[used - distance];
            } else {
                if (token.length == 0) {
                    token = new byte[1 + bits.read(4)];
                    for (int j = 0; j < token.length; j++) token[j] = (byte)bits.read(8);
                } else if (packet[0] == 9) {
                    var text = new StringBuilder();
                    new String(token, UTF_8).codePoints().forEach(cp -> {
                        String replacement = simplified.get(cp);
                        if (replacement == null) text.appendCodePoint(cp); else text.append(replacement);
                    });
                    token = text.toString().getBytes(UTF_8);
                }
                if (token.length > size - used) throw new Invalid();
                System.arraycopy(token, 0, out, used, token.length);
                used += token.length;
            }
        }
        int padding = packet.length * 8 - bits.offset;
        if (padding >= 8 || bits.read(padding) != 0) throw new Invalid();
        UTF_8.newDecoder().decode(ByteBuffer.wrap(out));
        return out;
    }

    public static void main(String[] args) throws Exception {
        var codec = new EssayBodyCheck(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        var input = new DataInputStream(System.in);
        var output = new DataOutputStream(System.out);
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int length = input.readUnsignedShort();
            byte[] packet = input.readNBytes(length);
            if (packet.length != length) throw new EOFException();
            try {
                byte[] raw = codec.decode(packet);
                output.writeShort(raw.length);
                output.write(raw);
            } catch (Invalid | CharacterCodingException expected) {
                output.writeShort(0);
            }
        }
        if (input.read() != -1) throw new IOException("trailing test batch");
        output.flush();
    }
}
