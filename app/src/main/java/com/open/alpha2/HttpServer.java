package com.open.alpha2;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A minimal, dependency-free HTTP/1.1 server built directly on java.net.ServerSocket -
 * deliberately no NanoHTTPD or other library, to match the SDK's own "Android framework +
 * JDK only" dependency policy.
 *
 * Responsibilities:
 *  - Serves static files (the HTML/JS/CSS control panel) out of assets/web/.
 *  - Dispatches "/api/*" requests to a pluggable {@link ApiHandler} (implemented by
 *    MainActivity, which owns the Alpha2RobotApi instance).
 *  - Detects a WebSocket upgrade request on "/ws" and hands the raw socket off to
 *    {@link WebSocketServer} for the RFC 6455 handshake and framing.
 *  - Dispatches "/stream/*" requests to a pluggable {@link StreamHandler} that owns the
 *    raw socket for the connection's whole lifetime (used by the MJPEG camera feed,
 *    which is a single long-lived response, not a request/response pair).
 *
 * One thread-per-connection via a cached thread pool; traffic on this panel is
 * low-volume/interactive so this is simpler and more robust than NIO here. A streaming
 * connection just holds one pool thread for as long as the client stays connected -
 * fine at this panel's expected scale of a handful of browser tabs, not fine at
 * internet scale, which is out of scope for a LAN test tool.
 */
public class HttpServer implements Runnable {
    private static final String TAG = "HttpServer";
    public static final int PORT = 8888;

    /**
     * For endpoints that need the raw POST body bytes rather than a UTF-8-decoded
     * String - currently just the walkie-talkie audio upload, where the body is
     * arbitrary binary PCM and UTF-8 decoding (as ApiHandler.handle()'s body param
     * does) would corrupt byte sequences that aren't valid UTF-8. Returns the
     * ApiResponse to send back, same as ApiHandler.
     */
    public interface RawUploadHandler {
        ApiResponse handle(String path, Map<String, String> query, byte[] body,
                Map<String, String> headers);
    }

    /** Implemented by MainActivity to answer "/api/..." calls with a JSON (or plain) body. */
    public interface ApiHandler {
        /** Returns the raw response body; contentType should usually be "application/json". */
        ApiResponse handle(String path, Map<String, String> query, String method, String body,
                Map<String, String> headers);
    }

    /**
     * Implemented by MainActivity to serve "/stream/..." requests that don't fit the
     * single-response ApiHandler model - the handler receives the still-open socket and
     * owns writing to it (headers included) until the client disconnects or the stream
     * ends on its own. Mirrors how WebSocketServer.handleUpgrade() owns the socket for
     * "/ws", just without the WebSocket framing/handshake.
     */
    public interface StreamHandler {
        void handle(String path, Map<String, String> query, java.net.Socket socket,
                Map<String, String> headers) throws IOException;
    }

    /**
     * Gate for the "/ws" upgrade (HttpServer itself has no Context so it cannot
     * consult PanelAuth directly — MainActivity provides the check, same
     * requireAuth logic as /api/*; returns null to allow, 401 ApiResponse to
     * reject with a plain-HTTP error before the 101 handshake).
     * Browser WebSocket cannot set custom headers, so the check accepts the
     * query {@code panel_token} fallback (see PanelAuth).
     */
    public interface WsAuthGate {
        ApiResponse check(String path, Map<String, String> query, Map<String, String> headers);
    }

    public static final class ApiResponse {
        public final int status;
        public final String contentType;
        public final String body;
        /** Extra response headers (e.g. WWW-Authenticate on 401); null = none. */
        public final Map<String, String> headers;

        public ApiResponse(int status, String contentType, String body) {
            this(status, contentType, body, null);
        }

        public ApiResponse(int status, String contentType, String body,
                Map<String, String> headers) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.headers = headers;
        }

        public static ApiResponse ok(String json) {
            return new ApiResponse(200, "application/json; charset=utf-8", json);
        }

        /** {"ok":true} shortcut (previously 30+ call sites wrote the full literal; now the single source JsonUtil.okTrue). */
        public static ApiResponse okTrue() {
            return ok(JsonUtil.okTrue());
        }

