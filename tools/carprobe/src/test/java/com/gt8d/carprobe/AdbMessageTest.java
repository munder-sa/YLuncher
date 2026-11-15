package com.gt8d.carprobe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class AdbMessageTest {
    @Test
    public void encodeCnxnHeaderAndPayload() {
        byte[] payload = "host::\0".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = AdbClient.encode(AdbClient.A_CNXN, 0x01000001, 0x40000, payload);
        assertEquals(24 + payload.length, encoded.length);
        assertArrayEquals(new byte[] {0x43, 0x4e, 0x58, 0x4e}, Arrays.copyOf(encoded, 4));
        ByteBuffer header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x4e584e43, header.getInt());
        assertEquals(0x01000001, header.getInt());
        assertEquals(0x40000, header.getInt());
        assertEquals(7, header.getInt());
        assertEquals(562, header.getInt());
        assertEquals(0x4e584e43 ^ 0xffffffff, header.getInt());
        assertArrayEquals(payload, Arrays.copyOfRange(encoded, 24, encoded.length));
    }

    @Test
    public void checksumAddsUnsignedBytes() {
        byte[] payload = {0, 1, 0x7f, (byte) 0x80, (byte) 0xff};
        byte[] encoded = AdbClient.encode(AdbClient.A_WRTE, 1, 2, payload);
        long checksum = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(16)
                & 0xffffffffL;
        assertEquals(511L, checksum);
    }

    @Test
    public void roundTripWithFragmentedReads() throws IOException {
        byte[] payload = "NAV フォーカス\n".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = AdbClient.encode(AdbClient.A_WRTE, 0x87654321, 0x12345678, payload);
        ByteArrayInputStream fragmented = new ByteArrayInputStream(encoded) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 3));
            }
        };
        AdbClient.Message message = AdbClient.readMessage(fragmented);
        assertEquals(AdbClient.A_WRTE, message.command);
        assertEquals(0x87654321, message.arg0);
        assertEquals(0x12345678, message.arg1);
        assertArrayEquals(payload, message.payload);
        assertEquals(-1, fragmented.read());
    }

    @Test
    public void rejectsBadMagic() {
        byte[] encoded = AdbClient.encode(AdbClient.A_CNXN, 0, 0, null);
        encoded[20] ^= 1;
        IOException failure = assertThrows(IOException.class,
                () -> AdbClient.readMessage(new ByteArrayInputStream(encoded)));
        assertEquals("bad magic", failure.getMessage());
    }

    @Test
    public void nullPayloadHasZeroLengthAndChecksum() throws IOException {
        byte[] encoded = AdbClient.encode(AdbClient.A_OKAY, 1, 2, null);
        assertEquals(24, encoded.length);
        ByteBuffer header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, header.getInt(12));
        assertEquals(0, header.getInt(16));
        AdbClient.Message message = AdbClient.readMessage(new ByteArrayInputStream(encoded));
        assertEquals(AdbClient.A_OKAY, message.command);
        assertEquals(1, message.arg0);
        assertEquals(2, message.arg1);
        assertArrayEquals(new byte[0], message.payload);
    }

    @Test
    public void rejectsTruncatedHeader() {
        byte[] encoded = AdbClient.encode(AdbClient.A_OKAY, 1, 2, null);
        assertThrows(EOFException.class, () -> AdbClient.readMessage(
                new ByteArrayInputStream(Arrays.copyOf(encoded, 23))));
    }

    @Test
    public void rejectsTruncatedPayload() {
        byte[] encoded = AdbClient.encode(AdbClient.A_WRTE, 1, 2, new byte[] {1, 2});
        assertThrows(EOFException.class, () -> AdbClient.readMessage(
                new ByteArrayInputStream(Arrays.copyOf(encoded, encoded.length - 1))));
    }

    @Test
    public void rejectsLengthsAboveAdvertisedMaxData() {
        for (int length : new int[] {0x40001, -1}) {
            byte[] encoded = AdbClient.encode(AdbClient.A_WRTE, 1, 2, null);
            ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).putInt(12, length);
            IOException failure = assertThrows(IOException.class,
                    () -> AdbClient.readMessage(new ByteArrayInputStream(encoded)));
            assertEquals("bad data length: " + Integer.toUnsignedString(length),
                    failure.getMessage());
        }
    }
}
