package com.open.alpha2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.ubtechinc.alpha.hardware.RobotWire;

/**
 * Receives every documented Alpha2 sensor/event broadcast (see docs/sensors-and-events.md
 * and docs/capabilities.md in the Alpha2OpenSdk repo) and forwards a JSON-ish line to the
 * shared {@link EventBus}, which both the WebSocket log and any local listeners consume.
 *
 * Registered dynamically from MainActivity.onCreate().
 *
 * IMPORTANT lesson from a real device: docs/capabilities.md documents
 * "getstureDirection" as a String extra, but on real hardware it arrives as an Integer,
 * and Intent.getStringExtra() throws ClassCastException on a type mismatch rather than
 * returning null. That exception was silently swallowing the whole gesture event. Every
 * extra read below now goes through {@link #readAny}, which tries the extra as every
 * primitive Bundle type Android supports and never throws - so a future doc/reality
 * mismatch degrades to an unlabeled raw value instead of dropping the event.
 */
public class RobotEventReceiver extends BroadcastReceiver {
    private static final String TAG = "RobotEventReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        // Log at the main entry point, recording which action this receiver
        // actually received.
        Log.i(TAG, "onReceive action=" + action);
        try {
            switch (action) {
                case "com.ubtechinc.key": {
                    // This action is dead code in this firmware version; it never
                    // fires. Kept only for backward compatibility (in case other
                    // firmware or older devices use this action again).
                    // Extra "key" is a Byte, not an int - see gotchas-and-naming.md.
                    Object key = readAny(intent, "key");
                    EventBus.get().publish("head_key", "{\"keyId\":" + jsonValue(key) + "}");
                    break;
                }
                case "com.ubtechinc.robot.tts_hint_wakeup": {
                    Object hint = readAny(intent, "hint_event");
                    EventBus.get().publish("wakeup", "{\"hintEvent\":" + jsonValue(hint) + "}");
                    // Probe cloud connectivity once the moment the user starts
                    // speaking (see the comment on MainActivity.triggerWakeupProbe()).
                    MainActivity.triggerWakeupProbe();
                    break;
                }
                case "come.ubt.alpha2.gesture": {
                    // Documented as String; observed as Integer on real hardware - see
                    // class javadoc. readAny() handles either without throwing.
                    Object direction = readAny(intent, "getstureDirection");
                    EventBus.get().publish("gesture", "{\"direction\":" + jsonValue(direction) + "}");
                    break;
                }
                case "com.ubtechinc.robot_uuid.info": {
                    Object uuid = readAny(intent, "robot_uuid");
                    // A direct String.valueOf(uuid) has two bugs that cause
                    // "cannot read uuid":
                    // 1) When uuid == null, String.valueOf() returns the string "null",
                    //    which is still "null" after whitelist filtering, so the
                    //    frontend shows "null" as a real ID;
                    // 2) The SN that firmware sends back over the broadcast is very
                    //    likely a byte[] (EEPROM ASCII + padding), and
                    //    String.valueOf(byte[]) only returns an Object.toString like
                    //    "[B@<hashcode>", which the whitelist turns into a "Bxxxxxxx"
                    //    garbage chunk, never a real SN.
                    // Fix: decode byte[] as US-ASCII (same semantics as
                    // MainActivity.parseRobotUuidFrame); when null, fall back to
                    // scanning all extras for a plausible value; when none is found,
                    // publish {"uuid":null} so the frontend shows an error instead of
                    // hanging on "querying".
                    String s = decodeUuidExtra(uuid);
                    if ((s == null || s.isEmpty()) && intent.getExtras() != null) {
                        Bundle all = intent.getExtras();
                        for (String key : all.keySet()) {
                            if ("robot_uuid".equals(key)) continue;
                            String cand = decodeUuidExtra(all.get(key));
                            if (cand != null && !cand.isEmpty()) {
                                s = cand;
                                Log.i(TAG, "robot_uuid.info: fell back to extra \"" + key + "\"");
                                break;
                            }
                        }
                    }
                    if (s == null || s.isEmpty()) {
                        Log.w(TAG, "robot_uuid.info: empty uuid (extras="
                                + bundleToJson(intent.getExtras()) + ")");
                        EventBus.get().publish("robot_uuid", "{\"uuid\":null}");
                    } else {
                        EventBus.get().publish("robot_uuid", "{\"uuid\":" + jsonValue(s) + "}");
                    }
                    break;
                }
                case RobotWire.ALPHA_QR_CODE: {
                    Object result = readAny(intent, "uncode_result");
                    Object flag = readAny(intent, "flag");
                    EventBus.get().publish("qr_code", "{\"result\":" + jsonValue(result)
                            + ",\"flag\":" + jsonValue(flag) + "}");
                    break;
                }
                case RobotWire.ALPHA_WIFI_RESULT: {
                    // Payload shape isn't pinned down in docs; forward every extra name
                    // present so nothing is silently dropped.
                    EventBus.get().publish("wifi_result", bundleToJson(intent.getExtras()));
                    break;
                }
                case RobotWire.ALPHA_BT_CONNECTION: {
                    Object btFlag = readAny(intent, "BT_FLAG");
                    EventBus.get().publish("bt_connection", "{\"btFlag\":" + jsonValue(btFlag) + "}");
                    break;
                }
                case "com.ubtechinc.services.Action.ACTION_STOP": {
                    // Action playback on the robot side was interrupted and stopped
                    // from outside - unlike
                    // IActionResultListener.onStopActionResult(), this is a global
                    // broadcast, not limited to the playAction() session you called.
                    EventBus.get().publish("action_stop", "{}");
                    break;
                }
                case "com.ubtechinc.services.Action.ROBOT_INTERRUPTED": {
                    // Means the robot as a whole was interrupted (usually stops TTS
                    // and actions together).
                    EventBus.get().publish("robot_interrupted", "{}");
                    break;
                }
                case RobotWire.CHEST_ACTION: {
                    // Sonar readings do not go through here; they go through the
                    // separate RobotWire.SONAR_DISTANCE_ACTION case below. This case
                    // only does auxiliary debug (you can see the timing of the raw
                    // command bytes inside the robot body).
                    //
                    // The CHEST_ACTION broadcast is not always forwarded to
                    // third-party apps (observed: not every one arrives). This prints
                    // the full raw value on every receipt, so you can compare how many
                    // times the official app received -109.
                    Log.i(TAG, "CHEST_ACTION received, raw value=" + bundleToJson(intent.getExtras()));
                    EventBus.get().publish("chest_broadcast_debug",
                            "{\"action\":\"" + action + "\",\"extras\":" + bundleToJson(intent.getExtras()) + "}");

                    // The chest mute key is sent over the same CHEST_ACTION
                    // broadcast; extra "value" (byte[]) contains -111 (0x91). Wire
                    // frame f8 8f 08 00 00 91 01 9a ed /
                    // f8 8f 08 00 00 91 00 99 ed.
                    //
                    // Do not assume -111 sits at a fixed index; scan the whole array.
                    // Known samples (cmd -115/-111/-109/-128 with checksums
                    // 0x97/0x9a, 0x99/0x9c/0x8c) do not collide with 0x91, so there is
                    // no false trigger.
                    Object rawValue = readAny(intent, "value");
                    if (rawValue instanceof byte[]) {
                        byte[] arr = (byte[]) rawValue;

                        // The chest mute key splits into "pressed(01) / released(00)"
                        // - the observed raw wire frames are f8 8f 08 00 00 91 01 9a ed /
                        // f8 8f 08 00 00 91 00 99 ed. As with PIR (-109), the byte
                        // after cmd is the sub-value. MainActivity uses this flag to
                        // toggle the mute LED (cmd=68, [01]=on / [00]=off, confirmed
                        // by on-device scan). Find the index of -111; if it is not the
                        // last byte, take the next byte for the pressed decision; when
                        // only cmd is present with no param, conservatively treat it as
                        // pressed.
                        for (int i = 0; i < arr.length; i++) {
                            if (arr[i] == (byte) -111) {
                                boolean mutePressed = true;
                                if (i + 1 < arr.length) {
                                    mutePressed = arr[i + 1] == 1;
                                }
                                EventBus.get().publish("chest_mute_key",
                                        "{\"pressed\":" + mutePressed + "}");
                                MainActivity.onMuteKeyEvent(mutePressed);
                                break;
                            }
                        }

                        // PIR sensor raw trigger event (cmd=-109 / 0x93, "PIR HUMON
                        // DETECT (1: ENTER)  (0: EXIT)").
                        //
                        // The official 1.1.7.3 code does not support -109/-111/-108;
                        // it only logs "ches cmd = -109" and does not forward the
                        // pirStatus broadcast. This app can still read the raw
                        // "value" byte[] of this broadcast directly.
                        //
                        // The sub-value (ENTER=1/EXIT=0) is the byte right after the
                        // cmd byte. The "value" array the SDK passes may be a full
                        // wire frame (e.g. f8 8f 08 00 00 93 01 9c ed) or already
                        // reduced to just the param (e.g. {01}). The approach: find
                        // the index of -109; if it is not the last element of the
                        // array, take its next byte for the ENTER/EXIT decision; if it
                        // is exactly the last byte, there is no sub-value to take, so
                        // conservatively treat triggered as true.
                        for (int i = 0; i < arr.length; i++) {
                            if (arr[i] == (byte) -109) {
                                boolean pirTriggered = true;
                                if (i + 1 < arr.length) {
                                    pirTriggered = arr[i + 1] == 1;
                                }
                                EventBus.get().publish("alpha2_pir_state",
                                        "{\"triggered\":" + pirTriggered + "}");
                                MainActivity.onPirStateReceived(pirTriggered);
                                break;
                            }
                        }
                    }
                    break;
                }
                case "com.ubtech.securityCamera.pirStatus": {
                    // Not verified on a real device (1.1.7.3 should not send this).
                    // This case is kept so that when a supported firmware version
                    // appears later, both paths feed the same "alpha2_pir_state"
                    // event and the frontend does not care which path ran behind the
                    // scenes. Extra "pirStatus" is a byte, 1=person entered,
                    // 0=person left - the action/extra names are completely different
                    // from Lynx's "com.ubtechinc.services.Action.PIR_STATE" (extra
                    // "pirState", boolean). Do not confuse the two. Use the separate
                    // "alpha2_pir_state" event name (not Lynx's "pir_state") so the
                    // frontend can keep the two backends' indicators apart without
                    // overwriting each other.
                    Object pirStatus = readAny(intent, "pirStatus");
                    boolean triggered = toUnsignedByteInt(pirStatus) == 1;
                    EventBus.get().publish("alpha2_pir_state", "{\"triggered\":" + triggered + "}");
                    break;
                }
                case RobotWire.SONAR_DISTANCE_ACTION: {
                    // Sonar readings arrive on this separate broadcast. The extra is
                    // already an int parsed by the firmware (SONAR_DISTANCE_EXTRA =
                    // "sonar_distance"), so no raw wire frame decoding is needed here.
                    // The demo's own UI (ActionMainActivity$7) reads it with
                    // intent.getIntExtra(key, 0) and shows 0 or a negative value as
                    // "no reading / out of range" rendered as "INF" - we follow the
                    // same assumption here. It is not 100% confirmed that this value
                    // is already in cm (the demo just prints it, with no conversion),
                    // but the second config param byte (40) sent by enableSonar()
                    // looks like a cm threshold, so both are likely the same unit.
                    int distanceCm = intent.getIntExtra(RobotWire.SONAR_DISTANCE_EXTRA, -1);
                    boolean triggered = distanceCm > 0 && distanceCm <= MainActivity.getSonarThresholdCm();
                    EventBus.get().publish("sonar_obstacle",
                            "{\"distanceCm\":" + distanceCm
                                    + ",\"thresholdCm\":" + MainActivity.getSonarThresholdCm()
                                    + ",\"triggered\":" + triggered + "}");
                    MainActivity.onSonarDistanceReceived(distanceCm, triggered);
                    break;
                }
                // Used to answer whether the robot body emits any broadcast
                // notification at the moment speech_SetMIC()/setWakeState() takes
                // the mic back.
                // These 6 action names/extras look related to TTS, ASR and mic
                // events, but which of them actually fires at the setWakeState()
                // moment, and what the payload really contains, cannot be seen by
                // decompiling the bytecode alone (the bytecode only shows the action
                // string and the putExtra() key names/types, not when that code is
                // reached). So here we deliberately do not yet assume which extra
                // means mic state, and do not split them into standalone UI events.
                // Instead we use one shared mic_broadcast_debug event to send the
                // whole intent (action + all extras, via bundleToJson(), types
                // untouched) to the WebSocket log. Once we collect the real
                // on-device payloads, we pick the ones that truly relate to mic
                // ownership and split those into standalone events.
                case "com.ubtechinc.services.ABOUT_TTS":
                case "com.ubtechinc.services.ALPHA_SOCKET_ASR_OK":
                case "com.ubtechinc.services.SPEECH_ANGLE_5MIC":
                case "com.ubtechinc.services.LED_ACTION":
                case "com.ubtechinc.services.POWER_SAVE":
                case "com.ubtechinc.services.ALPHA_NOTIFY_POWER": {
                    EventBus.get().publish("mic_broadcast_debug",
                            "{\"action\":\"" + action + "\",\"extras\":" + bundleToJson(intent.getExtras()) + "}");
                    break;
                }
                default:
                    Log.d(TAG, "Unhandled action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "onReceive error for " + action, e);
        }
    }

    /**
     * Reads a Bundle extra without knowing its real type ahead of time. Tries the common
     * primitive wrapper types Android's Bundle supports for a single extra key, in an
     * order that costs nothing on a miss (Bundle.get() itself never throws - it's the
     * *typed* getters like getStringExtra() that throw ClassCastException on a mismatch).
     * Falls back to Bundle.get() (returns Object, works for any type) if a caller needs
     * something outside that set.
     */
    private static Object readAny(Intent intent, String key) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }
        return extras.get(key); // Bundle.get() is untyped and never throws ClassCastException.
    }

    /**
     * Decode the robot_uuid extra into a clean SN string (same semantics as
     * MainActivity.parseRobotUuidFrame: ASCII decode - drop the padding after the
     * first \0 - whitelist keeps only alphanumerics, - and _). byte[] goes through
     * ASCII decoding, String goes through the same cleaning path, null/empty
     * returns null. Other types (Integer, etc.) go through String.valueOf and then
     * the same cleaning, to avoid "[B@hash"-style garbage.
     */
    private static String decodeUuidExtra(Object value) {
        if (value == null) {
            return null;
        }
        String s;
        if (value instanceof byte[]) {
            byte[] arr = (byte[]) value;
            try {
                s = new String(arr, java.nio.charset.StandardCharsets.US_ASCII);
            } catch (Exception e) {
                return null;
            }
        } else {
            s = String.valueOf(value);
            if ("null".equals(s)) {
                return null;
            }
        }
        // The byte[] may start with a flag 0x00 (see the real frame in
        // MainActivity.parseRobotUuidFrame). Skip the leading invalid characters
        // first, then cut at \0. Otherwise indexOf('\0')=0 cuts out an empty
        // string.
        int start = 0;
        while (start < s.length()) {
            char c = s.charAt(start);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (ok) break;
            start++;
        }
        s = s.substring(start);
        int cut = s.indexOf('\0');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        s = s.replaceAll("[^A-Za-z0-9\\-_]", "").trim();
        if (s.isEmpty()) return null;
        // Cut the tail (non-zero EEPROM residue; see ChestQuery.truncateUuidTail).
        // The broadcast path has no SharedPreferences-stored length to use, so pass
        // -1 to apply the pattern/case rules.
        return ChestQuery.truncateUuidTail(s, -1);
    }

    private static int toUnsignedByteInt(Object value) {
        if (value instanceof Byte) {
            int v = (Byte) value;
            return v < 0 ? v + 256 : v;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    /** Renders any extra value as a JSON literal: quoted string, bare number/boolean, or null. */
    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte || value instanceof Double
                || value instanceof Float) {
            return String.valueOf(value);
        }
        // byte[] (the CHEST_ACTION "value" extra is exactly this) is not covered
        // above. Falling back to String.valueOf(value) takes the default
        // Object.toString() result, a "[B@<hashcode>" string that shows none of the
        // content - that is why the Event Log page previously showed
        // "value":"[B@276adcef". The array content was never printed, so we kept
        // guessing which index the chest mute key (-111) sat at. Here we print each
        // byte as a signed decimal value (matching the logcat "ches cmd = -111"
        // format for direct comparison), comma-separated and wrapped in [], not a
        // plain JSON string.
        if (value instanceof byte[]) {
            byte[] arr = (byte[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof int[]) {
            int[] arr = (int[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(jsonValue(arr[i]));
            }
            sb.append(']');
            return sb.toString();
        }
        return "\"" + safe(String.valueOf(value)) + "\"";
    }

    /** Dumps every extra in a Bundle as a flat JSON object of stringified values, for
     *  broadcasts whose exact payload shape isn't pinned down upstream. */
    private static String bundleToJson(Bundle extras) {
        if (extras == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : extras.keySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(safe(key)).append("\":").append(jsonValue(extras.get(key)));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

