package com.adam.nowplaying;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Minimal WebSocket client for Soloist's local control API (ws://127.0.0.1:9090). */
final class SoloistClient {

    interface Listener {
        void onConnected();
        void onDisconnected();
        void onMessage(JSONObject msg);
    }

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 9090;

    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private volatile boolean running;
    private volatile Socket socket;
    private OutputStream out;
    private Thread thread;

    SoloistClient(Listener listener) {
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "soloist-ws");
        thread.start();
    }

    void stop() {
        running = false;
        closeQuietly();
        if (thread != null) thread.interrupt();
    }

    void command(String name) {
        sendJson("{\"type\":\"command\",\"command\":\"" + name + "\"}");
    }

    void command(String name, String key, long value) {
        sendJson("{\"type\":\"command\",\"command\":\"" + name + "\",\"" + key + "\":" + value + "}");
    }

    void command(String name, String key, boolean value) {
        sendJson("{\"type\":\"command\",\"command\":\"" + name + "\",\"" + key + "\":" + value + "}");
    }

    private void sendJson(final String json) {
        new Thread(() -> {
            try {
                sendFrame(0x1, json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                closeQuietly();
            }
        }, "soloist-send").start();
    }

    private void loop() {
        while (running) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(HOST, PORT), 3000);
                s.setTcpNoDelay(true);
                socket = s;
                InputStream in = s.getInputStream();
                synchronized (this) {
                    out = s.getOutputStream();
                }
                handshake(in);
                listener.onConnected();
                readFrames(in);
            } catch (IOException ignored) {
            } finally {
                closeQuietly();
                if (running) listener.onDisconnected();
            }
            if (!running) break;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void handshake(InputStream in) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String req = "GET / HTTP/1.1\r\nHost: " + HOST + ":" + PORT + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        synchronized (this) {
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new IOException("closed during handshake");
            head.append((char) b);
            if (head.length() > 8192) throw new IOException("handshake too long");
        }
        if (!head.toString().startsWith("HTTP/1.1 101")) throw new IOException("bad handshake");
    }

    private void readFrames(InputStream in) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (running) {
            int b0 = readByte(in);
            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            byte[] mask = null;
            if ((b1 & 0x80) != 0) {
                mask = new byte[4];
                readFully(in, mask);
            }
            if (len > 16 * 1024 * 1024) throw new IOException("frame too large");
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            if (opcode == 0x8) {
                throw new IOException("server closed");
            } else if (opcode == 0x9) {
                sendFrame(0xA, payload);
            } else if (opcode == 0x1 || opcode == 0x0) {
                message.write(payload);
                if (fin) {
                    String text = new String(message.toByteArray(), StandardCharsets.UTF_8);
                    message.reset();
                    try {
                        listener.onMessage(new JSONObject(text));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        if (out == null) throw new IOException("not connected");
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) {
            frame.write(0x80 | n);
        } else if (n < 65536) {
            frame.write(0x80 | 126);
            frame.write((n >> 8) & 0xFF);
            frame.write(n & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((int) (((long) n >> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < n; i++) frame.write(payload[i] ^ mask[i & 3]);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("eof");
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw new IOException("eof");
            off += r;
        }
    }

    private void closeQuietly() {
        Socket s = socket;
        socket = null;
        synchronized (this) {
            out = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
