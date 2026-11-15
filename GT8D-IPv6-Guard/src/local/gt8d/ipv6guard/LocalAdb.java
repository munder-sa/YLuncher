package local.gt8d.ipv6guard;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Minimal legacy ADB shell client for this GT8D's unauthenticated local adbd. */
final class LocalAdb {
    private static final int CNXN = 0x4e584e43, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, CLSE = 0x45534c43;
    private static final int WRTE = 0x45545257, AUTH = 0x48545541;
    private static final int LIMIT = 1024 * 1024;
    private static final String END = "__GT8D_IPV6_GUARD_COMMAND_FINISHED__";
    static final String SOCKET_NAME = "gt8d-ipv6-guard";

    static String shell(String command) throws IOException {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(3500);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            send(out, CNXN, 0x01000000, 4096, bytes("host::\0"));
            Message hello = receive(in);
            if (hello.command == AUTH) throw new IOException("ADB認証が必要なため接続できません");
            if (hello.command != CNXN) throw new IOException("ADB接続応答が不正です");
            send(out, OPEN, 1, 0, bytes("shell:{ " + command + "; }; echo " + END + "\0"));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            boolean opened = false;
            while (true) {
                Message message = receive(in);
                if (message.arg1 != 1) throw new IOException("ADBストリームが一致しません");
                if (message.command == OKAY) {
                    opened = true;
                } else if (message.command == WRTE && opened) {
                    if (result.size() + message.data.length > LIMIT) throw new IOException("ADB出力が大きすぎます");
                    result.write(message.data);
                    send(out, OKAY, 1, message.arg0, new byte[0]);
                    String text = new String(result.toByteArray(), StandardCharsets.UTF_8);
                    int end = text.indexOf(END);
                    if (end >= 0) return text.substring(0, end);
                } else if (message.command == CLSE) {
                    send(out, CLSE, 1, message.arg0, new byte[0]);
                    if (!opened) throw new IOException("ADBシェルを開始できません");
                    return new String(result.toByteArray(), StandardCharsets.UTF_8);
                } else {
                    throw new IOException("予期しないADB応答です");
                }
            }
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static int checksum(byte[] data) {
        int result = 0;
        for (byte value : data) result += value & 255;
        return result;
    }
    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length)
                .putInt(checksum(data)).putInt(command ^ -1);
        out.write(header.array());
        out.write(data);
        out.flush();
    }
    private static Message receive(DataInputStream in) throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message message = new Message();
        message.command = header.getInt();
        message.arg0 = header.getInt();
        message.arg1 = header.getInt();
        int length = header.getInt(), sum = header.getInt(), magic = header.getInt();
        if (length < 0 || length > LIMIT || magic != (message.command ^ -1))
            throw new IOException("ADBヘッダーが不正です");
        message.data = new byte[length];
        in.readFully(message.data);
        if (checksum(message.data) != sum) throw new IOException("ADBチェックサムが一致しません");
        return message;
    }
    private static final class Message { int command, arg0, arg1; byte[] data; }
}
