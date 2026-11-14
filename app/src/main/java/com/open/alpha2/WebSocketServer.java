package com.open.alpha2;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Hand-rolled RFC 6455 WebSocket server: handshake (Sec-WebSocket-Accept derivation) plus
 * minimal text-frame send/receive. No library - matches the SDK's own zero-third-party-
 * dependency policy. Used only to push a live event log (head-key presses, ASR results,
 * action lifecycle, wakeup, gesture, ...) from {@link EventBus} to the browser control
 * panel; the panel never needs binary frames or fragmentation, so this intentionally
 * doesn't implement the full spec (no ping/pong, no fragmented messages, no extensions).
 */
public class WebSocketServer {
    private static final String TAG = "WebSocketServer";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // Each process start mints a fresh bootId (it changes on APK reinstall / app restart):
    // if a browser tab still runs old JS (it was open at install time and never reloaded),
    // a WS reconnect that sees a different bootId triggers location.reload() to fetch the
    // new version -- after an install there is no manual F5 needed anymore; see the
    // frontend connectWs.
    public static final String BOOT_ID = java.util.UUID.randomUUID().toString();

    // Added 2026-08: see the comment in handleUpgrade() below - used to detect
    // "half-open" zombie connections. A 10-second SO_TIMEOUT; on timeout, send a
    // ping to probe. Only after two consecutive timeouts (20 seconds total) with
    // no response at all (not even a pong) is the connection considered dead. This
    // does not kill a healthy idle connection that merely has no event to send and
    // no ping/pong traffic (the browser still sends pings periodically; when
    // readLoop() receives one it answers a pong immediately, which also counts as
    // "something received" and does not count toward the timeout).
    private static final int IDLE_TIMEOUT_MS = 10000;
    private static final int MAX_CONSECUTIVE_TIMEOUTS = 2;

