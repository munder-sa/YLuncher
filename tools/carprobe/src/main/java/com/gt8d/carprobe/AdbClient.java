package com.gt8d.carprobe;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One unauthenticated loopback ADB shell per call; never call from the UI thread. */
public final class AdbClient {
    static final int A_CNXN = 0x4e584e43;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_AUTH = 0x48545541;
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final String VIA = HOST + ":" + PORT;
    private static final int VERSION = 0x01000001;
    private static final int MAX_DATA = 0x40000;
    private static final String EXIT_MARKER = "__CARPROBE_EXIT__";
    private static final Pattern EXIT_LINE = Pattern.compile(
            "(?m)^" + EXIT_MARKER + "([0-9]+)\\r?$");

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final boolean ok;
        public final String via;

        private Result(int exitCode, String stdout, String via) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.ok = exitCode == 0;
            this.via = via;
        }
    }

    static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] payload;

        Message(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }

    private AdbClient() {}

    static byte[] encode(int command, int arg0, int arg1, byte[] payload) {
        int length = payload == null ? 0 : payload.length;
        long checksum = 0;
        if (payload != null) {
            for (byte value : payload) {
                checksum += value & 0xff;
            }
        }
        ByteBuffer buffer = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(command).putInt(arg0).putInt(arg1).putInt(length)
                .putInt((int) (checksum & 0xffffffffL)).putInt(command ^ 0xffffffff);
        if (payload != null) {
            buffer.put(payload);
        }
        return buffer.array();
    }

    static Message readMessage(InputStream input) throws IOException {
        return readMessage(input, null, 0);
    }

    private static Message readMessage(InputStream input, Socket socket, long deadline)
            throws IOException {
        ByteBuffer header = ByteBuffer.wrap(readFully(input, 24, socket, deadline))
                .order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt();
        int arg0 = header.getInt();
        int arg1 = header.getInt();
        int length = header.getInt();
        header.getInt(); // Modern adbd does not require a checksum on received messages.
        if (header.getInt() != (command ^ 0xffffffff)) {
            throw new IOException("bad magic");
        }
        // Do not allocate an unbounded buffer from an invalid wire length.
        if (length < 0 || length > MAX_DATA) {
            throw new IOException("bad data length: " + Integer.toUnsignedString(length));
        }
        return new Message(command, arg0, arg1, readFully(input, length, socket, deadline));
    }

    private static byte[] readFully(InputStream input, int length, Socket socket, long deadline)
            throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            checkInterrupted();
            if (socket != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new SocketTimeoutException("stream deadline exceeded");
                }
                // Round up so a sub-millisecond remainder never disables SO_TIMEOUT.
                socket.setSoTimeout((int) Math.max(1,
                        (remaining + 999999L) / 1000000L));
            }
            int count = input.read(data, offset, length - offset);
            checkInterrupted();
            if (count < 0) {
                throw new EOFException("incomplete ADB message");
            }
            if (count == 0) {
                throw new IOException("ADB read made no progress");
            }
            offset += count;
        }
        return data;
    }

    public static synchronized Result run(String command, int timeoutMs) {
        if (timeoutMs <= 0) {
            return new Result(-1, "timeoutMs must be positive", VIA);
        }
        if (Thread.currentThread().isInterrupted()) {
            return new Result(-1, "Interrupted", VIA);
        }
        Socket socket = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean readingStream = false;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(HOST, PORT), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            InputStream input = socket.getInputStream();
            OutputStream sink = socket.getOutputStream();
            send(sink, A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            Message handshake = readMessage(input);
            if (handshake.command == A_AUTH) {
                return new Result(-1,
                        "device requires ADB auth (expected ro.adb.secure=0)", VIA);
            }
            if (handshake.command != A_CNXN) {
                throw new IOException("unexpected handshake: 0x"
                        + Integer.toHexString(handshake.command));
            }

            int localId = 1;
            String wrapped = command + "\n" + "echo " + EXIT_MARKER + "$?";
            send(sink, A_OPEN, localId, 0,
                    ("shell:" + wrapped + "\0").getBytes(StandardCharsets.UTF_8));
            Message opened = readMessage(input);
            if (opened.arg1 != localId) {
                throw new IOException("unexpected local stream id: " + opened.arg1);
            }
            if (opened.command == A_CLSE) {
                return completed(output);
            }
            if (opened.command != A_OKAY || opened.arg0 == 0) {
                throw new IOException("unexpected open response: 0x"
                        + Integer.toHexString(opened.command));
            }
            int remoteId = opened.arg0;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            readingStream = true;
            while (true) {
                Message message = readMessage(input, socket, deadline);
                if (message.arg0 != remoteId || message.arg1 != localId) {
                    throw new IOException("unexpected stream ids: "
                            + message.arg0 + "/" + message.arg1);
                }
                if (message.command == A_WRTE) {
                    output.write(message.payload);
                    send(sink, A_OKAY, localId, remoteId, null);
                } else if (message.command == A_CLSE) {
                    send(sink, A_CLSE, localId, remoteId, null);
                    if (System.nanoTime() > deadline) {
                        throw new SocketTimeoutException("stream deadline exceeded");
                    }
                    return completed(output);
                } else if (message.command != A_OKAY) {
                    throw new IOException("unexpected stream command: 0x"
                            + Integer.toHexString(message.command));
                }
                if (System.nanoTime() > deadline) {
                    throw new SocketTimeoutException("stream deadline exceeded");
                }
            }
        } catch (SocketTimeoutException e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return new Result(-1, utf8(output) + "\nInterrupted", VIA);
            }
            return readingStream
                    ? new Result(124, utf8(output) + "\nTimed out after " + timeoutMs + " ms", VIA)
                    : new Result(-1, utf8(output) + "\n" + e, VIA);
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, utf8(output) + "\nInterrupted", VIA);
        } catch (IOException | SecurityException e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return new Result(-1, utf8(output) + "\nInterrupted", VIA);
            }
            return new Result(-1, utf8(output) + "\n" + e, VIA);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Preserve the original command result if cleanup fails.
                }
            }
        }
    }

    private static void send(OutputStream output, int command, int arg0, int arg1,
            byte[] payload) throws IOException {
        checkInterrupted();
        output.write(encode(command, arg0, arg1, payload));
        output.flush();
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted");
        }
    }

    private static String utf8(ByteArrayOutputStream output) {
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Result completed(ByteArrayOutputStream output) throws IOException {
        String text = utf8(output);
        Matcher marker = EXIT_LINE.matcher(text);
        int markerStart = -1;
        String code = null;
        while (marker.find()) {
            markerStart = marker.start();
            code = marker.group(1);
        }
        int exitCode = 0;
        if (markerStart >= 0) {
            try {
                exitCode = Integer.parseInt(code);
            } catch (NumberFormatException e) {
                throw new IOException("invalid exit code: " + code, e);
            }
            text = text.substring(0, markerStart);
        }
        // Preserve meaningful spaces; only remove extra CR/LF at the ends.
        int start = 0;
        int end = text.length();
        while (start < end && isNewline(text.charAt(start))) {
            start++;
        }
        while (end > start && isNewline(text.charAt(end - 1))) {
            end--;
        }
        return new Result(exitCode, text.substring(start, end), VIA);
    }

    private static boolean isNewline(char value) {
        return value == '\r' || value == '\n';
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
