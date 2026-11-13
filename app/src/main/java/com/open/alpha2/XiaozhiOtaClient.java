package com.open.alpha2;

import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Implements the XiaoZhi OTA/device-activation HTTP protocol - the step that
 * happens *before* XiaozhiClient's WebSocket connection, not a replacement for it.
 *
 * On a genuine ESP32 device running the official firmware, the very first thing it does
 * on boot is POST to a "check version" HTTP endpoint; the server's JSON response tells
 * the device whether it's already bound to a xiaozhi.me account, and if not, hands back
 * a short numeric "activation code" the device is expected to speak out loud so a human
 * can type it into https://xiaozhi.me/console/ to bind the device to their account. Only
 * *after* that binding completes does the server's response start including the actual
 * WebSocket url/token XiaozhiClient needs - those are never something a person types in
 * themselves; they're provisioned by this HTTP exchange.
 *
 * This class is the Android-app equivalent of xiaozhi-esp32's main/ota.cc
 * (Ota::CheckVersion() / Ota::Activate()), adapted to this project's existing
 * zero-third-party-dependency style (java.net.HttpURLConnection + org.json, matching
 * XiaozhiClient's own tooling choices) rather than pulling in OkHttp.
 *
 * Protocol reference points used to build this:
 *  - main/ota.cc / main/ota.h in github.com/78/xiaozhi-esp32 (CheckVersion() request
 *    headers and response JSON shape: firmware/mqtt/websocket/activation/server_time).
 *  - github.com/xuan2261/r1-xiaozhi's documented Android reimplementation of the same
 *    flow (POST {ota_url}/activate with serial_number/challenge/hmac, HTTP 202 while
 *    waiting for the user to enter the code on the website, HTTP 200 once bound).
 *
 * This device has no secure element / hardware HMAC key, so the "hmac" field sent with
 * the activate request is always empty - the official server is documented to accept
 * this for devices without one (there is nothing this class can do to fabricate a
 * meaningful HMAC without real key material, so sending an honestly-empty value is
 * preferable to inventing one).
 */
public class XiaozhiOtaClient {
    private static final String TAG = "XiaozhiOtaClient";

    /** Official default OTA endpoint - same one xiaozhi-esp32's firmware ships with by
     *  default (api.tenclass.net is Tenclass's, the company behind the xiaozhi.me
     *  service). Not configurable via this app's UI for now: the person operating this
     *  robot confirmed the default is correct for their xiaozhi.me account, and
     *  exposing it as a raw editable field risks someone pasting a plausible-looking
     *  but wrong URL with no way for this app to sanity-check it. */
    public static final String DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";

    private static final int HTTP_TIMEOUT_MS = 10_000;
    private static final int ACTIVATE_POLL_INTERVAL_MS = 3_000;

    /** Result of a successful CheckVersion() call. Exactly one of
     *  {needsActivation-with-code, alreadyActivated-with-websocket} is meaningfully
     *  populated, mirroring ota.cc's own branching (the "activation" JSON block is only
     *  present when the device isn't bound yet; "websocket" is only present/complete
     *  once it is). */
    public static final class CheckVersionResult {
        public final boolean needsActivation;
        public final String activationMessage; // human-readable prompt from the server
        public final String activationCode;    // the code to speak/display, e.g. "12345"
        public final String activationChallenge; // opaque token to echo back in activate()
        public final long activationTimeoutMs;
        public final String websocketUrl;   // only non-null when already activated
        public final String websocketToken; // only non-null when already activated

        private CheckVersionResult(boolean needsActivation, String activationMessage,
                                    String activationCode, String activationChallenge,
                                    long activationTimeoutMs, String websocketUrl,
                                    String websocketToken) {
            this.needsActivation = needsActivation;
            this.activationMessage = activationMessage;
            this.activationCode = activationCode;
            this.activationChallenge = activationChallenge;
            this.activationTimeoutMs = activationTimeoutMs;
            this.websocketUrl = websocketUrl;
            this.websocketToken = websocketToken;
        }
    }

    /** Result of a completed (bound) activation poll - the websocket config that only
     *  becomes available once the person has entered the code on xiaozhi.me. */
    public static final class ActivationResult {
        public final String websocketUrl;
        public final String websocketToken;
        ActivationResult(String websocketUrl, String websocketToken) {
            this.websocketUrl = websocketUrl;
            this.websocketToken = websocketToken;
        }
    }

    private final String otaUrl;
    private final String deviceId;
    private final String clientId;

    public XiaozhiOtaClient(String otaUrl, String deviceId, String clientId) {
        this.otaUrl = otaUrl;
        this.deviceId = deviceId;
        this.clientId = clientId;
    }

    /** Blocking HTTP call - run this off the main thread (matches the rest of this
     *  app's handleXiaozhiApi() endpoints, which already run on HttpServer's own pool
     *  threads, not the UI thread). */
    public CheckVersionResult checkVersion() throws IOException {
        JSONObject body = buildDeviceInfoBody();
        JSONObject response = postJson(otaUrl, body);

        JSONObject activation = response.optJSONObject("activation");
        JSONObject websocket = response.optJSONObject("websocket");

        if (activation != null) {
            return new CheckVersionResult(
                    true,
                    activation.optString("message", null),
                    activation.optString("code", null),
                    activation.optString("challenge", null),
                    activation.optLong("timeout_ms", 30_000L),
                    null, null);
        }
        if (websocket != null) {
            String url = websocket.optString("url", null);
            String token = websocket.optString("token", null);
            if (url != null && token != null) {
                return new CheckVersionResult(false, null, null, null, 0, url, token);
            }
        }
        // Server responded but included neither block - treat as a protocol-shape
        // error rather than silently returning a result the caller would misread as
        // "already activated with null credentials".
        throw new IOException("OTA check_version response had neither 'activation' nor a complete 'websocket' block: "
                + response.toString());
    }

    /** Polls the activate endpoint until the person has entered activationCode on
     *  xiaozhi.me (HTTP 200 with a websocket block), the server explicitly rejects it,
     *  or timeoutMs elapses. Blocking - same threading expectation as checkVersion().
     *
     *  HTTP semantics per the r1-xiaozhi reimplementation notes: 202 (or any
     *  non-200/non-error status) means "still waiting for the user", 200 means bound.
     *  This class treats any 4xx/5xx other than repeated timeouts as fatal rather than
     *  retrying forever, since a persistent client error (e.g. a stale/expired
     *  challenge) won't resolve itself by polling harder. */
    public ActivationResult pollActivation(String challenge, long timeoutMs,
                                            PollCallback callback) throws IOException {
        String activateUrl = otaUrl.endsWith("/") ? otaUrl + "activate" : otaUrl + "/activate";
        long deadline = System.currentTimeMillis() + timeoutMs;
        int attempt = 0;
        // 2026-08 fix: a bug confirmed on the real device. Before, an IOException
        // thrown by a single postJsonWithStatus() call (most often here,
        // java.net.SocketTimeoutException: Read timed out) was re-thrown upward
        // directly. That made pollActivation() fail right away, even though the
        // deadline (the total time the server gives for the whole pairing flow,
        // usually a few minutes, used to wait for the user to open the xiaozhi.me
        // page, log in, and type the pairing code) still had plenty of time left.
        // HTTP_TIMEOUT_MS (10 seconds), used by a single HTTP request, is a
        // reasonable timeout designed for a normal request (such as checkVersion).
        // But a brief network hiccup during polling, or a slow response from the
        // server this time (10 seconds is common, especially on a mobile network),
        // can easily trigger this timeout. The real-device logcat shows "Read timed
        // out" about 10 seconds after the pairing code appears. The user has not
        // even had time to open the xiaozhi.me site when it has already failed, and
        // the user feels the binding "does not work". Here, a temporary network
        // IOException is caught inside the loop, logged, and treated as "this round
        // got no result". Then the normal flow sleeps and tries another round. It
        // really fails only when the deadline truly arrives with still no result.
        // The 4xx/5xx fatal errors (see the result.statusCode >= 400 branch below)
        // are not affected by this change. They still fail right away, because they
        // mean the server rejected the request explicitly, and retrying does not
        // help.
        IOException lastTransientError = null;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            if (callback != null) callback.onPoll(attempt);

            JSONObject body = new JSONObject();
            try {
                body.put("challenge", challenge);
                body.put("hmac", ""); // no secure element on this hardware - see class javadoc
            } catch (JSONException e) {
                throw new IOException("failed to build activate request body", e);
            }

            PostResult result;
            try {
                result = postJsonWithStatus(activateUrl, body);
            } catch (IOException e) {
                lastTransientError = e;
                try {
                    Thread.sleep(ACTIVATE_POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while polling for activation");
                }
                continue;
            }
            if (result.statusCode == 200) {
                JSONObject websocket = result.body != null ? result.body.optJSONObject("websocket") : null;
                if (websocket != null) {
                    String url = websocket.optString("url", null);
                    String token = websocket.optString("token", null);
                    if (url != null && token != null) {
                        return new ActivationResult(url, token);
                    }
                }
                // Bound (200) but the response didn't carry websocket config - fall
                // back to a fresh checkVersion() call, which per the protocol should
                // now report already-activated with the same info.
                CheckVersionResult recheck = checkVersion();
                if (!recheck.needsActivation && recheck.websocketUrl != null) {
                    return new ActivationResult(recheck.websocketUrl, recheck.websocketToken);
                }
                throw new IOException("activate returned 200 but no websocket config was available "
                        + "(neither in the activate response nor a follow-up check_version)");
            }
            if (result.statusCode >= 400 && result.statusCode != 404) {
                // 404 is left out of the "fatal" bucket deliberately: some server
                // revisions have been observed responding 404 while a challenge is
                // still pending rather than a clean 202 - treating it as retryable
                // avoids a false-fatal abort on those. Anything else 4xx/5xx (401,
                // 400, 410 expired-challenge, 500) is treated as unrecoverable.
                throw new IOException("activation rejected by server (HTTP " + result.statusCode + "): "
                        + (result.body != null ? result.body.toString() : "<no body>"));
            }
            try {
                Thread.sleep(ACTIVATE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while polling for activation");
            }
        }
        if (lastTransientError != null) {
            throw new IOException("timed out waiting for activation (code was never entered on xiaozhi.me within "
                    + timeoutMs + "ms; last transient error: " + lastTransientError.getMessage() + ")", lastTransientError);
        }
        throw new IOException("timed out waiting for activation (code was never entered on xiaozhi.me within "
                + timeoutMs + "ms)");
    }

    public interface PollCallback {
        void onPoll(int attemptNumber);
    }

    // ---------------- HTTP plumbing ----------------

    private static final class PostResult {
        final int statusCode;
        final JSONObject body; // null if the response wasn't valid JSON (e.g. empty 202 body)
        PostResult(int statusCode, JSONObject body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private JSONObject postJson(String url, JSONObject body) throws IOException {
        PostResult result = postJsonWithStatus(url, body);
        if (result.statusCode != 200) {
            throw new IOException("OTA server returned HTTP " + result.statusCode + ": "
                    + (result.body != null ? result.body.toString() : "<no body>"));
        }
        if (result.body == null) {
            throw new IOException("OTA server returned HTTP 200 with a non-JSON/empty body");
        }
        return result.body;
    }

    private PostResult postJsonWithStatus(String urlStr, JSONObject body) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            NetLog.out("xiaozhi-ota", urlStr);
            conn = (HttpURLConnection) url.openConnection();
            // Some Android 5.1 devices ship with a factory CA store that does not
            // include the root of the certificate chain that api.tenclass.net
            // currently uses. After this openConnection() call, when the request
            // actually goes out, the code hits a CertPathValidatorException. Use
            // XiaozhiTrustAllSsl to install a trust-all SSLSocketFactory on this
            // HttpsURLConnection (see that class's javadoc). If urlStr is not
            // https:// (for example, a custom OTA URL typed as http://),
            // applyTrustAll() sees it is not an HttpsURLConnection and does nothing.
            XiaozhiTrustAllSsl.applyTrustAll(conn);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            // Headers per ota.cc / the r1-xiaozhi reimplementation notes - Device-Id is
            // this robot's persisted UUID (see MainActivity#getXiaozhiDeviceId(); real
            // ESP32 firmware uses its WiFi MAC here, but as XiaozhiClient's own javadoc
            // notes, this app already made that substitution for the WebSocket
            // handshake, and the OTA endpoint needs the *same* identifier XiaozhiClient
            // uses so the server recognizes it as the same device across both calls).
            conn.setRequestProperty("Device-Id", deviceId);
            conn.setRequestProperty("Client-Id", clientId);
            conn.setRequestProperty("Activation-Version", "2");

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseText = is != null ? readFully(is) : "";
            JSONObject responseJson = null;
            if (!responseText.trim().isEmpty()) {
                try {
                    responseJson = new JSONObject(responseText);
                } catch (JSONException e) {
                    Log.w(TAG, "Non-JSON response body from " + urlStr + " (status " + status + "): "
                            + responseText.substring(0, Math.min(200, responseText.length())));
                }
            }
            return new PostResult(status, responseJson);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Minimal device-info body for the check_version POST. Real ESP32 firmware sends
     *  a much richer payload (board.GetJson(): chip model, flash size, partition
     *  table, etc.) that this Android app has no equivalent of - the server is only
     *  documented to *require* the Device-Id/Client-Id headers for identifying which
     *  device is asking, so this keeps the body to fields this app can honestly
     *  populate rather than fabricating hardware details that don't apply. */
    private JSONObject buildDeviceInfoBody() throws IOException {
        try {
            JSONObject body = new JSONObject();
            JSONObject application = new JSONObject();
            application.put("version", "2.4");
            // "board.type"-equivalent - identifies this as the open-alpha2 project
            // rather than a genuine ESP32 board, in case the server ever wants to
            // branch on it (e.g. to skip an ESP32-specific firmware OTA offer, which
            // would be meaningless here since this app doesn't self-update this way).
            application.put("name", "open-alpha2");
            body.put("application", application);
            JSONObject board = new JSONObject();
            board.put("type", "android");
            board.put("name", "open-alpha2");
            board.put("android_sdk_int", Build.VERSION.SDK_INT);
            body.put("board", board);
            return body;
        } catch (JSONException e) {
            throw new IOException("failed to build check_version request body", e);
        }
    }
}

