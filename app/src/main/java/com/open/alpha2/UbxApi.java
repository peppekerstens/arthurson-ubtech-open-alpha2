package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.ubx.UbxFile;
import com.ubtechinc.alpha.hardware.ubx.UbxParser;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;

/**
 * Shared implementation of Ubx playback and single-servo direct drive.
 */
public final class UbxApi {
    private static final String TAG = "UbxApi";

    private final Context appContext;
    private final UbxPlayer ubxPlayer;
    private final ActionDirect actionDirect;
    private final ChestQuery chestQuery;

    public UbxApi(Context context, UbxPlayer ubxPlayer, ActionDirect actionDirect, ChestQuery chestQuery) {
        this.appContext = context.getApplicationContext();
        this.ubxPlayer = ubxPlayer;
        this.actionDirect = actionDirect;
        this.chestQuery = chestQuery;
    }

    public HttpServer.ApiResponse ubxListResponse() {
        java.io.File dir = new java.io.File("/sdcard/actions");
        String[] names = dir.list();
        if (names == null) return HttpServer.ApiResponse.error("no /sdcard/actions");
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"files\":[");
        boolean first = true;
        for (String n : names) {
            java.io.File f = new java.io.File(dir, n);
            if (!f.isFile()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(MainActivity.jsonSafe(n)).append("\",\"size\":").append(f.length()).append('}');
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    /** Allow only .ubx files under /sdcard/actions (blocks LAN reads of arbitrary files via path/name). */
    private static boolean isAllowedUbxFile(java.io.File f) {
        if (f == null) return false;
        try {
            String target = f.getCanonicalPath();
            String base = new java.io.File("/sdcard/actions").getCanonicalPath();
            if (target.equals(base)) return false;
            if (!target.startsWith(base + java.io.File.separator)) return false;
            return target.toLowerCase(java.util.Locale.US).endsWith(".ubx");
        } catch (Exception e) {
            return false;
        }
    }

    public HttpServer.ApiResponse ubxPlayResponse(String name, String p) {
        java.io.File f;
        if (p != null) {
            f = new java.io.File(p);
            if (!isAllowedUbxFile(f)) return HttpServer.ApiResponse.error("path must be a .ubx under /sdcard/actions");
        } else if (name != null) {
            // name must be a file name only; any path (../../, /, \) is rejected.
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                return HttpServer.ApiResponse.error("invalid name");
            }
            f = new java.io.File("/sdcard/actions/" + name);
            if (!isAllowedUbxFile(f)) return HttpServer.ApiResponse.error("invalid name");
        } else return HttpServer.ApiResponse.error("name or path required");
        if (!f.isFile()) return HttpServer.ApiResponse.error("not found");
        HardwareDirectManager dm = HardwareDirectManager.get(appContext);
        if (!dm.chest().isAvailable()) return HttpServer.ApiResponse.error("chest not available");
        UbxFile ubx;
        try {
            ubx = UbxParser.parseFile(f);
        } catch (Exception e) {
            return HttpServer.ApiResponse.error("parse failed: " + e.getMessage());
        }
        ubxPlayer.stop(); // preempt: stop the old file, play the new one
        boolean started = ubxPlayer.play(ubx, f.getName(), dm.chest(), f);
        if (!started) return HttpServer.ApiResponse.error("cannot start: " + ubxPlayer.lastError());
        actionDirect.setLastPlayedFile(f);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"name\":\"" + MainActivity.jsonSafe(f.getName())
                + "\",\"total\":" + ubxPlayer.total() + "}");
    }

