package cn.paperstudio.p3print;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Wire format independently implemented from P3ProPrint 3.4.0 Tools.packPerBytes. */
public final class Protocol {
    public static final int WIDTH = 864;
    public static final int PAYLOAD_SIZE = 3456;
    public static final long CRC_SEED = 896963873L;

    private Protocol() {}

    public static byte[] frame(int command, int index, byte[] payload) {
        if (payload.length > 65535 || index < 0) {
            throw new IllegalArgumentException("Invalid packet size or index");
        }
        byte[] packet = new byte[payload.length + 10];
        packet[0] = 2;
        packet[1] = (byte) command;
        // The supplied SDK clamps every index above 127 to 128.
        packet[2] = (byte) Math.min(index, 128);
        packet[3] = (byte) payload.length;
        packet[4] = (byte) (payload.length >>> 8);
        System.arraycopy(payload, 0, packet, 5, payload.length);
        long crc = checksum(payload);
        for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
            packet[5 + payload.length + byteIndex] = (byte) (crc >>> (byteIndex * 8));
        }
        packet[packet.length - 1] = 3;
        return packet;
    }

    public static long checksum(byte[] bytes) {
        long crc = CRC_SEED ^ 0xffffffffL;
        for (byte value : bytes) {
            crc ^= value & 255;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xedb88320L);
            }
        }
        return (crc ^ 0xffffffffL) & 0xffffffffL;
    }

    public static byte[] feed() {
        return frame(0x1a, 0, new byte[]{(byte) 190, 0});
    }

    public static byte[] payload(byte[] raster, int offset) {
        return Arrays.copyOfRange(raster, offset, Math.min(raster.length, offset + PAYLOAD_SIZE));
    }

    public static String statusError(int status) {
        StringBuilder error = new StringBuilder();
        if ((status & 1) != 0 && (status & 16) == 0) error.append("缺纸 ");
        if ((status & 2) != 0) error.append("过热 ");
        if ((status & 4) != 0) error.append("温度过低 ");
        if ((status & 16) != 0) error.append("纸盖未合上 ");
        if ((status & 32) != 0) error.append("打印机休眠 ");
        return error.toString().trim();
    }

    public static String hex(byte[] bytes, int maximum) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < Math.min(bytes.length, maximum); index++) {
            if (index > 0) text.append(' ');
            int value = bytes[index] & 255;
            text.append("0123456789ABCDEF".charAt(value >>> 4));
            text.append("0123456789ABCDEF".charAt(value & 15));
        }
        if (bytes.length > maximum) text.append(" … ").append(bytes.length).append(" bytes");
        return text.toString();
    }

    public static final class ReceivedFrame {
        public final int head;
        public final int command;
        public final byte[] payload;

        private ReceivedFrame(int head, int command, byte[] payload) {
            this.head = head;
            this.command = command;
            this.payload = payload;
        }
    }

    /** Receive layouts match the original SDK's getDataStartPos/LenByProtocol. */
    public static final class Decoder {
        private byte[] pending = new byte[0];

        public List<ReceivedFrame> accept(byte[] incoming, int count) {
            if (count < 0 || count > incoming.length || count > 4096) throw new IllegalArgumentException("Invalid serial read size");
            byte[] buffer = Arrays.copyOf(pending, pending.length + count);
            System.arraycopy(incoming, 0, buffer, pending.length, count);
            List<ReceivedFrame> frames = new ArrayList<>();
            int offset = 0;
            while (offset < buffer.length) {
                int head = buffer[offset] & 255;
                if (head != 2 && head != 7) {
                    offset++;
                    continue;
                }
                int headerSize = head == 7 ? 11 : 5;
                if (buffer.length - offset < headerSize) break;
                int lengthOffset = offset + (head == 7 ? 4 : 3);
                int length = (buffer[lengthOffset] & 255) | ((buffer[lengthOffset + 1] & 255) << 8);
                if (length > 4096) {
                    offset++;
                    continue;
                }
                int end = offset + length + (head == 7 ? 11 : 10);
                if (end > buffer.length) break;
                if (head == 2 && buffer[end - 1] != 3) {
                    offset++;
                    continue;
                }
                int dataStart = offset + headerSize;
                frames.add(new ReceivedFrame(head, buffer[offset + 1] & 255,
                        Arrays.copyOfRange(buffer, dataStart, dataStart + length)));
                offset = end;
            }
            pending = Arrays.copyOfRange(buffer, offset, buffer.length);
            return frames;
        }
    }
}
