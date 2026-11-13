package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Xiaozhi config layer: self-hosted OTA server / MCP switch / TTS engine / auto-connect.
 */
public final class XiaozhiConfig {
    private static final String TAG = "XiaozhiConfig";

    /** Custom Xiaozhi server config. Use PREF_XIAOZHI_OTA_URL when the switch is on.
     *  When off, use XiaozhiOtaClient.DEFAULT_OTA_URL (the official api.tenclass.net).
     *  See the "ota_config/get"/"ota_config/set" case in handleXiaozhiApi() and how
     *  runXiaozhiActivationFlow() reads this config. */
    public static final String PREF_XIAOZHI_OTA_CUSTOM_ENABLED = "xiaozhi_ota_custom_enabled";
    public static final String PREF_XIAOZHI_OTA_URL = "xiaozhi_ota_url";
    // A self-hosted server may not match the shape of the official protocol (the OTA
    // response returns the websocket url and token together). Some self-hosted setups
    // require the user to enter these values manually. All empty = keep the automatic
    // flow (the OTA response supplies the values). Filled values override the matching
    // automatic values. Read these only when PREF_XIAOZHI_OTA_CUSTOM_ENABLED is on,
    // and group them under the same "custom Xiaozhi server" switch as the OTA URL.
    public static final String PREF_XIAOZHI_WS_URL_OVERRIDE = "xiaozhi_ws_url_override";
    public static final String PREF_XIAOZHI_DEVICE_ID_OVERRIDE = "xiaozhi_device_id_override";
    public static final String PREF_XIAOZHI_TOKEN_OVERRIDE = "xiaozhi_token_override";
    // Individual enable/disable config for each MCP tool. The master switch defaults
    // to true (preserve the existing behavior - users who already use this should not
    // suddenly lose all tools when the feature ships). The disabled-tool list defaults
    // to empty (that is, all enabled). Store the comma-separated tool names in the
    // same SharedPreferences. Use the name, not the index, because the tool list
    // changes between versions. The index drifts, but the name is a stable identity.
    public static final String PREF_XIAOZHI_MCP_ENABLED = "xiaozhi_mcp_enabled";
    public static final String PREF_XIAOZHI_MCP_DISABLED_TOOLS = "xiaozhi_mcp_disabled_tools";
    // See the javadoc of getTtsEngine().
    public static final String PREF_XIAOZHI_TTS_ENGINE = "xiaozhi_tts_engine";
    /** Boot voice mode, one of three (experimental tab card, see boot_voice/get|set):
     *  off = start nothing automatically; xiaozhi = connect to Xiaozhi when the app
     *  opens; vosk = load the model and start Vosk listening when the app opens.
     *  Default off. */
    public static final String PREF_BOOT_VOICE_MODE = "boot_voice_mode";
    public static final String BOOT_VOICE_OFF = "off";
    public static final String BOOT_VOICE_XIAOZHI = "xiaozhi";
    public static final String BOOT_VOICE_VOSK = "vosk";
    /** Legacy Xiaozhi tab "auto-connect when the app opens" boolean (replaced by the
     *  three states above). Keep the key only for boot migration: if no new value is
     *  stored and the old value is true, treat it as xiaozhi and write it back. Do not
     *  read it after that. */
    public static final String PREF_XIAOZHI_AUTO_CONNECT = "xiaozhi_auto_connect";
    /** Random filler gesture at XiaoZhi TTS "start" (see XiaozhiBridge
     *  TtsStateListener). Default true keeps the old behaviour. false = the TTS
     *  start lights the mouth LED only, the robot does not move. See
     *  filler_action/get|set. */
    public static final String PREF_XIAOZHI_TTS_FILLER_ACTION_ENABLED = "xiaozhi_tts_filler_action_enabled";
    /** Push-to-talk on the chest mute key + half-duplex gate for the plain
     *  session (alpha2-brain#16). Default false = the exact behavior of build
     *  0aa89ed (release ignored, no uplink gate, no push to talk). */
    public static final String PREF_XIAOZHI_PUSH_TO_TALK_ENABLED = "xiaozhi_push_to_talk_enabled";

    /** Snapshot of the self-hosted OTA config (activation / vision reads it all at
     *  once, no per-key lookup). */
    public static final class OtaConfig {
        public final boolean customEnabled;
        public final String otaUrl;
        public final String wsUrl;
        public final String deviceId;
        public final String token;
        OtaConfig(boolean customEnabled, String otaUrl, String wsUrl, String deviceId, String token) {
            this.customEnabled = customEnabled;
            this.otaUrl = otaUrl;
            this.wsUrl = wsUrl;
            this.deviceId = deviceId;
            this.token = token;
        }
    }

