package com.open.alpha2;

import java.util.HashMap;
import java.util.Map;

/**
 * ApiValidator 單元測試 (零依賴，不用 JUnit)。
 *
 * 點解唔用 JUnit：本專案離線編譯 (`--offline`)，gradle cache 入面冇 junit，
 * 加 `testImplementation 'junit:...'` 會整斷每個人嘅 offline build。
 * 呢個 file 係純 Java + `main()` runner，經 `scripts/test-apivalidator.py`
 * 用 `javac`/`java` + SDK 嘅 `android.jar` 直接編譯執行 (CI 同本機都得)，
 * `./gradlew assembleDebug` 完全唔受影響 (唔喺 main sourceSet)。
 *
 * 執行：`python scripts/test-apivalidator.py`
 */
public final class ApiValidatorTest {
    private static int passed = 0;
    private static int failed = 0;

    private ApiValidatorTest() {}

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(float expected, float actual, String name) {
        boolean ok = (Float.isNaN(expected) && Float.isNaN(actual))
                || Math.abs(expected - actual) < 0.0001f;
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(int expected, int actual, String name) {
        eq(Integer.valueOf(expected), Integer.valueOf(actual), name);
    }

    private static void eq(boolean expected, boolean actual, String name) {
        eq(Boolean.valueOf(expected), Boolean.valueOf(actual), name);
    }

    private static void throwsIllegal(Runnable r, String name) {
        try {
            r.run();
            failed++;
            System.out.println("FAIL " + name + ": expected IllegalArgumentException, none thrown");
        } catch (IllegalArgumentException e) {
            passed++;
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + ": wrong exception " + t);
        }
    }

    private static byte[] frame(int cmd, int... payload) {
        int len = 7 + payload.length;
        byte[] out = new byte[len + 1];
        out[0] = (byte) 0xF8; out[1] = (byte) 0x8F; out[2] = (byte) len; out[3] = 0x05; out[4] = 0x00;
        out[5] = (byte) cmd;
        for (int i = 0; i < payload.length; i++) out[6 + i] = (byte) payload[i];
        int sum = 0;
        for (int i = 2; i < len - 1; i++) sum += out[i] & 0xFF;
        out[len - 1] = (byte) sum;
        out[len] = (byte) 0xED;
        return out;
    }

