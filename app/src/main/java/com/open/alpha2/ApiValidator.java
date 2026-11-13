package com.open.alpha2;

import com.ubtechinc.alpha.hardware.ChestSafety;

import java.util.Arrays;
import java.util.Map;

/**
 * A lightweight OpenAPI-driven parameter validation layer (layer 1 of the 1+2 plan).
 *
 * It corresponds to the parameters / schema definitions of {@code openapi/open-alpha2-openapi.yml},
 * and centralizes into one place the repetitive boilerplate of the old
 * {@code MainActivity.require / queryOrDefault / Integer.parseInt / enum checks}
 * that was scattered across each handle*Api case.
 *
 * Design principles:
 *  - Zero extra dependencies (only Map/String), matching this project's "Android framework + JDK only" policy (see HttpServer.java:34).
 *  - API 22 compatible (no Map.getOrDefault).
 *  - Throws IllegalArgumentException; the outer catch of handleApi converts it uniformly to {"ok":false,"error":...} (following the existing behavior of require).
 *  - No heavyweight JSON Schema validation; only what this project actually needs: required, integer, integer range, enum, boolean.
 *
 * Usage example (before vs after):
 *   // before:
 *   byte id = Byte.parseByte(require(query,"id"));
 *   int angle = Integer.parseInt(require(query,"angle"));
 *   // after:
 *   int id = ApiValidator.requireIntRange(query,"id",1,20);
 *   int angle = ApiValidator.requireInt(query,"angle");
 *
 *   // before:
 *   String preset = queryOrDefault(query,"preset","long");
 *   switch(preset){ case "flash": ... default: ...}
 *   if(!Arrays.asList("long","flash","breathe"...).contains(preset)) error
 *   // after:
 *   String preset = ApiValidator.optionalEnum(query,"preset",new String[]{"long","flash","breathe","chase","dual","stop"},"long");
 */
public final class ApiValidator {
    private ApiValidator() {}

    /** Single source of truth for the servo ranges (consistent with the openapi spec / MCP schema / McpToolsGenerated constraints):
     *  id 1-22, angle 0-255, time 20-32767ms. Previously UbxApi / ApiDispatcher /
     *  XiaozhiBridge each wrote bare literals; missing one place on a change caused a front/back-end validation mismatch. Converged here. */
    public static final int SERVO_ID_MIN = 1;
    public static final int SERVO_ID_MAX = 22;
    /** Total servo count of the whole body (length of the servo/all CSV, loop upper bound). */
    public static final int SERVO_COUNT = 22;
    /** Old 20-axis CSV length (servo/all backward compatibility: 20 = move only 1-20, 22 = move all). */
    public static final int SERVO_COUNT_LEGACY = 20;
    public static final int SERVO_ANGLE_MIN = 0;
    public static final int SERVO_ANGLE_MAX = 255;
    public static final int SERVO_TIME_MIN_MS = 20;
    public static final int SERVO_TIME_MAX_MS = 32767;

    /** Per-axis travel + origin (exactly the same as SERVO_CALIBRATION in app-core.js, measured on the real device;
     *  when changing one side, remember to change the other - the servo tab slider and the gamepad jog share the same set,
     *  and nobody may push a servo past the mechanical limit).
     *  index 0 = servo number 1; an unknown axis returns the 0-255 wire range (fail-safe). */
    private static final int[] SERVO_CAL_MIN = {
            5, 50, 55, 5, 30, 55, 100, 20, 35, 35,
            100, 40, 20, 10, 25, 50, 95, 95, 75, 105,
            5, 5};
    private static final int[] SERVO_CAL_MAX = {
            235, 210, 185, 235, 190, 185, 200, 220, 230, 215,
            190, 140, 220, 205, 205, 140, 125, 125, 165, 155,
            235, 235};
    private static final int[] SERVO_CAL_HOME = {
            120, 120, 120, 120, 120, 120, 120, 65, 145, 140,
            120, 120, 175, 95, 100, 120, 120, 120, 120, 120,
            120, 120};

    public static int servoCalMin(int id) {
        if (id < SERVO_ID_MIN || id > SERVO_ID_MAX) return SERVO_ANGLE_MIN;
        return SERVO_CAL_MIN[id - 1];
    }

    public static int servoCalMax(int id) {
        if (id < SERVO_ID_MIN || id > SERVO_ID_MAX) return SERVO_ANGLE_MAX;
        return SERVO_CAL_MAX[id - 1];
    }