    private final Context appContext;
    // TTS output engine selection for the Xiaozhi tab. "xiaozhi" (the default) keeps
    // the original behavior (the server sends opus, and XiaozhiAudioController decodes
    // and plays it). Selecting "android" mutes that opus entirely and reads the
    // Xiaozhi reply sentence by sentence with the local speech/tts (the built-in
    // Android TTS). Persist with SharedPreferences so the selection survives a reboot.
    // The old values "iflytek"/"nuance" are removed (the device no longer has those
    // two engines, and selecting one only mutes the audio). Reading an old value at
    // boot migrates it to "xiaozhi". tts_config/set rejects the old values directly.
    private volatile String xiaozhiTtsEngine = "xiaozhi";

    public XiaozhiConfig(Context context) {
        this.appContext = context.getApplicationContext();
        // Read the previously selected TTS engine. If none is stored, use the default
        // "xiaozhi" (the original behavior, no mute). The old values "iflytek"/"nuance"
        // stored by an earlier version no longer map to an engine. Migrate them all to
        // "xiaozhi" and write the result back. Otherwise the queue would go through
        // speech/tts to a dead binder and stay muted the whole time.
        xiaozhiTtsEngine = prefs().getString(PREF_XIAOZHI_TTS_ENGINE, "xiaozhi");
        if (!"xiaozhi".equals(xiaozhiTtsEngine) && !"android".equals(xiaozhiTtsEngine)) {
            Log.i(TAG, "migrate legacy xiaozhiTtsEngine " + xiaozhiTtsEngine + " -> xiaozhi");
            xiaozhiTtsEngine = "xiaozhi";
            prefs().edit().putString(PREF_XIAOZHI_TTS_ENGINE, "xiaozhi").apply();
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getTtsEngine() {
        return xiaozhiTtsEngine;
    }

    public OtaConfig getOtaConfig() {
        SharedPreferences p = prefs();
        boolean customEnabled = p.getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false);
        return new OtaConfig(
                customEnabled,
                customEnabled ? p.getString(PREF_XIAOZHI_OTA_URL, XiaozhiOtaClient.DEFAULT_OTA_URL)
                        : XiaozhiOtaClient.DEFAULT_OTA_URL,
                customEnabled ? p.getString(PREF_XIAOZHI_WS_URL_OVERRIDE, "") : "",
                customEnabled ? p.getString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, "") : "",
                customEnabled ? p.getString(PREF_XIAOZHI_TOKEN_OVERRIDE, "") : "");
    }