    private static byte[] shortFrame(int cmd, int... payload) {
        int len = 1 + payload.length;
        byte[] out = new byte[len + 5];
        out[0] = (byte) 0xF8; out[1] = (byte) 0x8F; out[2] = (byte) len; out[3] = (byte) cmd;
        for (int i = 0; i < payload.length; i++) out[4 + i] = (byte) payload[i];
        int sum = 0;
        for (int i = 2; i < out.length - 2; i++) sum += out[i] & 0xFF;
        out[out.length - 2] = (byte) sum;
        out[out.length - 1] = (byte) 0xED;
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) { if (sb.length() > 0) sb.append(' '); sb.append(String.format("%02x", x & 0xFF)); }
        return sb.toString();
    }

    public static void main(String[] args) {
        // ── 基礎 ──
        eq("v", ApiValidator.require(q("k", "v"), "k"), "require.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.require(q(), "k"); } }, "require.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.require(q("k", ""), "k"); } }, "require.empty");
        eq("v", ApiValidator.optional(q("k", "v"), "k", "d"), "optional.present");
        eq("d", ApiValidator.optional(q(), "k", "d"), "optional.missing");
        eq("d", ApiValidator.optional(q("k", ""), "k", "d"), "optional.empty");

        // ── 整數 ──
        eq(42, ApiValidator.requireInt(q("k", "42"), "k"), "requireInt.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireInt(q("k", "abc"), "k"); } }, "requireInt.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireInt(q(), "k"); } }, "requireInt.missing");
        eq(7, ApiValidator.optionalInt(q("k", "7"), "k", 9), "optionalInt.present");
        eq(9, ApiValidator.optionalInt(q(), "k", 9), "optionalInt.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalInt(q("k", "x"), "k", 9); } }, "optionalInt.bad");
        eq(5, ApiValidator.requireIntRange(q("k", "5"), "k", 1, 20), "requireIntRange.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireIntRange(q("k", "21"), "k", 1, 20); } }, "requireIntRange.high");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireIntRange(q("k", "0"), "k", 1, 20); } }, "requireIntRange.low");
        eq(3, ApiValidator.optionalIntRange(q("k", "3"), "k", 1, 20, 1), "optionalIntRange.present");
        eq(1, ApiValidator.optionalIntRange(q(), "k", 1, 20, 1), "optionalIntRange.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalIntRange(q("k", "99"), "k", 1, 20, 1); } }, "optionalIntRange.high");

        // ── 長整數 ──
        eq(Long.valueOf(1500L), Long.valueOf(ApiValidator.requireLong(q("k", "1500"), "k")), "requireLong.ok");
        eq(Long.valueOf(1500L), Long.valueOf(ApiValidator.optionalLong(q(), "k", 1500L)), "optionalLong.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalLong(q("k", "1.5"), "k", 0L); } }, "optionalLong.bad");

        // ── 浮點 ──
        eq(0.5f, ApiValidator.requireFloat(q("k", "0.5"), "k"), "requireFloat.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireFloat(q("k", "abc"), "k"); } }, "requireFloat.bad");
        eq(Float.NaN, ApiValidator.optionalFloat(q(), "k", Float.NaN), "optionalFloat.nanDefault");
        eq(1.5f, ApiValidator.optionalFloat(q("k", "1.5"), "k", Float.NaN), "optionalFloat.present");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalFloat(q("k", "abc"), "k", Float.NaN); } }, "optionalFloat.bad");
        eq(Double.valueOf(2.5d), Double.valueOf(ApiValidator.requireDouble(q("k", "2.5"), "k")), "requireDouble.ok");
        eq(Double.valueOf(3.0d), Double.valueOf(ApiValidator.optionalDouble(q(), "k", 3.0d)), "optionalDouble.missing");

        // ── Nullable ──
        eq(null, ApiValidator.optionalInteger(q(), "k"), "optionalInteger.missing");
        eq(null, ApiValidator.optionalInteger(q("k", ""), "k"), "optionalInteger.empty");
        eq(Integer.valueOf(4), ApiValidator.optionalInteger(q("k", "4"), "k"), "optionalInteger.present");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalInteger(q("k", "z"), "k"); } }, "optionalInteger.bad");
        eq(null, ApiValidator.optionalBooleanObject(q(), "k"), "optionalBooleanObject.missing");
        eq(Boolean.TRUE, ApiValidator.optionalBooleanObject(q("k", "true"), "k"), "optionalBooleanObject.true");
        eq(Boolean.FALSE, ApiValidator.optionalBooleanObject(q("k", "0"), "k"), "optionalBooleanObject.zero");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalBooleanObject(q("k", "maybe"), "k"); } }, "optionalBooleanObject.bad");
        eq(null, ApiValidator.optionalNullable(q(), "k"), "optionalNullable.missing");
        eq("x", ApiValidator.optionalNullable(q("k", "x"), "k"), "optionalNullable.present");

        // ── 枚舉 ──
        final String[] ab = new String[]{"a", "b"};
        eq("a", ApiValidator.requireEnum(q("k", "a"), "k", ab), "requireEnum.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireEnum(q("k", "c"), "k", ab); } }, "requireEnum.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireEnum(q(), "k", ab); } }, "requireEnum.missing");
        eq("b", ApiValidator.optionalEnum(q("k", "b"), "k", ab, "a"), "optionalEnum.present");
        eq("a", ApiValidator.optionalEnum(q(), "k", ab, "a"), "optionalEnum.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalEnum(q("k", "c"), "k", ab, "a"); } }, "optionalEnum.bad");
        eq(null, ApiValidator.optionalEnumNullable(q(), "k", ab), "optionalEnumNullable.missing");
        eq("b", ApiValidator.optionalEnumNullable(q("k", "b"), "k", ab), "optionalEnumNullable.present");

        // ── Boolean ──
        eq(true, ApiValidator.requireBoolean(q("k", "true"), "k"), "requireBoolean.true");
        eq(true, ApiValidator.requireBoolean(q("k", "1"), "k"), "requireBoolean.one");
        eq(false, ApiValidator.requireBoolean(q("k", "FALSE"), "k"), "requireBoolean.falseUpper");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBoolean(q("k", "yes"), "k"); } }, "requireBoolean.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBoolean(q(), "k"); } }, "requireBoolean.missing");
        eq(true, ApiValidator.optionalBoolean(q(), "k", true), "optionalBoolean.default");

        // ── 常用組合 ──
        eq(3, ApiValidator.requireColor(q("color", "3")), "color.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireColor(q("color", "8")); } }, "color.high");
        eq(9, ApiValidator.requireBrightness(q("brightness", "9")), "brightness.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBrightness(q("brightness", "0")); } }, "brightness.low");
        eq("long", ApiValidator.requireLedHeadPreset(q()), "ledHead.default");
        eq("breathe", ApiValidator.requireLedHeadPreset(q("preset", "breathe")), "ledHead.breathe");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireLedHeadPreset(q("preset", "breathe2")); } }, "ledHead.bad");
        eq("long", ApiValidator.requireLedEyePreset(q()), "ledEye.default");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireLedEyePreset(q("preset", "breathe")); } }, "ledEye.noBreathe");
        eq("breathing", ApiValidator.requireMouthPreset(q()), "mouth.default");
        eq(150, ApiValidator.requireMouthSpeed(q()), "mouthSpeed.default");
        eq(150, ApiValidator.requireMouthSpeed(q("speed", "150")), "mouthSpeed.present");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireMouthSpeed(q("speed", "1001")); } }, "mouthSpeed.high");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireMouthSpeed(q("speed", "99")); } }, "mouthSpeed.low");
        // P0 行為：speech/tts 只准 android (唯一會出聲)；nuance/iflytek 已經永久
        // 唔再用 (機身無 alpha2services, binder 已死)，2026-09 由「照收但回
        // NOT_INIT」改做徹底唔准 (invalid-parameter)。
        eq("android", ApiValidator.requireSpeechEngine(q()), "speechEngine.defaultAndroid");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireSpeechEngine(q("engine", "iflytek")); } }, "speechEngine.iflytekGone");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireSpeechEngine(q("engine", "nuance")); } }, "speechEngine.nuanceGone");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireSpeechEngine(q("engine", "bogus")); } }, "speechEngine.bad");
        eq("ringtone", ApiValidator.optionalRingtoneType(q()), "ringtone.default");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalRingtoneType(q("type", "alarm")); } }, "ringtone.bad");
        eq("head", ApiValidator.optionalSerialPort(q()), "serialPort.default");
        eq("chest", ApiValidator.optionalSerialPort(q("port", "chest")), "serialPort.chest");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalSerialPort(q("port", "usb")); } }, "serialPort.bad");
        eq("zh", ApiValidator.optionalUiLang(q()), "uiLang.default");
        eq("en", ApiValidator.optionalUiLang(q("ui_lang", "en")), "uiLang.en");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalUiLang(q("ui_lang", "fr")); } }, "uiLang.bad");
        eq("on", ApiValidator.requireDebugLedFunc(q("func", "on")), "debugFunc.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireDebugLedFunc(q()); } }, "debugFunc.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireDebugLedFunc(q("func", "blink")); } }, "debugFunc.bad");
        eq("red", ApiValidator.requireWifiLedColor(q("color", "red")), "wifiLed.red");
        eq("off", ApiValidator.requireWifiLedColor(q("color", "off")), "wifiLed.off");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireWifiLedColor(q("color", "green")); } }, "wifiLed.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireWifiLedColor(q()); } }, "wifiLed.missing");
        eq("minus", ApiValidator.requirePadKey(q("key", "minus")), "padKey.minus");
        eq("plus", ApiValidator.requirePadKey(q("key", "plus")), "padKey.plus");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requirePadKey(q("key", "both")); } }, "padKey.bad");
        eq("xiaozhi", ApiValidator.requireXiaozhiTtsEngine(q("engine", "xiaozhi")), "xiaozhiTts.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireXiaozhiTtsEngine(q("engine", "nuance")); } }, "xiaozhiTts.bad");
        eq("off", ApiValidator.requireBootVoiceMode(q("mode", "off")), "bootVoice.off");
        eq("xiaozhi", ApiValidator.requireBootVoiceMode(q("mode", "xiaozhi")), "bootVoice.xiaozhi");
        eq("vosk", ApiValidator.requireBootVoiceMode(q("mode", "vosk")), "bootVoice.vosk");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBootVoiceMode(q("mode", "bad")); } }, "bootVoice.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBootVoiceMode(q()); } }, "bootVoice.missing");

        // ── ubx speed ──
        eq(1.0f, ApiValidator.requireUbxSpeed(q("value", "1")), "ubxSpeed.one");
        eq(0.67f, ApiValidator.requireUbxSpeed(q("value", "0.67")), "ubxSpeed.067");
        eq(2.0f, ApiValidator.parseUbxSpeedValue("2"), "ubxSpeed.parse");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUbxSpeed(q("value", "3")); } }, "ubxSpeed.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUbxSpeed(q()); } }, "ubxSpeed.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.parseUbxSpeedValue(null); } }, "ubxSpeed.null");

        // speech/tts、speech/tts_prosody/set: pitch／rate 0.5..2.0，可選 (null＝無帶)
        eq(null, ApiValidator.optionalTtsProsody(q(), "pitch"), "ttsProsody.missing");
        eq(null, ApiValidator.optionalTtsProsody(q("pitch", ""), "pitch"), "ttsProsody.empty");
        eq(0.7f, ApiValidator.optionalTtsProsody(q("pitch", "0.7"), "pitch").floatValue(), "ttsProsody.pitch");
        eq(0.5f, ApiValidator.optionalTtsProsody(q("rate", "0.5"), "rate").floatValue(), "ttsProsody.min");
        eq(2.0f, ApiValidator.optionalTtsProsody(q("rate", " 2 "), "rate").floatValue(), "ttsProsody.max");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalTtsProsody(q("pitch", "0.49"), "pitch"); } }, "ttsProsody.low");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalTtsProsody(q("rate", "2.01"), "rate"); } }, "ttsProsody.high");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalTtsProsody(q("rate", "NaN"), "rate"); } }, "ttsProsody.nan");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalTtsProsody(q("pitch", "fast"), "pitch"); } }, "ttsProsody.text");

        // ── uuid / volume / angles ──
        eq("ABC123", ApiValidator.requireUuidValue(q("value", "ABC123")), "uuid.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUuidValue(q("value", "01234567890123456789012345678901")); } }, "uuid.tooLong");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUuidValue(q("value", "ABC\u00e4")); } }, "uuid.nonAscii");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUuidValue(q()); } }, "uuid.missing");
        eq(50, ApiValidator.requireVolumePercent(q("percent", "50")), "volume.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireVolumePercent(q("percent", "101")); } }, "volume.high");
        eq(50, ApiValidator.requireVolumePercent(q("percent", "50"), "percent"), "volume.keyed");
        int[] angles = ApiValidator.requireAngles20(q("angles", "90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90"));
        eq(20, angles.length, "angles20.len");
        eq(90, angles[0], "angles20.first");
        int[] angles22 = ApiValidator.requireAngles20(q("angles", "90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90"));
        eq(22, angles22.length, "angles22.len");
        eq(90, angles22[21], "angles22.last");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireAngles20(q("angles", "1,2,3")); } }, "angles20.short");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireAngles20(q("angles", "90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90")); } }, "angles21.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireAngles20(q()); } }, "angles20.missing");

        // ── 舵機校準 clamp（同 app-core.js SERVO_CALIBRATION 一字不差；
        // 手柄 jog／搖桿／觸控板共用，頂限位唔會再頂過龍）──
        eq(5, ApiValidator.servoCalMin(1), "cal.min1");
        eq(235, ApiValidator.servoCalMax(1), "cal.max1");
        eq(65, ApiValidator.servoCalHome(8), "cal.home8");
        eq(105, ApiValidator.servoCalMin(20), "cal.min20");
        eq(155, ApiValidator.servoCalMax(20), "cal.max20");
        eq(5, ApiValidator.servoCalMin(21), "cal.min21");
        eq(235, ApiValidator.servoCalMax(21), "cal.max21");
        eq(5, ApiValidator.servoCalMin(22), "cal.min22");
        eq(235, ApiValidator.servoCalMax(22), "cal.max22");
        eq(120, ApiValidator.servoCalHome(21), "cal.home21");
        eq(120, ApiValidator.servoCalHome(22), "cal.home22");
        eq(22, ApiValidator.SERVO_COUNT, "servo.count");
        eq(22, ApiValidator.SERVO_ID_MAX, "servo.idmax");
        eq(125, ApiValidator.clampServoAngle(17, 200), "clamp.17high");
        eq(95, ApiValidator.clampServoAngle(17, 0), "clamp.17low");
        eq(120, ApiValidator.clampServoAngle(17, 120), "clamp.17mid");
        eq(0, ApiValidator.clampServoAngle(99, -5), "clamp.unknownLow");
        eq(255, ApiValidator.clampServoAngle(0, 999), "clamp.unknownHigh");

        // ── servo id list (servo/stream/start) ──
        final int[] defIds = {2, 3, 5};
        eq("[2, 3, 5]", java.util.Arrays.toString(ApiValidator.optionalServoIdList(q(), "ids", defIds, 22)), "ids.default");
        eq("[2, 17, 20]", java.util.Arrays.toString(ApiValidator.optionalServoIdList(q("ids", "20, 2,17,2"), "ids", defIds, 22)), "ids.sortUnique");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalServoIdList(q("ids", "0"), "ids", defIds, 22); } }, "ids.zero");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalServoIdList(q("ids", "23"), "ids", defIds, 22); } }, "ids.high");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalServoIdList(q("ids", "a,b"), "ids", defIds, 22); } }, "ids.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalServoIdList(q("ids", ",,"), "ids", defIds, 22); } }, "ids.empty");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalServoIdList(q("ids", "1,2,3"), "ids", defIds, 2); } }, "ids.tooMany");

        // ── chest "Sit down" guard (alpha2-brain#24, same rules as alpha2-python e98d6d1) ──
        eq(true, ApiValidator.requireChestServoPower(q("on", "1")), "sit.servoPower.on");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireChestServoPower(q("on", "0")); } }, "sit.servoPower.off.refused");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireChestServoPower(q("on", "0", "allow_sit_down", "false")); } }, "sit.servoPower.off.false");
        eq(false, ApiValidator.requireChestServoPower(q("on", "0", "allow_sit_down", "true")), "sit.servoPower.off.allowed");
        eq(false, ApiValidator.requireChestServoPower(q("on", "0", "allow_sit_down", "1")), "sit.servoPower.off.allowed1");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireChestServoPower(q("on", "2")); } }, "sit.servoPower.range");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireChestServoPower(q("on", "0", "allow_sit_down", "yes")); } }, "sit.servoPower.badFlag");
        eq(false, ApiValidator.requireChestPowerSaveEnter(q("enter", "0")), "sit.powerSave.leave");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireChestPowerSaveEnter(q("enter", "1")); } }, "sit.powerSave.enter.refused");
        eq(true, ApiValidator.requireChestPowerSaveEnter(q("enter", "1", "allow_sit_down", "true")), "sit.powerSave.enter.allowed");

        // raw frames: F8 8F LEN SRC DST CMD PAYLOAD CHK ED (CHK = sum LEN..PAYLOAD)
        final byte[] f19off = frame(0x19, 0x00);
        final byte[] f19on = frame(0x19, 0x01);
        final byte[] f19empty = frame(0x19);
        final byte[] f40enter = frame(0x40, 0x01, 0x01);
        final byte[] f40enterOne = frame(0x40, 0x01);
        final byte[] f40leave = frame(0x40, 0x00, 0x00);
        final byte[] f40empty = frame(0x40);
        final byte[] fRead = frame(0x06, 0x03);
        eq("f8 8f 08 05 00 19 00 26 ed", hex(f19off), "sit.frame.layout"); // alpha2-python test frame
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", f19off); } }, "sit.raw.19off");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", f19empty); } }, "sit.raw.19empty");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", f40enter); } }, "sit.raw.40enter");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", f40enterOne); } }, "sit.raw.40enterOne");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", f40empty); } }, "sit.raw.40empty");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", concat(fRead, f19off)); } }, "sit.raw.batchSecond");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", concat(new byte[]{0x00, (byte) 0xF8}, f19off)); } }, "sit.raw.junkBefore");
        // the legacy short form F8 8F LEN CMD PAYLOAD CHK ED (LEN counts CMD)
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", shortFrame(0x19, 0x00)); } }, "sit.raw.short19off");
        // allowed
        int okBefore = failed;
        ApiValidator.checkSerialSendSitDown(q(), "chest", f19on);
        ApiValidator.checkSerialSendSitDown(q(), "chest", f40leave);
        ApiValidator.checkSerialSendSitDown(q(), "chest", fRead);
        ApiValidator.checkSerialSendSitDown(q(), "chest", concat(fRead, f19on, f40leave));
        ApiValidator.checkSerialSendSitDown(q(), "chest", new byte[0]);
        ApiValidator.checkSerialSendSitDown(q("allow_sit_down", "true"), "chest", f19off);
        ApiValidator.checkSerialSendSitDown(q("allow_sit_down", "true"), "chest", f40enter);
        ApiValidator.checkSerialSendSitDown(q(), "head", f19off); // head cmd 0x19 is another command
        eq(okBefore, failed, "sit.raw.allowed");
        // alpha2-brain#25: whole frames only. Nobody checked that the chest firmware drops a
        // frame with a bad checksum, so the guard refuses it.
        final byte[] badSum = f19off.clone();
        badSum[badSum.length - 2] ^= 0x01;
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", badSum); } }, "sit.raw.badChecksum");
        final byte[] badSum40 = f40enter.clone();
        badSum40[badSum40.length - 2] ^= 0x01;
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", badSum40); } }, "sit.raw.badChecksum40");
        final byte[] badSumRead = fRead.clone();
        badSumRead[badSumRead.length - 2] ^= 0x01;
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", badSumRead); } }, "sit.raw.badChecksumRead");
        final byte[] badTail = f19off.clone();
        badTail[badTail.length - 1] = 0x00;
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", badTail); } }, "sit.raw.badTail");
        final byte[] badLen = f19off.clone();
        badLen[2] = 0x09; // LEN says one more byte than the data has
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", badLen); } }, "sit.raw.badLength");
        // a frame split over two debug/serial/send calls: each part is refused
        final byte[] partA = java.util.Arrays.copyOfRange(f19off, 0, 6);  // F8 8F 08 05 00 19
        final byte[] partB = java.util.Arrays.copyOfRange(f19off, 6, 9);  // 00 26 ED
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", partA); } }, "sit.split.first");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", partB); } }, "sit.split.second");
        final byte[] onlyF8 = new byte[]{(byte) 0xF8};
        final byte[] restAfterF8 = java.util.Arrays.copyOfRange(f19off, 1, 9);
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", onlyF8); } }, "sit.split.onlyF8");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", restAfterF8); } }, "sit.split.after8F");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", new byte[]{(byte) 0xF8, (byte) 0x8F}); } }, "sit.raw.headerOnly");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", concat(fRead, partA)); } }, "sit.split.afterGoodFrame");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", concat(fRead, new byte[]{0x01})); } }, "sit.raw.junkAfter");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.checkSerialSendSitDown(q(), "chest", concat(new byte[]{0x00}, partA)); } }, "sit.split.afterPadding");
        // the opt-in sends the bytes without a check; the head port has no check
        int okBefore2 = failed;
        ApiValidator.checkSerialSendSitDown(q("allow_sit_down", "true"), "chest", partA);
        ApiValidator.checkSerialSendSitDown(q("allow_sit_down", "true"), "chest", badSum);
        ApiValidator.checkSerialSendSitDown(q(), "head", partA);
        ApiValidator.checkSerialSendSitDown(q(), "chest", concat(fRead, fRead, fRead));
        ApiValidator.checkSerialSendSitDown(q(), "chest", concat(fRead, new byte[]{0x00})); // set_uuid form
        ApiValidator.checkSerialSendSitDown(q(), "chest", concat(new byte[]{0x00, 0x00}, fRead, new byte[]{0x00}, fRead));
        eq(okBefore2, failed, "sit.whole.allowed");
        eq(null, com.ubtechinc.alpha.hardware.ChestSafety.checkWholeFrames(new byte[0]), "sit.whole.empty");
        eq(null, com.ubtechinc.alpha.hardware.ChestSafety.checkWholeFrames(
                new byte[]{(byte) 0xF8, (byte) 0x8F, 0x07, 0x00, 0x00, 0x33, 0x3A, (byte) 0xED}), "sit.whole.versionFrame");
        eq(null, com.ubtechinc.alpha.hardware.ChestSafety.checkWholeFrames(shortFrame(0x06, 0x03)), "sit.whole.short");
        eq(null, com.ubtechinc.alpha.hardware.ChestSafety.checkCommand(0x19, new byte[]{1}), "sit.cmd.19on");
        eq(com.ubtechinc.alpha.hardware.ChestSafety.SIT_DOWN_19,
                com.ubtechinc.alpha.hardware.ChestSafety.checkCommand(0x19, new byte[]{0}), "sit.cmd.19off");
        eq(com.ubtechinc.alpha.hardware.ChestSafety.SIT_DOWN_40,
                com.ubtechinc.alpha.hardware.ChestSafety.checkCommand(0x40, new byte[]{1, 0}), "sit.cmd.40enter");

        // ── 回應 helpers ──
        eq(true, ApiValidator.okTrue().body.contains("\"ok\":true"), "okTrue.body");
        eq(200, ApiValidator.ok("{\"ok\":true}").status, "ok.status");
        eq(true, ApiValidator.error("boom").body.contains("boom"), "error.body");

        System.out.println("ApiValidatorTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