    public static int servoCalHome(int id) {
        if (id < SERVO_ID_MIN || id > SERVO_ID_MAX) return 128;
        return SERVO_CAL_HOME[id - 1];
    }

    /** Calibration clamp (an unknown axis returns the 0-255 wire clamp; consistent with clampServoAngle in app-core.js). */
    public static int clampServoAngle(int id, int value) {
        int lo = servoCalMin(id);
        int hi = servoCalMax(id);
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    /** Single source of truth for the LED ranges (consistent with the openapi spec / MCP schema / McpToolsGenerated constraints):
     *  color 1-7, brightness 1-9, mouth speed 100-1000ms. Previously HTTP (requireColor
     *  etc.) and MCP (LedCenter mcpLedSet*) each wrote bare literals / did not validate at all; an out-of-range MCP value
     *  silently fell into JNI - converged here, one set on both sides.
     *  Note: TTS/disco call breathing(0) (fastest) directly inside, bypassing this validation - 0 is
     *  an internal reserved value; the public API (HTTP/MCP/UI slider) is always 100-1000. */
    public static final int LED_COLOR_MIN = 1;
    public static final int LED_COLOR_MAX = 7;
    public static final int LED_BRIGHTNESS_MIN = 1;
    public static final int LED_BRIGHTNESS_MAX = 9;
    public static final int LED_MOUTH_SPEED_MIN_MS = 100;

    public static final int LED_MOUTH_SPEED_MAX_MS = 1000;
    // ── Internal shared parsing (all trim; message shape unchanged) ─────────────────────
    private static int parseIntOrThrow(String key, String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
    }

    private static long parseLongOrThrow(String key, String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
    }

    private static int rangeCheck(String key, int v, int min, int max) {
        if (v < min || v > max) {
            throw new IllegalArgumentException("parameter '" + key + "' must be between " + min + " and " + max + ", got: " + v);
        }
        return v;
    }

    private static float parseFloatOrThrow(String key, String v) {
        try {
            return Float.parseFloat(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be a number, got: " + v);
        }
    }

    private static double parseDoubleOrThrow(String key, String v) {
        try {
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be a number, got: " + v);
        }
    }

    private static Boolean parseBoolOrNull(String key, String v) {
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v) || "0".equals(v)) return Boolean.FALSE;
        throw new IllegalArgumentException("parameter '" + key + "' must be true/false, got: " + v);
    }

    private static String checkEnumOrThrow(String key, String v, String[] allowed) {
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException("parameter '" + key + "' must be one of " + Arrays.toString(allowed) + ", got: " + v);
    }