    public boolean isOtaCustomEnabled() {
        try {
            return prefs().getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Boot voice mode (off/xiaozhi/vosk), with migration of the old boolean. If
     *  nothing is stored, the value is off. */
    public String getBootVoiceMode() {
        try {
            String mode = prefs().getString(PREF_BOOT_VOICE_MODE, null);
            if (mode == null) {
                boolean legacy = prefs().getBoolean(PREF_XIAOZHI_AUTO_CONNECT, false);
                mode = legacy ? BOOT_VOICE_XIAOZHI : BOOT_VOICE_OFF;
                prefs().edit().putString(PREF_BOOT_VOICE_MODE, mode).apply();
            }
            if (!BOOT_VOICE_OFF.equals(mode) && !BOOT_VOICE_XIAOZHI.equals(mode)
                    && !BOOT_VOICE_VOSK.equals(mode)) {
                mode = BOOT_VOICE_OFF;
            }
            return mode;
        } catch (Exception e) {
            return BOOT_VOICE_OFF;
        }
    }

    /** XiaoZhi TTS filler gesture switch, default true. */
    public boolean isTtsFillerActionEnabled() {
        try {
            return prefs().getBoolean(PREF_XIAOZHI_TTS_FILLER_ACTION_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    /** Push-to-talk switch (alpha2-brain#16), default false. */
    public boolean isPushToTalkEnabled() {
        try {
            return prefs().getBoolean(PREF_XIAOZHI_PUSH_TO_TALK_ENABLED, false);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isAutoConnectEnabled() {
        return BOOT_VOICE_XIAOZHI.equals(getBootVoiceMode());
    }

    /** Read/write helper for MCP tool enable/disable config - a comma-separated list
     *  of disabled tool names. Both listTools()/callTool() share isMcpToolEnabled():
     *  listTools() uses it to filter which tools appear in the response. callTool()
     *  uses it to block a tool that is already disabled while the LLM still holds the
     *  old tool list and tries to call it as before. This device always exposes MCP
     *  tools to the outside (the per-item enable/disable is unchanged), and
     *  isMcpEnabled() always returns true. Keep the PREF_XIAOZHI_MCP_ENABLED pref key
     *  in the constant and in the mcp_config/set write path, without removing it,
     *  purely for compatibility with the old call style that hit the API directly
     *  through a query string. It no longer affects the actual behavior. */
    public boolean isMcpEnabled() {
        return true;
    }

    public Set<String> getMcpDisabledToolNames() {
        String csv = prefs().getString(PREF_XIAOZHI_MCP_DISABLED_TOOLS, "");
        Set<String> disabled = new HashSet<>();
        if (!csv.isEmpty()) {
            for (String name : csv.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) disabled.add(trimmed);
            }
        }
        return disabled;
    }

    public boolean isMcpToolEnabled(String toolName, Set<String> disabledNames) {
        return isMcpEnabled() && !disabledNames.contains(toolName);
    }

    public static boolean isMacShaped(String s) {
        return s != null && s.matches("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$");
    }

    public HttpServer.ApiResponse otaConfigGet() {
        SharedPreferences p = prefs();
        boolean customEnabled = p.getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false);
        String customUrl = p.getString(PREF_XIAOZHI_OTA_URL, "");
        String wsUrlOverride = p.getString(PREF_XIAOZHI_WS_URL_OVERRIDE, "");
        String deviceIdOverride = p.getString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, "");
        String tokenOverride = p.getString(PREF_XIAOZHI_TOKEN_OVERRIDE, "");
        return HttpServer.ApiResponse.ok("{\"ok\":true,"
                + "\"customEnabled\":" + customEnabled + ","
                + "\"customUrl\":\"" + MainActivity.jsonSafe(customUrl) + "\","
                + "\"defaultUrl\":\"" + MainActivity.jsonSafe(XiaozhiOtaClient.DEFAULT_OTA_URL) + "\","
                + "\"wsUrlOverride\":\"" + MainActivity.jsonSafe(wsUrlOverride) + "\","
                + "\"deviceIdOverride\":\"" + MainActivity.jsonSafe(deviceIdOverride) + "\","
                + "\"tokenOverride\":\"" + MainActivity.jsonSafe(tokenOverride) + "\"}");
    }

    public HttpServer.ApiResponse otaConfigSet(Map<String, String> query, boolean connected) {
        // A self-hosted server may not match the shape of the official protocol: open
        // three optional overrides, wsUrl/deviceId/token. Leave one empty to keep the
        // original "only the OTA URL, the rest is automatic" path.
        boolean enabled = ApiValidator.requireBoolean(query, "enabled");
        String url = ApiValidator.optionalNullable(query, "url");
        String wsUrlOverride = ApiValidator.optionalNullable(query, "wsUrl");
        String deviceIdOverride = ApiValidator.optionalNullable(query, "deviceId");
        String tokenOverride = ApiValidator.optionalNullable(query, "token");
        if (enabled) {
            if (url == null) {
                throw new IllegalArgumentException("url is required when enabled=true");
            }
            url = url.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("url must start with http:// or https://");
            }
            // A custom http URL sends the token in plain text. Keep a warning (do not
            // block: a self-hosted LAN server is reasonable).
            if (url.startsWith("http://")) {
                android.util.Log.w("XiaozhiConfig", "custom OTA url is plain http (token in plain text, trusted LAN only)");
            }
            if (wsUrlOverride != null) {
                String trimmed = wsUrlOverride.trim();
                if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) {
                    throw new IllegalArgumentException("wsUrl must start with ws:// or wss://");
                }
            }
            if (deviceIdOverride != null && !isMacShaped(deviceIdOverride.trim())) {
                throw new IllegalArgumentException(
                        "deviceId must look like a MAC address, e.g. aa:bb:cc:dd:ee:ff");
            }
            if (connected) {
                return HttpServer.ApiResponse.error("disconnect from XiaoZhi first before changing the server");
            }
        }
        SharedPreferences.Editor editor = prefs().edit();
        editor.putBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, enabled);
        if (url != null) editor.putString(PREF_XIAOZHI_OTA_URL, url);
        if (wsUrlOverride != null) editor.putString(PREF_XIAOZHI_WS_URL_OVERRIDE, wsUrlOverride.trim());
        if (deviceIdOverride != null) editor.putString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, deviceIdOverride.trim());
        if (tokenOverride != null) editor.putString(PREF_XIAOZHI_TOKEN_OVERRIDE, tokenOverride.trim());
        editor.apply();
        return HttpServer.ApiResponse.okTrue();
    }

