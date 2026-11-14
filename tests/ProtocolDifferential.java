import cn.paperstudio.p3print.Protocol;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

public final class ProtocolDifferential {
    public static void main(String[] args) throws Exception {
        long[] expected = {0x35769521L, 0xe959b9d0L, 0x9e5e8946L, 0xe211665cL, 0x3765d697L};
        String[] payloads = {"", "01", "00", "313233343536373839", "BE00"};
        for (int index = 0; index < payloads.length; index++) {
            if (Protocol.checksum(hex(payloads[index])) != expected[index]) throw new AssertionError("Seeded CRC fixture " + index);
        }
        if (!Arrays.equals(Protocol.feed(), hex("021A000200BE0097D6653703"))) throw new AssertionError("Feed fixture");
        byte[] raster = new byte[10000];
        int[] lengths = {3456, 3456, 3088};
        for (int index = 0; index < lengths.length; index++) {
            if (Protocol.payload(raster, index * 3456).length != lengths[index]) throw new AssertionError("Chunk fixture " + index);
        }
        System.out.println("PASS seeded CRC fixtures independently computed with Python zlib + captured feed + chunk boundaries");
        receiveFixtures();
        if (args.length > 0 && "--original".equals(args[0])) compareOriginal();
    }

    private static void compareOriginal() throws Exception {
        Class<?> tools = Class.forName("com.paperang.tools.Tools");
        java.lang.reflect.Method crc = tools.getMethod("Lcrc32", byte[].class, int.class);
        java.lang.reflect.Method pack = tools.getMethod("packPerBytes", java.util.List.class, byte.class, int.class, boolean.class);
        java.lang.reflect.Method split = tools.getMethod("addBytesToList", byte[].class);
        Random random = new Random(0x503350524fL);
        int count = 0;
        for (int length : new int[]{0, 1, 2, 107, 108, 3455, 3456, 4096, 65535}) {
            byte[] payload = new byte[length];
            random.nextBytes(payload);
            if (Protocol.checksum(payload) != (Long) crc.invoke(null, payload, length)) throw new AssertionError("CRC mismatch " + length);
            for (int index : new int[]{0, 1, 126, 127, 128, 129, 255, 1000}) {
                java.util.List<byte[]> packets = Collections.nCopies(index + 1, payload);
                byte[] expected = (byte[]) pack.invoke(null, packets, (byte) 0, index, false);
                byte[] actual = Protocol.frame(0, index, payload);
                if (!Arrays.equals(expected, actual)) throw new AssertionError("Packet mismatch " + length + " / " + index);
                count++;
            }
        }
        byte[] feed = (byte[]) pack.invoke(null, Collections.singletonList(new byte[]{(byte) 190, 0}), (byte) 26, 0, false);
        if (!Arrays.equals(feed, Protocol.feed())) throw new AssertionError("Feed mismatch");
        byte[] raster = new byte[10000];
        int[] expectedLengths = {3456, 3456, 3088};
        @SuppressWarnings("unchecked")
        java.util.List<byte[]> original = (java.util.List<byte[]>) split.invoke(null, (Object) raster);
        for (int index = 0; index < original.size(); index++) {
            byte[] actual = Protocol.payload(raster, index * Protocol.PAYLOAD_SIZE);
            if (actual.length != expectedLengths[index] || !Arrays.equals(original.get(index), actual)) throw new AssertionError("Chunk mismatch");
        }
        System.out.println("PASS " + count + " differential packet cases + seeded CRC + feed + chunk boundaries");
        System.out.println("Feed frame " + Protocol.hex(feed, feed.length));
    }

    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        return bytes;
    }

    private static void receiveFixtures() {
        // Status frames transcribed from the user's P3 Pro photos, including both cover transitions.
        String[] captured = {"070D000001008DEF02D2BA00", "070D00000100E9FEB5CED610",
                "070D000001008DEF02D2BA00", "070D000001001BDF05A50E01"};
        int[] statuses = {0, 16, 0, 1};
        int splits = 0;
        java.io.ByteArrayOutputStream combined = new java.io.ByteArrayOutputStream();
        for (int index = 0; index < captured.length; index++) {
            byte[] raw = hex(captured[index]);
            combined.write(raw, 0, raw.length);
            for (int split = 0; split <= raw.length; split++) {
                Protocol.Decoder decoder = new Protocol.Decoder();
                java.util.List<Protocol.ReceivedFrame> frames = decoder.accept(Arrays.copyOfRange(raw, 0, split), split);
                frames.addAll(decoder.accept(Arrays.copyOfRange(raw, split, raw.length), raw.length - split));
                if (frames.size() != 1 || frames.get(0).head != 7 || frames.get(0).command != 13
                        || frames.get(0).payload.length != 1 || (frames.get(0).payload[0] & 255) != statuses[index]) throw new AssertionError("07 receive split " + index + " / " + split);
                splits++;
            }
        }
        byte[] legacy = Protocol.frame(13, 0, new byte[]{0});
        combined.write(legacy, 0, legacy.length);
        Protocol.Decoder decoder = new Protocol.Decoder();
        java.util.List<Protocol.ReceivedFrame> received = new java.util.ArrayList<>();
        for (byte value : combined.toByteArray()) received.addAll(decoder.accept(new byte[]{value}, 1));
        if (received.size() != 5 || received.get(4).head != 2 || received.get(4).payload[0] != 0) throw new AssertionError("Mixed 02/07 byte stream");
        decoder = new Protocol.Decoder();
        byte[] noisy = new byte[combined.size() + 12];
        Arrays.fill(noisy, 0, 12, (byte) 0xff);
        System.arraycopy(combined.toByteArray(), 0, noisy, 12, combined.size());
        if (decoder.accept(noisy, noisy.length).size() != 5) throw new AssertionError("Noise and coalesced frames");
        if (!Protocol.statusError(0).isEmpty() || !"纸盖未合上".equals(Protocol.statusError(16))
                || !"缺纸".equals(Protocol.statusError(1)) || !"纸盖未合上".equals(Protocol.statusError(17))) throw new AssertionError("Status meanings");
        System.out.println("PASS " + splits + " captured 07 split cases + mixed 02/07 streams + noise + cover/paper states");
    }
}