    // ── Basic ────────────────────────────────────────────────────────
    public static String require(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required parameter: " + key);
        }
        return v;
    }

    public static String optional(Map<String, String> q, String key, String defaultValue) {
        String v = q.get(key);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    // ── Integer ────────────────────────────────────────────────────────
    // All trim (a URL passing " 90 " should not 400).
    public static int requireInt(Map<String, String> q, String key) {
        return parseIntOrThrow(key, require(q, key));
    }

    public static int optionalInt(Map<String, String> q, String key, int defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseIntOrThrow(key, v);
    }

    public static int requireIntRange(Map<String, String> q, String key, int min, int max) {
        return rangeCheck(key, requireInt(q, key), min, max);
    }

    /** Optional integer tri-state (absent/empty string returns null; a value out of range is a 400; used for the camera w/h pair). */
    public static Integer optionalIntegerRange(Map<String, String> q, String key, int min, int max) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        return Integer.valueOf(rangeCheck(key, parseIntOrThrow(key, v), min, max));
    }

    public static int optionalIntRange(Map<String, String> q, String key, int min, int max, int defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return rangeCheck(key, parseIntOrThrow(key, v), min, max);
    }

    /** Range check for a floating-point parameter. min/max use double: float ranges are usually decimals (ratios, gains, thresholds),
     *  not integers. */
    public static float optionalFloatRange(Map<String, String> q, String key,
                                           double min, double max, float defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        float f = parseFloatOrThrow(key, v);
        if (f < min || f > max) {
            throw new IllegalArgumentException("parameter '" + key + "' must be between "
                    + min + " and " + max + ", got: " + f);
        }
        return f;
    }

    /** TTS pitch/rate range (pitch/rate of speech/tts, speech/tts_prosody/set; 1.0 = normal). */
    public static final float TTS_PROSODY_MIN = 0.5f;
    public static final float TTS_PROSODY_MAX = 2.0f;

    /** pitch/rate of speech/tts, speech/tts_prosody/set: optional, null = not passed.
     *  Range TTS_PROSODY_MIN..MAX; NaN/Infinity are always rejected. */
    public static Float optionalTtsProsody(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        float f = parseFloatOrThrow(key, v);
        if (Float.isNaN(f) || f < TTS_PROSODY_MIN || f > TTS_PROSODY_MAX) {
            throw new IllegalArgumentException("parameter '" + key + "' must be between "
                    + TTS_PROSODY_MIN + " and " + TTS_PROSODY_MAX + ", got: " + v);
        }
        return f;
    }

    public static long requireLong(Map<String, String> q, String key) {        return parseLongOrThrow(key, require(q, key));
    }

    public static long optionalLong(Map<String, String> q, String key, long defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseLongOrThrow(key, v);
    }

    // ── Float (vosk/endpointer t_*, ubx/speed value) ──────────────────
    public static float requireFloat(Map<String, String> q, String key) {
        return parseFloatOrThrow(key, require(q, key));
    }

    public static float optionalFloat(Map<String, String> q, String key, float defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseFloatOrThrow(key, v);
    }

    public static double requireDouble(Map<String, String> q, String key) {
        return parseDoubleOrThrow(key, require(q, key));
    }

    public static double optionalDouble(Map<String, String> q, String key, double defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseDoubleOrThrow(key, v);
    }

    // ── Nullable (absent/empty string returns null; a value is strictly parsed; an invalid value throws) ──────
    // For tri-state logic like "two optional parameters are only valid together" (camera w/h) or
    // "parameter present = set, absent = query" (speech/offline_auto_switch on), replacing query.get() + manual null checks.
    public static Integer optionalInteger(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        return Integer.valueOf(parseIntOrThrow(key, v));
    }

    public static Boolean optionalBooleanObject(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        return parseBoolOrNull(key, v.trim());
    }

    public static String optionalNullable(Map<String, String> q, String key) {
        String v = q.get(key);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    // ── Enum ────────────────────────────────────────────────────────
    public static String requireEnum(Map<String, String> q, String key, String[] allowed) {
        return checkEnumOrThrow(key, require(q, key), allowed);
    }

    public static String optionalEnum(Map<String, String> q, String key, String[] allowed, String defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return checkEnumOrThrow(key, v, allowed);
    }

    public static String optionalEnumNullable(Map<String, String> q, String key, String[] allowed) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        return checkEnumOrThrow(key, v, allowed);
    }

    // ── Boolean ────────────────────────────────────────────────────
    public static boolean requireBoolean(Map<String, String> q, String key) {
        return parseBoolOrNull(key, require(q, key).trim()).booleanValue();
    }

    public static boolean optionalBoolean(Map<String, String> q, String key, boolean defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseBoolOrNull(key, v.trim()).booleanValue();
    }

    // ── Common combinations (matching the specific constraints of the openapi) ─────────────────
    /** Servo ids 1-22 split by commas ("2,3,5"), sorted, without doubles. Missing or empty gives a
     *  copy of {@code defaultIds}. At most {@code maxCount} ids (servo/stream/start). */
    public static int[] optionalServoIdList(Map<String, String> q, String key, int[] defaultIds, int maxCount) {
        String text = q.get(key);
        if (text == null || text.trim().isEmpty()) return defaultIds.clone();
        boolean[] seen = new boolean[SERVO_ID_MAX + 1];
        int n = 0;
        for (String p : text.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            int id;
            try {
                id = Integer.parseInt(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be servo ids 1-22 split by commas, got: " + text);
            }
            if (id < SERVO_ID_MIN || id > SERVO_ID_MAX) {
                throw new IllegalArgumentException(key + ": servo id must be between 1 and 22, got: " + id);
            }
            if (!seen[id]) {
                seen[id] = true;
                n++;
            }
        }
        if (n == 0 || n > maxCount) {
            throw new IllegalArgumentException(key + " must name 1 to " + maxCount + " servos");
        }
        int[] out = new int[n];
        int k = 0;
        for (int id = 1; id < seen.length; id++) {
            if (seen[id]) out[k++] = id;
        }
        return out;
    }

    public static int requireColor(Map<String, String> q) {
        return requireIntRange(q, "color", 1, 7);
    }

    public static int requireBrightness(Map<String, String> q) {
        return requireIntRange(q, "brightness", 1, 9);
    }

    public static String requireLedHeadPreset(Map<String, String> q) {
        return optionalEnum(q, "preset", new String[]{"long","flash","breathe","chase","dual","stop"}, "long");
    }

    public static String requireLedEyePreset(Map<String, String> q) {
        return optionalEnum(q, "preset", new String[]{"long","flash","chase","dual","stop"}, "long");
    }

    public static String requireMouthPreset(Map<String, String> q) {
        return optionalEnum(q, "preset", new String[]{"breathing","off"}, "breathing");
    }

    public static int requireMouthSpeed(Map<String, String> q) {
        return optionalIntRange(q, "speed", 100, 1000, 150);
    }

    /** nuance/iflytek are dead (no alpha2services on the body,
     *  RobotStub.speech_startTTS always returns NOT_INIT); this list has been narrowed to "android" only -
     *  passing an old value gives invalid-parameter directly (see the test below). Previously tried to leave them in the list and return
     *  NOT_INIT honestly, then decided to narrow: dead engines should not appear in the legal parameters again
     *  (ApiValidatorTest covers the rejection). */
    /** TTS engine: android only allowed (no alpha2services on the body, the binder is dead). */
    public static String requireSpeechEngine(Map<String, String> q) {
        return optionalEnum(q, "engine", new String[]{"android"}, "android");
    }

    public static String optionalRingtoneType(Map<String, String> q) {
        return optionalEnum(q, "type", new String[]{"ringtone","notification"}, "ringtone");
    }

    public static String optionalSerialPort(Map<String, String> q) {
        return optionalEnum(q, "port", new String[]{"head","chest"}, "head");
    }

    // ── Chest "Sit down" guard (alpha2-brain#24, alpha2-python e98d6d1) ──
    // cmd 0x19 [00] is NOT a servo power off: it starts the stock "Sit down" move.
    // cmd 0x40 [01 ..] (power save, enter) can do the same. Both need allow_sit_down=true.

    /** allow_sit_down (default false): the explicit opt-in for the chest "Sit down" move.
     *  The openapi spec declares it as type boolean, so only the exact strings
     *  "true" and "false" are accepted. Any other value gives a 400. */
    public static boolean optionalAllowSitDown(Map<String, String> q) {
        String v = q.get("allow_sit_down");
        if (v == null) return false;
        v = v.trim();
        if ("true".equals(v)) return true;
        if ("false".equals(v)) return false;
        throw new IllegalArgumentException("parameter 'allow_sit_down' must be true or false, got: " + v);
    }

    /** direct/chest/servo_power: on (0/1). on=0 without allow_sit_down=true gives a 400. */
    public static boolean requireChestServoPower(Map<String, String> q) {
        boolean on = requireIntRange(q, "on", 0, 1) != 0;
        if (!on && !optionalAllowSitDown(q)) throw new IllegalArgumentException(ChestSafety.SIT_DOWN_19);
        return on;
    }

    /** direct/chest/power_save: enter (0/1). enter=1 without allow_sit_down=true gives a 400. */
    public static boolean requireChestPowerSaveEnter(Map<String, String> q) {
        boolean enter = requireIntRange(q, "enter", 0, 1) != 0;
        if (enter && !optionalAllowSitDown(q)) throw new IllegalArgumentException(ChestSafety.SIT_DOWN_40);
        return enter;
    }

    /** debug/serial/send: check allow_sit_down first, for every port: a bad value gives a
     *  400 even on port=head (alpha2-brain#60 N-1). Only then, on port=chest, refuse frames
     *  with cmd 0x19 [00] or cmd 0x40 [01 ..] unless allow_sit_down=true. Head frames are
     *  not checked (head cmd 0x19 is another command). */
    public static void checkSerialSendSitDown(Map<String, String> q, String port, byte[] data) {
        boolean allowSitDown = optionalAllowSitDown(q);
        if (!"chest".equals(port)) return;
        String why = ChestSafety.checkChestFrames(data, allowSitDown);
        if (why != null) throw new IllegalArgumentException(why);
    }

    public static String optionalUiLang(Map<String, String> q) {
        return optionalEnum(q, "ui_lang", new String[]{"zh","en"}, "zh");
    }

    public static String requireDebugLedFunc(Map<String, String> q) {
        return requireEnum(q, "func", new String[]{"off","on","eye","head"});
    }

    public static String requireWifiLedColor(Map<String, String> q) {
        return requireEnum(q, "color", new String[]{"red","blue","off"});
    }

    public static String requirePadKey(Map<String, String> q) {
        return requireEnum(q, "key", new String[]{"minus","plus"});
    }

    public static String requireXiaozhiTtsEngine(Map<String, String> q) {
        return requireEnum(q, "engine", new String[]{"xiaozhi","android"});
    }

    /** Boot voice mode, three choices (see XiaozhiConfig boot_voice/get|set, experiments tab card). */
    public static String requireBootVoiceMode(Map<String, String> q) {
        return requireEnum(q, "mode", new String[]{"off","xiaozhi","vosk"});
    }

    /** ubx/speed value: 0.5|0.67|1|1.5|2 (see the openapi enum + UbxPlayer.setSpeed). */
    public static float requireUbxSpeed(Map<String, String> q) {
        return parseUbxSpeedValue(require(q, "value"));
    }

    /** Shared string version (for helpers like ubxSpeedResponse that have already extracted the query; the same enum set). */
    public static float parseUbxSpeedValue(String v) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required parameter: value");
        }
        float f;
        try {
            f = Float.parseFloat(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("parameter 'value' must be one of [0.5, 0.67, 1, 1.5, 2], got: " + v);
        }
        float[] allowed = new float[]{0.5f, 0.67f, 1.0f, 1.5f, 2.0f};
        for (float a : allowed) {
            if (Math.abs(f - a) < 0.001f) return a;
        }
        throw new IllegalArgumentException("parameter 'value' must be one of [0.5, 0.67, 1, 1.5, 2], got: " + v);
    }

    /** Experiments tab panel token: 8-64 URL-safe characters (alphanumeric/-/_; see PanelAuth). */
    public static void checkPanelTokenFormat(String token) {
        if (token == null) {
            throw new IllegalArgumentException("missing required parameter: token/panel_token");
        }
        String v = token.trim();
        if (v.length() < 8 || v.length() > 64 || !v.matches("[A-Za-z0-9\\-_]+")) {
            throw new IllegalArgumentException("token must be 8-64 chars of A-Za-z0-9/-/_");
        }
    }

    /** misc/set_uuid value: 1-31 ASCII characters (see the openapi minLength/maxLength). */
    public static String requireUuidValue(Map<String, String> q) {
        String v = require(q, "value");
        if (v.length() < 1 || v.length() > 31) {
            throw new IllegalArgumentException("parameter 'value' must be 1-31 ascii chars, got length: " + v.length());
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("parameter 'value' must be 1-31 ascii chars, got non-ascii at index " + i);
            }
        }
        return v;
    }

    public static int requireVolumePercent(Map<String, String> q) {
        return requireIntRange(q, "percent", 0, 100);
    }

    public static int requireVolumePercent(Map<String, String> q, String key) {
        return requireIntRange(q, key, 0, 100);
    }

    public static int[] requireAngles20(Map<String, String> q) {
        String csv = require(q, "angles");
        String[] parts = csv.split(",");
        // Backward compatibility: accept both 20 (move only 1-20) and 22 (move all); any other length is a 400.
        if (parts.length != SERVO_COUNT && parts.length != SERVO_COUNT_LEGACY) {
            throw new IllegalArgumentException("parameter 'angles' must have exactly " + SERVO_COUNT_LEGACY + " or " + SERVO_COUNT + " comma-separated values, got " + parts.length);
        }
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            // Per element 0-255 (same as the spec/MCP schema; consistent with servo/one, preventing a silent byte wrap from reaching the servo).
            int v;
            try {
                v = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("parameter 'angles' element " + (i+1) + " must be integer, got: " + parts[i]);
            }
            if (v < SERVO_ANGLE_MIN || v > SERVO_ANGLE_MAX) {
                throw new IllegalArgumentException("parameter 'angles' element " + (i+1) + " must be between 0 and 255, got: " + v);
            }
            out[i] = v;
        }
        return out;
    }

    // ── Response helpers (unified {"ok":...} shape) ────────────────────────
    public static HttpServer.ApiResponse ok(String jsonBody) {
        return HttpServer.ApiResponse.ok(jsonBody);
    }

    public static HttpServer.ApiResponse okTrue() {
        return HttpServer.ApiResponse.okTrue();
    }

    public static HttpServer.ApiResponse error(String msg) {
        return HttpServer.ApiResponse.error(msg);
    }
}