    public HttpServer.ApiResponse mcpConfigGet() {
        Set<String> disabledNames = getMcpDisabledToolNames();
        org.json.JSONArray disabledArr = new org.json.JSONArray();
        for (String n : disabledNames) disabledArr.put(n);
        try {
            org.json.JSONObject result = new org.json.JSONObject();
            result.put("ok", true);
            result.put("mcpEnabled", isMcpEnabled());
            result.put("disabledTools", disabledArr);
            return HttpServer.ApiResponse.ok(result.toString());
        } catch (org.json.JSONException e) {
            return HttpServer.ApiResponse.error("failed to build config: " + e.getMessage());
        }
    }

    public HttpServer.ApiResponse mcpConfigSet(Map<String, String> query) {
        // Two usage forms, depending on the query parameters:
        //   ?enabled=true|false                  -> set the master switch
        //   ?tool=<name>&enabled=true|false       -> set a single tool
        String toolName = ApiValidator.optionalNullable(query, "tool");
        boolean enabled = ApiValidator.requireBoolean(query, "enabled");
        SharedPreferences.Editor mcpEditor = prefs().edit();
        if (toolName == null || toolName.isEmpty()) {
            mcpEditor.putBoolean(PREF_XIAOZHI_MCP_ENABLED, enabled);
        } else {
            Set<String> disabledNames = getMcpDisabledToolNames();
            if (enabled) {
                disabledNames.remove(toolName);
            } else {
                disabledNames.add(toolName);
            }
            mcpEditor.putString(PREF_XIAOZHI_MCP_DISABLED_TOOLS,
                    TextUtils.join(",", disabledNames));
        }
        mcpEditor.apply();
        return HttpServer.ApiResponse.okTrue();
    }

    // See the javadoc of getTtsEngine(). engine values: "xiaozhi" (the default, the
    // server sends opus for playback) | "android" (mute the opus, read with the local
    // Android TTS instead). "iflytek"/"nuance" are removed and rejected directly.
    public HttpServer.ApiResponse ttsConfigGet() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"engine\":\""
                + MainActivity.jsonSafe(xiaozhiTtsEngine) + "\"}");
    }

    public HttpServer.ApiResponse ttsConfigSet(Map<String, String> query) {
        String engine = ApiValidator.requireXiaozhiTtsEngine(query);
        xiaozhiTtsEngine = engine;
        prefs().edit().putString(PREF_XIAOZHI_TTS_ENGINE, engine).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"engine\":\"" + MainActivity.jsonSafe(engine) + "\"}");
    }

    public HttpServer.ApiResponse fillerActionGet() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":"
                + isTtsFillerActionEnabled() + "}");
    }

    public HttpServer.ApiResponse fillerActionSet(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "enabled");
        prefs().edit().putBoolean(PREF_XIAOZHI_TTS_FILLER_ACTION_ENABLED, enabled).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + "}");
    }

    public HttpServer.ApiResponse pushToTalkGet() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":"
                + isPushToTalkEnabled() + "}");
    }

    public HttpServer.ApiResponse pushToTalkSet(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "enabled");
        prefs().edit().putBoolean(PREF_XIAOZHI_PUSH_TO_TALK_ENABLED, enabled).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + "}");
    }

    public HttpServer.ApiResponse keyFeedbackGet() {
        boolean enabled;
        try {
            enabled = prefs().getBoolean(GestureCenter.PREF_KEY_EARLY_FEEDBACK, false);
        } catch (Exception e) {
            enabled = false;
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + "}");
    }

    /** Early key feedback (alpha2-brain#53): the head key tick plays before the volume
     *  binder call. Default false. */
    public HttpServer.ApiResponse keyFeedbackSet(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "enabled");
        prefs().edit().putBoolean(GestureCenter.PREF_KEY_EARLY_FEEDBACK, enabled).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + "}");
    }

    public HttpServer.ApiResponse bootVoiceGet() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"mode\":\""
                + MainActivity.jsonSafe(getBootVoiceMode()) + "\"}");
    }

    public HttpServer.ApiResponse bootVoiceSet(Map<String, String> query) {
        String mode = ApiValidator.requireBootVoiceMode(query);
        prefs().edit().putString(PREF_BOOT_VOICE_MODE, mode).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"mode\":\""
                + MainActivity.jsonSafe(mode) + "\"}");
    }
}