    public HttpServer.ApiResponse ubxSpeedResponse(String v) {
        float f = ApiValidator.parseUbxSpeedValue(v);
        if (!ubxPlayer.setSpeed(f)) {
            throw new IllegalArgumentException("parameter 'value' must be one of [0.5, 0.67, 1, 1.5, 2], got: " + v);
        }
        // Take effect immediately during playback: replay the same file from the start at the new speed (internal snapshot isolation; the old plan hands over safely).
        boolean restarted = false;
        java.io.File last = actionDirect.getLastPlayedFile();
        if (ubxPlayer.isPlaying() && last != null && last.isFile()) {
            try {
                UbxFile rubx = UbxParser.parseFile(last);
                HardwareDirectManager rdm = HardwareDirectManager.get(appContext);
                if (rdm.chest().isAvailable()) {
                    ubxPlayer.stop();
                    restarted = ubxPlayer.play(rubx, last.getName(), rdm.chest(), last);
                    if (restarted) actionDirect.setLastPlayedFile(last);
                }
            } catch (Exception e) {
                Log.w(TAG, "speed restart failed", e);
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"code\":\"API_ERROR_SUCCEED\",\"speed\":" + ubxPlayer.getSpeed()
                + ",\"restarted\":" + restarted + "}");
    }

    public HttpServer.ApiResponse ubxStopResponse() {
        ubxPlayer.stop();
        return HttpServer.ApiResponse.okTrue();
    }

    public HttpServer.ApiResponse ubxStatusResponse() {
        return HttpServer.ApiResponse.ok(ubxPlayer.statusJson());
    }

    // -- Servo single-servo direct send (cmd05) ----------------------------------------------
    // The official PC tuner test proved that cmd05 (05 00 header) drives this
    // robot's servos correctly; the user confirmed by eye. The old full-frame
    // cmd03 write has a safety problem: it fills the other 19 axes from pose
    // tracking (dead reckoning). Once the tracked values go stale (after a
    // restart or a dance), all 19 servos jump to a wrong pose at once --
    // "write it and it goes crazy". cmd05 moves only the target servo and
    // never touches the other axes, so it is safe by design. It also no longer
    // requires a known pose before moving.
    /** Send one servo directly via cmd05; FAILED only when the serial port is unavailable. */
    public UbxErrorCode.API_ERROR_CODE servoSendOneCode(int id, int angle, int timeMs) {
        if (id < ApiValidator.SERVO_ID_MIN || id > ApiValidator.SERVO_ID_MAX) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        if (angle < ApiValidator.SERVO_ANGLE_MIN) angle = ApiValidator.SERVO_ANGLE_MIN;
        if (angle > ApiValidator.SERVO_ANGLE_MAX) angle = ApiValidator.SERVO_ANGLE_MAX;
        if (timeMs < ApiValidator.SERVO_TIME_MIN_MS) timeMs = ApiValidator.SERVO_TIME_MIN_MS;
        boolean sent = HardwareDirectManager.get(appContext).chest()
                .setSingleServo((byte) id, angle, (short) timeMs);
        if (!sent) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        // Pose tracking: record this axis' commanded value (per-axis known; used by the commanded-pose board / commanded readback).
        ubxPlayer.notePoseOne(id, angle & 0xFF);
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    public HttpServer.ApiResponse servoSendOne(int id, int angle, int timeMs) {
        UbxErrorCode.API_ERROR_CODE code = servoSendOneCode(id, angle, timeMs);
        if (code != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            return HttpServer.ApiResponse.error("direct not ready");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + id + ",\"angle\":" + (angle & 0xFF) + "}");
    }

    // -- Servo endpoint response layer --
    // directChestReady() is a thin delegate (implementation in DirectProbes).
    private boolean directChestReady() {
        return DirectProbes.isChestReady(appContext);
    }

    public HttpServer.ApiResponse servoOneResponse(Map<String, String> query) {
        // pure-direct: single servo sent directly via cmd05.
        // Optional trim parameter -- when present, also write the chest EEPROM
        // via cmd12 (the same persistence the official tuner uses; it survives
        // a power cut. A wrong write changes the factory calibration, so use
        // it only when you know what you are doing).
        int id = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        // Validate explicitly against the spec/MCP (angle 0-255, time
        // 20-32767); out of range returns 400.
        int angle = ApiValidator.requireIntRange(query, "angle", ApiValidator.SERVO_ANGLE_MIN, ApiValidator.SERVO_ANGLE_MAX);
        int time = ApiValidator.optionalIntRange(query, "time", ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 1000);
        Integer trim = null;
        if (query.containsKey("trim") && query.get("trim") != null && !query.get("trim").isEmpty()) {
            try {
                trim = Integer.parseInt(query.get("trim").trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("parameter 'trim' must be an integer");
            }
            if (trim < -1000 || trim > 1000) {
                throw new IllegalArgumentException("parameter 'trim' must be within [-1000,1000]");
            }
        }
        boolean ok = servoSendOneCode(id, angle, time) == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        if (!ok || trim == null) {
            return MainActivity.sentReadyResponse(ok, directChestReady());
        }
        Boolean written = writeServoTrimLive(id, trim);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"code\":\"API_ERROR_SUCCEED\"");
        sb.append(",\"bindReady\":").append(directChestReady());
        sb.append(",\"id\":").append(id).append(",\"angle\":").append(angle & 0xFF);
        sb.append(",\"trim\":").append(trim);
        // written==null (the official service ate the whole reply) is not the
        // same as a failed write -- id2 stores the value even with zero ACK.
        // Report unknown, not failure, and let the frontend rescan to verify.
        sb.append(",\"trimWritten\":").append(written != null && written);
        sb.append(",\"trimUnknown\":").append(written == null);
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoAllResponse(Map<String, String> query) {
        int[] angles = ApiValidator.requireAngles20(query);
        int time = ApiValidator.optionalIntRange(query, "time", ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 1000);
        // setAllServos already converts to cmd03 internally (cmd52 gives an ACK but no motion).
        boolean sent = HardwareDirectManager.get(appContext).chest().setAllServos(angles, (short) time);
        if (sent) ubxPlayer.notePose(angles);
        return MainActivity.sentReadyResponse(sent, directChestReady());
    }

    public HttpServer.ApiResponse servoReadResponse(Map<String, String> query) {
        // Live read of one servo (cmd 13): the same query the official PC
        // tuner sends. Note: the reply is the trim stored in the chest (an
        // offset; the official ratio is angle:offset = 1:3), <b>not</b> an
        // absolute angle. Example: servo 8 keeps pose 65 and reads back -33
        // across several actions (see the official session logcat comparison).
        // trim is the raw offset value; the frontend shows it as-is, no need
        // to subtract home again.
        // When no read is possible (timeout, or the servo returns a 01 error,
        // e.g. servo 5/6 hardware fault on this robot; finger servos 17/18
        // have no feedback by design), report ok:false as-is, never invent 0.
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        Integer live = readServoLive(idInt);
        Integer commanded = ubxPlayer.poseBoxed()[idInt - 1];
        if (live == null) {
            StringBuilder sb = new StringBuilder("{\"ok\":false,\"id\":" + idInt + ",\"live\":false");
            sb.append(",\"commanded\":").append(commanded != null ? commanded : "null");
            sb.append(",\"known\":").append(commanded != null);
            sb.append(",\"error\":\"no feedback (timeout or faulty servo)\"}");
            return HttpServer.ApiResponse.ok(sb.toString());
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + idInt
                + ",\"trim\":" + live + ",\"live\":true"
                + ",\"commanded\":" + (commanded != null ? commanded : "null")
                + ",\"known\":" + (commanded != null) + "}");
    }

    /** 100ms gap between the 22 consecutive reads (identical in servoReadAll / servoAngleAll; an interruption returns false so the caller breaks). */
    private static boolean sleepBetweenServos() {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return true;
    }

    /** Build the failed list for the 20 consecutive reads (identical in both places): comma-separated with positions. Always returns false; the caller puts it back into firstFail. */
    private static boolean appendFailed(StringBuilder failed, boolean firstFail, int i) {
        if (!firstFail) failed.append(',');
        failed.append(i);
        return false;
    }

    public HttpServer.ApiResponse servoReadAllResponse() {
        // Read the trim of all 22 servos in a row (the official tuner's
        // pace: roughly a dozen ms apart per servo).
        // trims[i] = the raw offset stored in the chest for that axis. A
        // servo that cannot be read is recorded as null and listed in
        // failed.
        // Note: this is not an absolute angle; do not paste it into the
        // angle input box.
        // Always wait 100ms between servos (success or failure).
        Integer[] trims = new Integer[ApiValidator.SERVO_COUNT];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            Integer v = readServoLive(i);
            trims[i - 1] = v;
            if (v == null) {
                firstFail = appendFailed(failed, firstFail, i);
            }
            if (i < ApiValidator.SERVO_COUNT) {
                if (!sleepBetweenServos()) break;
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"live\":true,\"trims\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(trims[i] != null ? trims[i] : "null");
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoAngleResponse(Map<String, String> query) {
        // Live read of one servo's absolute angle (cmd 6). Same unit as
        // servo/one; measured tracking error about 1 degree. Note: this is
        // not the trim/offset from servo/read.
        // When no read is possible (timeout), report ok:false as-is, never
        // invent 0.
        // Note: the read itself drops the torque of that servo (firmware
        // behavior, confirmed on two robots). To re-engage torque, call
        // servo/angle-restore (atomic read-write) or simply move it.
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        Integer angle = readServoAbsLive(idInt);
        if (angle == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + idInt
                + ",\"angle\":" + angle + "}");
    }

    public HttpServer.ApiResponse servoAngleRestoreResponse(Map<String, String> query) {
        // Atomic read plus immediate write-back. The cmd6 read drops the
        // torque of that servo (see above); right after the read, write the
        // same position back with servo/one (cmd05) to re-engage torque.
        // Everything happens inside the backend, with no round trip through
        // the browser: the torque-free window is only a few tens of
        // milliseconds, too short to fall, and the motion is invisible.
        // time uses the minimum 20ms: purely to re-engage torque fast, not
        // to move.
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        Integer angle = readServoAbsLive(idInt);
        if (angle == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        boolean restored = servoSendOneCode(idInt, angle, 20)
                == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + idInt
                + ",\"angle\":" + angle + ",\"restored\":" + restored + "}");
    }

    public HttpServer.ApiResponse servoBackAngleResponse(Map<String, String> query) {
        // Live read of one servo's readback angle (cmd 23 / 0x17, the same
        // as the PC tool's "read angle 6"). Same unit as servo/one. Note:
        // this is not the trim/offset from servo/read.
        // When no read is possible (timeout), report ok:false as-is, never
        // invent 0.
        // Note: it is unknown whether the 0x17 read drops torque (0x06 does).
        // The first version is a pure read without write-back; watch the
        // robot during testing before deciding.
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        Integer angle = readServoBackLive(idInt);
        if (angle == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + idInt
                + ",\"angle\":" + angle + "}");
    }

    public HttpServer.ApiResponse servoModifyIdResponse(java.util.Map<String, String> query) {
        int id = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        int newId = ApiValidator.requireIntRange(query, "newId", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        if (id == newId) {
            throw new IllegalArgumentException("parameter 'newId' must differ from 'id', got: " + newId);
        }
        if (chestQuery == null || !directChestReady()) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + id
                    + ",\"newId\":" + newId + ",\"error\":\"direct not ready\"}");
        }
        Boolean ack = chestQuery.modifyServoId(id, newId, 800);
        if (ack != null && ack) {
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + id + ",\"newId\":" + newId + ",\"ack\":true}");
        }
        if (ack != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + id + ",\"newId\":" + newId
                    + ",\"ack\":false,\"error\":\"servo NAK (no feedback for id " + id + ")\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + id + ",\"newId\":" + newId
                + ",\"error\":\"timeout (sent, no chest ack; verify by moving new ID)\"}");
    }

    /**
     * Best-effort parse of a cmd14 reply payload into a version string.
     * Layout is UNVERIFIED (first real-device reads decide): skip payload[0]
     * (cmd echo), trim trailing 0x00, then longest printable ASCII run.
     * Returns null when nothing string-like is found (caller still has rawHex).
     */
    static String parseMotorVersion(byte[] payload) {
        if (payload == null || payload.length < 2) return null;
        int n = payload.length;
        int end = n;
        while (end > 1 && payload[end - 1] == 0) end--;
        if (end <= 1) return null;
        int bestStart = -1, bestLen = 0, curStart = -1;
        for (int i = 1; i <= end; i++) {
            boolean printable = i < end && (payload[i] & 0xFF) >= 0x20 && (payload[i] & 0xFF) <= 0x7E;
            if (printable) {
                if (curStart == -1) curStart = i;
            } else if (curStart != -1) {
                if (i - curStart > bestLen) { bestLen = i - curStart; bestStart = curStart; }
                curStart = -1;
            }
        }
        // Single stray printable bytes (e.g. #1 sample "0e 00 01 30 17 04 06"
        // where 0x30='0' is likely binary, not text) must not become fake
        // versions: require at least 2 consecutive printable chars.
        if (bestLen < 2) return null;
        try {
            return new String(payload, bestStart, bestLen, java.nio.charset.StandardCharsets.US_ASCII).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** cmd14 short error frame [0e, 01, id] = servo no feedback (same convention
     *  as cmd13 read / cmd12 trim error frames; seen on 5/6 faulty, 17/18 finger,
     *  21/22 absent servos). Real-device verified 2026-10-12. */
    static boolean isMotorVersionError(byte[] payload) {
        return payload != null && payload.length == 3 && (payload[0] & 0xFF) == 14 && payload[1] == 1;
    }

    /** Servo model from version-field byte0 (user-confirmed ground truth,
     *  real-device 2026-10-13: all 8kg servos reply B0=0x53, all 20kg B0=0x30).
     *  8kg = [2,3,5,6,17,18,19,20]; 20kg = [1,4,7-16]; 21/22 unknown (absent).
     *  Returns "8kg"/"20kg", or null when undecodable (incl. error frames). */
    static String motorModel(byte[] payload) {
        if (payload == null || payload.length < 4 || isMotorVersionError(payload)) return null;
        int b0 = payload[3] & 0xFF;
        if (b0 == 0x53) return "8kg";
        if (b0 == 0x30) return "20kg";
        return null;
    }

    /** Hex of the version field (bytes after cmd/status/id), shown until the
     *  4-byte layout (e.g. "30 17 04 06") is decoded against official data. */
    static String motorVersionFieldHex(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < payload.length; i++) {
            if (i > 3) sb.append(' ');
            int v = payload[i] & 0xFF;
            sb.append(Character.forDigit((v >>> 4) & 0xF, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    static String toHexBytes(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            int v = b[i] & 0xFF;
            sb.append(Character.forDigit((v >>> 4) & 0xF, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    public HttpServer.ApiResponse servoVersionResponse(java.util.Map<String, String> query) {
        // Single servo firmware version (chest cmd14 live query, pure read).
        // Reply layout unverified: response carries both parsed version (best
        // effort, may be null) and rawHex (authoritative until verified).
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorVersionRaw(idInt, 500);
        if (raw == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        if (isMotorVersionError(raw)) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"rawHex\":\"" + toHexBytes(raw) + "\""
                    + ",\"error\":\"no feedback (servo error frame - faulty/disconnected servo)\"}");
        }
        String ver = parseMotorVersion(raw);
        if (ver == null || ver.isEmpty()) ver = motorVersionFieldHex(raw);
        String model = motorModel(raw);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"id\":" + idInt);
        sb.append(",\"rawHex\":\"").append(toHexBytes(raw)).append("\"");
        sb.append(",\"model\":").append(model != null ? ("\"" + model + "\"") : "null");
        if (ver != null && !ver.isEmpty()) sb.append(",\"version\":\"").append(MainActivity.jsonSafe(ver)).append("\"");
        else sb.append(",\"version\":null");
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoVersionAllResponse() {
        // One-click read of all 22 servo firmware versions (cmd14, pure read).
        // First pass single attempt per servo (500ms) with 100ms gaps, then one
        // retry pass over failed ids only; persistent failures go into failed
        // with null slots (same shape as servo/read-all).
        String[] versions = new String[ApiValidator.SERVO_COUNT];
        String[] raws = new String[ApiValidator.SERVO_COUNT];
        String[] models = new String[ApiValidator.SERVO_COUNT];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorVersionRaw(i, 500);
            if (raw == null || isMotorVersionError(raw)) {
                versions[i - 1] = null;
                raws[i - 1] = raw != null ? toHexBytes(raw) : null;
                if (!firstFail) failed.append(',');
                failed.append(i);
                firstFail = false;
            } else {
                String ver = parseMotorVersion(raw);
                versions[i - 1] = (ver != null && !ver.isEmpty()) ? ver : motorVersionFieldHex(raw);
                raws[i - 1] = toHexBytes(raw);
                models[i - 1] = motorModel(raw);
            }
            if (i < ApiValidator.SERVO_COUNT) {
                if (!sleepBetweenServos()) break;
            }
        }
            // One retry pass over failed ids only (servo bus reads are flaky:
            // 12/13/14/16 answered one scan and error-framed the next, real-device
            // 2026-10-13). Keeps the common all-good case at single-pass speed.
            java.util.ArrayList<Integer> retryIds = new java.util.ArrayList<Integer>();
            for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
                if (versions[i - 1] == null) retryIds.add(i);
            }
            if (!retryIds.isEmpty()) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int id : retryIds) {
                    if (Thread.currentThread().isInterrupted()) break;
                    byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorVersionRaw(id, 500);
                    if (raw != null && !isMotorVersionError(raw)) {
                        String v2 = parseMotorVersion(raw);
                        versions[id - 1] = (v2 != null && !v2.isEmpty()) ? v2 : motorVersionFieldHex(raw);
                        raws[id - 1] = toHexBytes(raw);
                        models[id - 1] = motorModel(raw);
                    }
                }
                failed = new StringBuilder("[");
                firstFail = true;
                for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
                    if (versions[i - 1] == null) {
                        if (!firstFail) failed.append(',');
                        failed.append(i);
                        firstFail = false;
                    }
                }
            }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"versions\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(versions[i] != null ? ("\"" + MainActivity.jsonSafe(versions[i]) + "\"") : "null");
        }
        sb.append("],\"rawHex\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(raws[i] != null ? ("\"" + raws[i] + "\"") : "null");
        }
        sb.append("],\"models\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(models[i] != null ? ("\"" + models[i] + "\"") : "null");
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoHardwareResponse(java.util.Map<String, String> query) {
        // Single servo hardware info (chest cmd33 live query, pure read).
        // Reply layout unverified: hw is best-effort (may be null), rawHex authoritative.
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorHardwareRaw(idInt, 500);
        if (raw == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        if (raw.length == 3 && raw[1] == 1) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"rawHex\":\"" + toHexBytes(raw) + "\""
                    + ",\"error\":\"no feedback (servo error frame - unsupported or faulty servo)\"}");
        }
        String hw = parseMotorVersion(raw);
        if (hw == null || hw.isEmpty()) hw = motorVersionFieldHex(raw);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"id\":" + idInt);
        sb.append(",\"rawHex\":\"").append(toHexBytes(raw)).append("\"");
        if (hw != null && !hw.isEmpty()) sb.append(",\"hw\":\"").append(MainActivity.jsonSafe(hw)).append("\"");
        else sb.append(",\"hw\":null");
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoHardwareAllResponse() {
        // One-click read of all 22 servo hardware infos (cmd33, pure read).
        // First pass single attempt per servo (500ms) with 100ms gaps, then one
        // retry pass over failed ids only.
        String[] hws = new String[ApiValidator.SERVO_COUNT];
        String[] raws = new String[ApiValidator.SERVO_COUNT];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorHardwareRaw(i, 500);
            if (raw == null) {
                hws[i - 1] = null;
                raws[i - 1] = null;
                if (!firstFail) failed.append(',');
                failed.append(i);
                firstFail = false;
            } else {
                String hw = parseMotorVersion(raw);
                hws[i - 1] = (hw != null && !hw.isEmpty()) ? hw : motorVersionFieldHex(raw);
                raws[i - 1] = toHexBytes(raw);
            }
            if (i < ApiValidator.SERVO_COUNT) {
                if (!sleepBetweenServos()) break;
            }
        }
        java.util.ArrayList<Integer> retryIds = new java.util.ArrayList<Integer>();
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            if (hws[i - 1] == null) retryIds.add(i);
        }
        if (!retryIds.isEmpty()) {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int id : retryIds) {
                if (Thread.currentThread().isInterrupted()) break;
                byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorHardwareRaw(id, 500);
                if (raw != null) {
                    String hw = parseMotorVersion(raw);
                    hws[id - 1] = (hw != null && !hw.isEmpty()) ? hw : motorVersionFieldHex(raw);
                    raws[id - 1] = toHexBytes(raw);
                }
            }
            failed = new StringBuilder("[");
            firstFail = true;
            for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
                if (hws[i - 1] == null) {
                    if (!firstFail) failed.append(',');
                    failed.append(i);
                    firstFail = false;
                }
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"hws\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(hws[i] != null ? ("\"" + MainActivity.jsonSafe(hws[i]) + "\"") : "null");
        }
        sb.append("],\"rawHex\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(raws[i] != null ? ("\"" + raws[i] + "\"") : "null");
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoBackAngleAllResponse() {
        // Read the readback angle of all 22 servos in a row (cmd 23 / 0x17):
        // one shot per servo, no gap, no retry, no write-back.
        // Measured: 20 servos answered within 230ms (16 with values +
        // 5/6/17/18 with short error frames); no waiting needed. Real-device
        // test on 2026-10-14 confirmed that 21/22 also have feedback (51/5),
        // so read 21/22 as well.
        // A servo that cannot be read is recorded as null in failed.
        // Hold the robot steady while reading (it is unknown whether 0x17
        // drops torque; the first version is a pure read).
        final int n = ApiValidator.SERVO_COUNT;
        Integer[] angles = new Integer[n];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= n; i++) {
            Integer v = (chestQuery == null) ? null : chestQuery.queryServoBackAngle(i, 250);
            angles[i - 1] = v;
            if (v == null) {
                firstFail = appendFailed(failed, firstFail, i);
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"live\":true,\"angles\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(angles[i] != null ? angles[i] : "null");
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoAngleAllResponse() {
        // Read all 22 servos in a row plus an immediate write-back per servo
        // (each read-write pair is atomic; 100ms between servos).
        // Background: a pure consecutive read (without write-back) was
        // confirmed on two robots to break the output of servos one by one.
        // A single-servo read plus immediate write-back was confirmed to
        // cause no torque drop and no motion, so the consecutive version
        // performs the same atomic operation per servo. A servo that cannot
        // be read is not written back and is recorded as null in failed.
        // Unknown whether it can stay standing the whole time -- under
        // testing.
        Integer[] angles = new Integer[ApiValidator.SERVO_COUNT];
        boolean[] restored = new boolean[ApiValidator.SERVO_COUNT];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            Integer v = readServoAbsLive(i);
            angles[i - 1] = v;
            if (v == null) {
                firstFail = appendFailed(failed, firstFail, i);
            } else {
                restored[i - 1] = servoSendOneCode(i, v, 20)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
            }
            if (i < ApiValidator.SERVO_COUNT) {
                if (!sleepBetweenServos()) break;
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"live\":true,\"angles\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(angles[i] != null ? angles[i] : "null");
        }
        sb.append("],\"restored\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(restored[i]);
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    /**
     * Live read of one servo's absolute angle with retry (3 attempts x 250ms;
     * 100ms between retries, the same level as the consecutive reads. Do not
     * hammer the chest serial port densely -- see the incident comment in
     * servoAngleAllResponse).
     */
    private Integer readServoAbsLive(final int id) {
        return retryServoLive(100, new ServoRetry<Integer>() {
            @Override public Integer attempt() { return chestQuery.queryServoAbsAngle(id, 250); }
        });
    }

    /**
     * Live read of one servo's readback angle with retry (cmd 23 / 0x17, 3
     * attempts x 250ms; 100ms between retries, the same level as the
     * consecutive reads. Do not hammer the chest serial port densely).
     */
    private Integer readServoBackLive(final int id) {
        return retryServoLive(100, new ServoRetry<Integer>() {
            @Override public Integer attempt() { return chestQuery.queryServoBackAngle(id, 250); }
        });
    }

    /** Shared retry skeleton (the three live* methods above were identical in
     *  wording before; only the query call / retry gap differs. All kept: 3
     *  attempts, stop at attempt==2, an interruption returns null). Returns
     *  the value on success, null on all-timeout / send-failure. */
    private interface ServoRetry<T> { T attempt(); }

    private <T> T retryServoLive(int sleepMs, ServoRetry<T> op) {
        if (chestQuery == null || !directChestReady()) return null;
        for (int attempt = 0; attempt < 3; attempt++) {
            T v = op.attempt();
            if (v != null) return v;
            if (attempt == 2) break;
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    /**
     * Live read of one servo with retry. The official alpha2services on the
     * robot still holds ttyS1 and can eat the reply bytes, so occasional
     * timeouts are expected. Hence 3 retries (the official ACK takes about
     * 10-15ms, so a 250ms timeout is generous; 100ms between retries. Do not
     * hammer the chest serial port densely).
     */
    private Integer readServoLive(final int id) {
        return retryServoLive(100, new ServoRetry<Integer>() {
            @Override public Integer attempt() { return chestQuery.queryServoAngle(id, 250); }
        });
    }

    /**
     * Trim write with retry (3 attempts x 250ms, the same level as the live
     * reads -- timeouts are common when the official service competes for
     * the reply bytes). Returns TRUE/FALSE (ACK semantics); null on
     * all-timeout / send-failure.
     */
    private Boolean writeServoTrimLive(final int id, final int trim) {
        return retryServoLive(30, new ServoRetry<Boolean>() {
            @Override public Boolean attempt() { return chestQuery.writeServoTrim(id, trim, 250); }
        });
    }
}