    public static void handleUpgrade(Socket socket, InputStream rawIn, Map<String, String> headers) {
        try {
            String key = headers.get("sec-websocket-key");
            if (key == null) {
                socket.close();
                return;
            }
            String accept = computeAccept(key);

            OutputStream out = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    + "\r\n";
            out.write(response.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            // Added 2026-08: when the phone browser goes to the background / the screen
            // locks / the network changes, the TCP connection can become "half-open" -
            // the peer (the browser) no longer pays attention to this connection, but
            // does not send FIN/RST, so the in.read() in readLoop() below blocks forever
            // and the unsubscribe() in finally never gets a chance to run. Result: the
            // client uses the connectWs() 3-second reconnect to open several new
            // connections, but the listener of the old zombie connection stays in the
            // EventBus, so the same event (e.g. asr_result) is sent to the frontend by
            // several listeners, each triggering the frontend triggerSemanticSimulate()
            // once -> the same sentence is spoken by TTS several times. (User report:
            // voice tab, ASR triggers TTS twice. Real-device logcat shows publish()
            // printing "5 listener(s) subscribed" continuously, but the whole session
            // has only two "WebSocket upgrade accepted" and one "closed normally" -
            // proving 3~4 connections were zombies that never read the close event.)
            //
            // Fix: set SO_TIMEOUT on this socket so in.read() does not block forever; on
            // timeout, send a WebSocket ping frame to probe whether the connection is
            // still alive. After two consecutive timeouts (i.e. idle for a total of
            // 2 x IDLE_TIMEOUT_MS) with nothing received (not even a pong), consider it
            // dead and close it actively, so the unsubscribe() in finally is guaranteed
            // to run.
            socket.setSoTimeout(IDLE_TIMEOUT_MS);

            final Connection conn = new Connection(socket, out);
            Log.i(TAG, "WebSocket upgrade accepted (remote=" + socket.getRemoteSocketAddress() + ")");
            EventBus.Listener listener = new EventBus.Listener() {
                @Override
                public void onEvent(String line) {
                    conn.sendText(line);
                }
            };
            EventBus.get().subscribe(listener);
            try {
                conn.sendText("{\"type\":\"connected\",\"bootId\":\"" + BOOT_ID
                        + "\",\"time\":\"\",\"data\":{\"msg\":\"ws connected\"}}");
                long startMs = System.currentTimeMillis();
                conn.readLoop(rawIn);
                Log.i(TAG, "WebSocket closed normally after " + (System.currentTimeMillis() - startMs) + "ms"
                        + " (listeners now " + (EventBus.get().listenerCount() - 1) + ")");
            } finally {
                EventBus.get().unsubscribe(listener);
            }
        } catch (IOException e) {
            Log.i(TAG, "WebSocket connection closed: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String computeAccept(String clientKey) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((clientKey + GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    /** One live WebSocket connection: owns the raw frame I/O for its lifetime. */
    private static final class Connection {
        private final Socket socket;
        private final OutputStream out;
        private volatile boolean open = true;

        Connection(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        /** Reads client frames until close/EOF. The panel never sends meaningful data
         *  frames back, but we must still parse and discard them (and answer pings)
         *  to keep the connection and buffers correct. */
        void readLoop(InputStream in) throws IOException {
            int consecutiveTimeouts = 0;
            while (open) {
                int b0;
                try {
                    b0 = in.read();
                } catch (java.net.SocketTimeoutException e) {
                    // SO_TIMEOUT fired - this does not necessarily mean the connection
                    // is dead (it may simply be a normal idle state with no event to
                    // send and no ping from the browser); probe by sending a ping. If
                    // the connection is still alive, the browser's WebSocket
                    // implementation answers a pong automatically, and the next loop
                    // iteration receives it below and resets the counter. Only when
                    // MAX_CONSECUTIVE_TIMEOUTS consecutive timeouts get no response at
                    // all is it considered dead.
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                        Log.i(TAG, "WebSocket idle timeout x" + consecutiveTimeouts
                                + " with no response - treating as dead connection");
                        return;
                    }
                    try {
                        sendFrame((byte) 0x89, new byte[0]); // ping
                    } catch (IOException sendFailed) {
                        // Even the send failed: this connection is definitely dead; no need to wait for the next round of timeouts.
                        return;
                    }
                    continue;
                }
                if (b0 == -1) return;
                consecutiveTimeouts = 0;
                int b1 = in.read();
                if (b1 == -1) return;

                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;

                // Fixed 2026-08: the in.read() calls in these two branches previously
                // did not check -1 (EOF) at all - if the connection dropped exactly
                // while reading the 16-bit/64-bit length, `-1 & 0xFF` would become 255,
                // silently yielding a wrong length value instead of revealing the EOF,
                // and the code could then read the payload with a garbage length,
                // possibly hanging or reading garbage data. Now readByteOrThrow() is
                // used; on hitting EOF it throws IOException immediately, which the
                // existing catch (IOException e) at the handleUpgrade() level catches,
                // ending this loop exactly like any ordinary connection drop.
                if (len == 126) {
                    len = (readByteOrThrow(in) << 8) | readByteOrThrow(in);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | readByteOrThrow(in);
                    }
                }

                // Added 2026-08: there was previously no upper-bound check on len here -
                // a malicious client could send a frame header with opcode=127
                // (64-bit length) claiming a payload of several GB; `new byte[(int) len]`
                // would instantly throw OutOfMemoryError on this pool thread even when
                // the int cast of len does not overflow (Error, cannot be caught). This
                // panel never expects the browser to send any meaningful data frame back
                // (see the class javadoc above), so the limit can be as conservative as
                // we like - 1MB already far exceeds any inbound frame this panel will
                // use (ping/pong payloads are usually only a few bytes).
                final long MAX_FRAME_PAYLOAD_BYTES = 1024 * 1024;
                if (len < 0 || len > MAX_FRAME_PAYLOAD_BYTES) {
                    Log.w(TAG, "Rejecting WebSocket frame with payload length " + len
                            + " (limit " + MAX_FRAME_PAYLOAD_BYTES + ")");
                    return;
                }

                byte[] mask = new byte[4];
                if (masked) {
                    int read = 0;
                    while (read < 4) {
                        int n = in.read(mask, read, 4 - read);
                        if (n < 0) return;
                        read += n;
                    }
                }

                byte[] payload = new byte[(int) len];
                int read = 0;
                while (read < len) {
                    int n = in.read(payload, read, (int) len - read);
                    if (n < 0) return;
                    read += n;
                }
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }

                switch (opcode) {
                    case 0x8: // close
                        open = false;
                        return;
                    case 0x9: // ping -> pong
                        sendFrame((byte) 0x8A, payload);
                        break;
                    default:
                        // text/binary/continuation from the client: not used by this panel.
                        break;
                }
                if (!fin) {
                    // Fragmented frames from the client aren't expected from this panel's
                    // JS; ignore continuation complexity rather than mis-parse it.
                }
            }
        }

        /** Reads exactly one byte, throwing IOException on EOF instead of silently
         *  returning -1 (which, if used directly in a bit-shift expression, becomes
         *  0xFF and looks like valid data rather than a closed connection). */
        private static int readByteOrThrow(InputStream in) throws IOException {
            int b = in.read();
            if (b == -1) {
                throw new IOException("Unexpected EOF while reading WebSocket frame header");
            }
            return b;
        }

        void sendText(String text) {
            if (!open) return;
            try {
                byte[] payload = text.getBytes(StandardCharsets.UTF_8);
                sendFrame((byte) 0x81, payload); // FIN + text opcode, unmasked (server->client)
            } catch (IOException e) {
                open = false;
            }
        }

        private synchronized void sendFrame(byte firstByte, byte[] payload) throws IOException {
            int len = payload.length;
            out.write(firstByte);
            if (len < 126) {
                out.write(len);
            } else if (len <= 0xFFFF) {
                out.write(126);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) ((len >> (8 * i)) & 0xFF));
                }
            }
            out.write(payload);
            out.flush();
        }
    }
}