        /** {"ok":<bool>} shortcut (the dynamic version of okTrue; previously 6 call sites built the string). */
        public static ApiResponse okBool(boolean ok) {
            return ok("{\"ok\":" + ok + "}");
        }

        private static ApiResponse of(int status, String message) {
            return new ApiResponse(status, "application/json; charset=utf-8",
                    JsonUtil.okFalse(String.valueOf(message)));
        }

        public static ApiResponse error(String message) {
            return of(500, message);
        }

        public static ApiResponse badRequest(String message) {
            return of(400, message);
        }

        /** For the panel token gate (see PanelAuth): a missing or wrong token is 401, not 400/500.
         *  Adds WWW-Authenticate: Bearer realm="panel" (standard 401 semantics, so curl/Swagger
         *  know to send Authorization: Bearer). */
        public static ApiResponse unauthorized(String message) {
            Map<String, String> h = new HashMap<String, String>();
            h.put("WWW-Authenticate", "Bearer realm=\"panel\"");
            return new ApiResponse(401, "application/json; charset=utf-8",
                    JsonUtil.okFalse(String.valueOf(message)), h);
        }

        /** The single escaping implementation lives in JsonUtil (the cycle the old comment worried about no longer exists: JsonUtil has zero dependencies). */
        private static String esc(String s) {
            return JsonUtil.esc(s);
        }
    }

    private final AssetManager assets;
    private final ApiHandler apiHandler;
    private final StreamHandler streamHandler;
    private final RawUploadHandler rawUploadHandler;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;
    private volatile boolean running = false;
    private volatile IOException bindError;
    private final CountDownLatch bindLatch = new CountDownLatch(1);
    /** Gate before the /ws upgrade (MainActivity injects the PanelAuth check; null = no gate, backward compatible). */
    private volatile WsAuthGate wsAuthGate;

    /** Injected by MainActivity before start() (see PanelAuth.requireAuth, the same logic as /api/*). */
    public void setWsAuthGate(WsAuthGate gate) {
        this.wsAuthGate = gate;
    }

    /**
     * TLS is removed; plain HTTP only. Browsers kept rejecting the self-signed cert,
     * and the walkie-talkie mic is permanently off in the UI, so TLS is no longer needed.
     * This is now the only constructor - plain HTTP only.
     */
    public HttpServer(AssetManager assets, ApiHandler apiHandler, StreamHandler streamHandler,
            RawUploadHandler rawUploadHandler) {
        this.assets = assets;
        this.apiHandler = apiHandler;
        this.streamHandler = streamHandler;
        this.rawUploadHandler = rawUploadHandler;
    }

    /**
     * Blocks until the listening socket is actually bound (or failed to bind) before
     * returning, instead of just submitting the accept-loop to the thread pool and
     * returning immediately. MainActivity's on-device WebView calls loadUrl() on
     * 127.0.0.1 right after this returns - without this wait, that first page load can
     * race the ServerSocket constructor running on the pool thread and fail with
     * connection-refused, especially on a slower first boot.
     */
    public void start() {
        running = true;
        pool.execute(this);
        try {
            bindLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (bindError != null) {
            Log.e(TAG, "Server failed to bind port " + PORT, bindError);
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(PORT);
            Log.i(TAG, "Listening on port " + PORT + " (plain HTTP)");
            bindLatch.countDown();
            while (running) {
                final Socket client = serverSocket.accept();
                final String remoteAddr = String.valueOf(client.getRemoteSocketAddress());
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client, remoteAddr);
                    }
                });
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "Server socket error", e);
            }
            bindError = e;
            bindLatch.countDown(); // unblock start() even on failure - don't hang the caller
        }
    }
    private void handleClient(Socket socket, String remoteAddr) {
        try {
            socket.setTcpNoDelay(true);
            // Keep-alive loop: serve as many requests as the client sends on this same
            // TCP connection instead of closing after one. Loop ends when the client sends "Connection:
            // close", disconnects, or a request can't be parsed.
            boolean keepAlive = true;
            while (keepAlive) {
                keepAlive = handleOneRequest(socket);
            }
        } catch (Exception e) {
            // The exception is fully caught here and
            // the socket is always closed in the finally block below regardless, so
            // this does not leak threads or sockets. remoteAddr is captured by the caller immediately after accept()
            // (see run()).
            Log.e(TAG, "handleClient error (remote=" + remoteAddr + ")", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Serves exactly one HTTP request off an already-open
     *  socket. Returns true if the connection should stay open for another
     *  request (keep-alive), false if it should be closed - either because the client
     *  asked for that (Connection: close, or an HTTP/1.0 request with no keep-alive
     *  header), a WebSocket upgrade/stream/upload response already owns or closed the
     *  socket itself, or the client disconnected (readHttpLine returned null: no bytes
     *  at all for a fresh request line, i.e. a clean EOF between requests). */
    private boolean handleOneRequest(Socket socket) throws IOException {
        // Deliberately NOT wrapped in a BufferedReader: BufferedReader.readLine() fills
        // its own internal buffer eagerly and can silently consume bytes past the header
        // block (e.g. the client's first WebSocket frame, sent right after the upgrade
        // handshake). Since WebSocketServer needs the *exact* stream position where the
        // headers end, headers are read one byte at a time directly off the raw socket
        // stream instead.
        InputStream rawIn = socket.getInputStream();

        String requestLine = readHttpLine(rawIn);
        if (requestLine == null || requestLine.isEmpty()) {
            return false; // clean EOF between requests, or an empty line - close.
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return false;
        }
        String method = parts[0];
        String fullPath = parts[1];

        Map<String, String> headers = new HashMap<>();
        String line;
        // Header line cap of 100.
        int headerLines = 0;
        while ((line = readHttpLine(rawIn, 8192)) != null && !line.isEmpty()) {
            if (++headerLines > 100) return false;
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(),
                        line.substring(idx + 1).trim());
            }
        }

        // The client can explicitly ask this particular connection to close after this
        // response (still fully valid HTTP/1.1); honour that instead of always looping.
        String connectionHeader = headers.get("connection");
        boolean clientWantsClose = connectionHeader != null && connectionHeader.equalsIgnoreCase("close");

        String path = fullPath;
        String queryString = "";
        int qIdx = fullPath.indexOf('?');
        if (qIdx >= 0) {
            path = fullPath.substring(0, qIdx);
            queryString = fullPath.substring(qIdx + 1);
        }
        Map<String, String> query = parseQuery(queryString);

        // WebSocket upgrade goes to a completely separate framed protocol. rawIn is
        // guaranteed to be positioned exactly at the first byte after the blank line
        // terminating the headers, since nothing above touched a BufferedReader.
        String upgrade = headers.get("upgrade");
        if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
            if (wsAuthGate != null) {
                ApiResponse gate;
                try {
                    gate = wsAuthGate.check(path, query, headers);
                } catch (Exception e) {
                    Log.e(TAG, "WS auth gate threw", e);
                    gate = ApiResponse.error(String.valueOf(e.getMessage()));
                }
                if (gate != null) {
                    Log.i(TAG, "WS rejected [" + gate.status + "]: " + path);
                    OutputStream wsOut = socket.getOutputStream();
                    writeResponse(wsOut, gate, false);
                    return false;
                }
            }
            WebSocketServer.handleUpgrade(socket, rawIn, headers);
            return false; // WebSocketServer owns the socket lifecycle from here on.
        }

        // Streaming responses (MJPEG camera feed) own the socket from here on, same
        // as the WebSocket upgrade above - no Content-Length response is possible
        // for a feed of unknown/infinite length, so this can't go through the normal
        // ApiResponse/writeResponse path at all.
        if (path.startsWith("/stream/") && streamHandler != null) {
            Log.i(TAG, "Stream request: " + method + " " + path);
            try {
                streamHandler.handle(path.substring(8), query, socket, headers);
            } catch (IOException e) {
                // Expected, routine noise, not a bug: fires every time a browser tab
                // navigates away, refreshes, or is closed while an MJPEG/mic stream is
                // still writing to it (typically surfaces as "Broken pipe"/EPIPE) -
                // confirmed from logcat_2026-07-27 that the stream handler's own
                // finally block (see handleCameraStream()/handleMicStream()) already
                // unsubscribes and releases the camera/mic correctly whenever this
                // fires, so there is nothing left to clean up here. Logged at debug
                // rather than info so it doesn't read like a warning worth chasing.
                Log.d(TAG, "Stream connection closed: " + e.getMessage());
            }
            return false; // streamHandler owns the socket lifecycle from here on.
        }

        byte[] rawBody = readBody(rawIn, headers, path);
        if (rawBody == null) return false;

        OutputStream out = socket.getOutputStream();

        // CORS preflight: the Bearer header makes cross-origin /api a non-simple request, so the
        // browser sends an OPTIONS probe first - before there was no header so this was ignored,
        // now answer 200 + Allow-Headers, otherwise a third-party cross-origin panel with an
        // Authorization header breaks entirely (the on-device panel is same-origin, unaffected).
        if ("OPTIONS".equalsIgnoreCase(method)) {
            Map<String, String> h = new HashMap<String, String>();
            h.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            h.put("Access-Control-Allow-Headers", "Authorization, Content-Type");
            h.put("Access-Control-Max-Age", "86400");
            writeResponse(out, new ApiResponse(200, "text/plain; charset=utf-8", "", h),
                    !clientWantsClose);
            return !clientWantsClose;
        }

        if (path.startsWith("/upload/") && rawUploadHandler != null) {
            Log.i(TAG, "Upload request: " + method + " " + path + " (" + rawBody.length + " bytes)");
            ApiResponse resp;
            try {
                resp = rawUploadHandler.handle(path.substring(8), query, rawBody, headers);
            } catch (Exception e) {
                Log.e(TAG, "Upload handler threw", e);
                resp = ApiResponse.error("Upload handler error: " + e.getMessage());
            }
            writeResponse(out, resp, !clientWantsClose);
            return !clientWantsClose;
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);

        if (path.startsWith("/api/")) {
            // Log only the first 200 chars of the response body (ssid/uuid/vision token must not land in logcat).
            Log.i(TAG, "API request: " + method + " " + path + (queryString.isEmpty() ? "" : "?" + redactQuery(queryString)));
            ApiResponse resp;
            try {
                resp = apiHandler.handle(path.substring(5), query, method, body, headers);
                String bodyLog = resp.body != null && resp.body.length() > 200
                        ? resp.body.substring(0, 200) + "…(" + resp.body.length() + "B)" : resp.body;
                Log.i(TAG, "API response [" + resp.status + "]: " + path + " -> " + bodyLog);
            } catch (IllegalArgumentException e) {
                // OpenAPI validation failure -> 400, not 500 (the parameter errors thrown by ApiValidator)
                Log.w(TAG, "API bad request for " + path + ": " + e.getMessage());
                resp = ApiResponse.badRequest(String.valueOf(e.getMessage()));
            } catch (Exception e) {
                Log.e(TAG, "API handler error for " + path, e);
                resp = ApiResponse.error(String.valueOf(e.getMessage()));
            }
            writeResponse(out, resp, !clientWantsClose);
        } else {
            serveStatic(out, path, !clientWantsClose);
        }
        return !clientWantsClose;
    }

    private void serveStatic(OutputStream out, String path, boolean keepAlive) throws IOException {
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        // Block ".." (unverified whether AssetManager normalizes; do not take the risk).
        if (path.contains("..")) {
            byte[] msg = "Not found".getBytes(StandardCharsets.UTF_8);
            writeResponse(out, 404, "text/plain; charset=utf-8", msg, keepAlive, true);
            return;
        }
        // 1+2: handle directory index for /docs and /.well-known alias (dotfiles are stripped by aapt, so fallback to well-known without dot)
        if (path.equals("/docs") || path.equals("/docs/")) {
            path = "/docs/index.html";
        }
        if (path.startsWith("/.well-known/")) {
            // dotfiles are stripped by aapt: try the dot path first, fall back to the dotless version on a miss, done in one open.
            String fallback = "web/well-known" + path.substring("/.well-known".length());
            try {
                byte[] wellKnown = readAsset(fallback);
                if (wellKnown == null) wellKnown = readAsset("web" + path);
                if (wellKnown != null) {
                    writeResponse(out, 200, mimeType(path), wellKnown, keepAlive, true);
                    return;
                }
            } catch (IOException ignored) {}
        }
        String assetPath = "web" + path;
        try {
            byte[] body = readAsset(assetPath);
            if (body == null) throw new IOException("not found: " + assetPath);
            // No Cache-Control header used to be sent at all, which left every browser/
            // WebView free to apply its own heuristic caching to these .js/.css/.html
            // files - user-confirmed symptom: after updating this web UI (new APK
            // install), the browser kept serving an old cached copy of app-log.js, so
            // WebSocket events the new backend genuinely sent (visible in the raw event
            // log, which is itself driven by JS that happened to be unchanged) never
            // reached the *new* per-servo readout handler because that handler's code
            // wasn't in the stale cached file yet.
            // This control panel is always served fresh off the device's own assets/
            // (never a CDN, never meant to be offline-cached), so there is no upside to
            // caching it and real downside (silently stale UI logic after every
            // update) - explicitly forbid caching for every static response.
            writeResponse(out, 200, mimeType(path), body, keepAlive, true);
        } catch (IOException notFound) {
            byte[] msg = ("Not found: " + path).getBytes(StandardCharsets.UTF_8);
            writeResponse(out, 404, "text/plain; charset=utf-8", msg, keepAlive, true);
        }
    }

    /** Read an asset in one pass (a missing asset returns null, no exception, to ease the fallback chain). */
    private byte[] readAsset(String assetPath) {
        InputStream is = null;
        try {
            is = assets.open(assetPath);
            return IOUtil.readAllBytes(is);
        } catch (IOException e) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** Read the body (with a limit): a normal body returns byte[], a broken header or an over-limit length returns null = close the connection. */
    private static byte[] readBody(InputStream rawIn, Map<String, String> headers, String path)
            throws IOException {
        String lenStr = headers.get("content-length");
        if (lenStr == null) return new byte[0];
        int len;
        try {
            len = Integer.parseInt(lenStr.trim());
        } catch (NumberFormatException e) {
            // Malformed Content-Length header - can't trust anything about the
            // body that follows (or even how much of it there is), so close the
            // connection instead of trying to guess/recover.
            return null;
        }
        // Body limit: len comes from the client Content-Length; without a limit this OOMs. The
        // largest normal body is a /upload/audio walkie-talkie PCM chunk (tens of KB), so 32MB
        // leaves plenty of margin. The chest firmware only accepts a <=112KB app micro-image;
        // /upload/chest still gets 1MB (its dedicated limit) so a wrongly sent 256KB full image
        // reaches handleChestUpload for an explicit 400 reason instead of a silent drop here.
        final int maxBody = path.startsWith("/upload/chest") ? (1024 * 1024) : (32 * 1024 * 1024);
        if (len < 0 || len > maxBody) {
            Log.w(TAG, "Rejecting request with Content-Length=" + len + " (limit " + maxBody + ")");
            return null;
        }
        if (len == 0) return new byte[0];
        return IOUtil.readExactly(rawIn, len);
    }

    private static final Map<String, String> MIME = new HashMap<String, String>();
    static {
        MIME.put(".html", "text/html; charset=utf-8");
        MIME.put(".js", "application/javascript; charset=utf-8");
        MIME.put(".css", "text/css; charset=utf-8");
        MIME.put(".json", "application/json; charset=utf-8");
        MIME.put(".yml", "text/yaml; charset=utf-8");
        MIME.put(".yaml", "text/yaml; charset=utf-8");
        MIME.put(".png", "image/png");
        MIME.put(".svg", "image/svg+xml");
    }

    private static final Map<Integer, String> STATUS_TEXT = new HashMap<Integer, String>();
    static {
        STATUS_TEXT.put(200, "OK");
        STATUS_TEXT.put(400, "Bad Request");
        STATUS_TEXT.put(401, "Unauthorized");
        STATUS_TEXT.put(404, "Not Found");
        STATUS_TEXT.put(409, "Conflict");
        STATUS_TEXT.put(500, "Internal Server Error");
        STATUS_TEXT.put(503, "Service Unavailable");
    }

    private static String mimeType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            String t = MIME.get(path.substring(dot));
            if (t != null) return t;
        }
        return "application/octet-stream";
    }

    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body, boolean keepAlive)
            throws IOException {
        writeResponse(out, status, contentType, body, keepAlive, false);
    }

    /** ApiResponse version: also writes the extra headers (a 401 auto-adds WWW-Authenticate, guarding against a missed write). */
    static void writeResponse(OutputStream out, ApiResponse resp, boolean keepAlive)
            throws IOException {
        writeResponse(out, resp, keepAlive, false);
    }

    static void writeResponse(OutputStream out, ApiResponse resp, boolean keepAlive, boolean noCache)
            throws IOException {
        byte[] body = resp.body != null ? resp.body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        String statusText = STATUS_TEXT.get(resp.status);
        if (statusText == null) statusText = "Error";
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(resp.status).append(' ').append(statusText).append("\r\n");
        header.append("Content-Type: ").append(resp.contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        boolean hasWwwAuth = false;
        if (resp.headers != null) {
            for (Map.Entry<String, String> e : resp.headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if ("WWW-Authenticate".equalsIgnoreCase(e.getKey())) hasWwwAuth = true;
                header.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        if (resp.status == 401 && !hasWwwAuth) {
            header.append("WWW-Authenticate: Bearer realm=\"panel\"\r\n");
        }
        if (noCache) {
            header.append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n");
            header.append("Pragma: no-cache\r\n");
        }
        header.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body, boolean keepAlive, boolean noCache)
            throws IOException {
        String statusText = STATUS_TEXT.get(status);
        if (statusText == null) statusText = "Error";
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        if (noCache) {
            // See serveStatic()'s javadoc comment above for why - covers index.html and
            // every app-*.js/style.css it references, plus the 404 body for completeness.
            header.append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n");
            header.append("Pragma: no-cache\r\n");
        }
        header.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /**
     * Reads one CRLF- or LF-terminated line directly off the raw socket stream, one byte
     *  at a time, without any internal read-ahead buffering. Slower than BufferedReader but
     *  leaves the stream positioned exactly where the line ended - required so a following
     *  WebSocket upgrade sees the correct first frame byte. Returns null on EOF with no data
     *  read yet.
     */
    private static String readHttpLine(InputStream in) throws IOException {
        return readHttpLine(in, Integer.MAX_VALUE);
    }

    private static String readHttpLine(InputStream in, int maxChars) throws IOException {
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(128);
        int b;
        boolean sawAny = false;
        while ((b = in.read()) != -1) {
            sawAny = true;
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                if (lineBuf.size() < maxChars) lineBuf.write(b);
            }
        }
        if (!sawAny) {
            return null;
        }
        return new String(lineBuf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** Query redaction for logs: token/uuid/value (xiaozhi token, UUID writes) etc. must not land in logcat. */
    private static String redactQuery(String qs) {
        if (qs == null || qs.isEmpty()) return qs;
        StringBuilder sb = new StringBuilder();
        for (String pair : qs.split("&")) {
            if (sb.length() > 0) sb.append('&');
            int idx = pair.indexOf('=');
            String k = idx >= 0 ? pair.substring(0, idx) : pair;
            String kl = k.toLowerCase(java.util.Locale.US);
            if (kl.contains("token") || kl.contains("uuid") || kl.equals("value")
                    || kl.contains("secret") || kl.contains("pass") || kl.contains("psk")
                    // Experiments-tab panel token: panel_token already matches via "token"; current (the old value) needs its own entry.
                    || kl.equals("current") || kl.equals("panel_token")
                    || kl.equals("url") || kl.equals("wsurl") || kl.equals("deviceid")) {
                sb.append(k).append('=').append("***");
            } else {
                sb.append(pair);
            }
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String qs) {        Map<String, String> map = new HashMap<>();
        if (qs == null || qs.isEmpty()) {
            return map;
        }
        for (String pair : qs.split("&")) {
            int idx = pair.indexOf('=');
            try {
                if (idx >= 0) {
                    String k = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String v = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    map.put(k, v);
                } else {
                    map.put(java.net.URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception e) {
                // Log one debug line for a malformed pair.
                android.util.Log.d(TAG, "parseQuery dropped pair: " + pair);
            }
        }
        return map;
    }
}


