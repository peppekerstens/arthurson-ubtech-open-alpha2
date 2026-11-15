package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.ubx.UbxFile;
import com.ubtechinc.alpha.hardware.ubx.UbxParser;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in action direct drive: actionInfo.txt + UbxPlayer.
 */
public final class ActionDirect {
    private static final String TAG = "ActionDirect";

    /** The "squat then stand up" recovery action played after the one-key stop-all. */
    public static final String STOP_RECOVERY_ACTION_ID = "1510818174706";

    /** 官方 Alpha2Services v1.1.7.3 `robotStandup()` 20 軸企姿（raw 0-255；
     *  官方 smali 係 signed byte：-51→205、-111→145、-121→135、-79→177。
     *  #19 官方原文係 -6→250，呢度跟 app 安全範圍改為 120。
     *  同 {@link IkPose#BOOT_POSE}／web app-ik.js {@code IK_BOOT_POSE} 一致，改要三邊一齊改。 */
    private static final int[] OFFICIAL_BOOT_STANDUP_POSE = {
            120, 205, 120, 120, 35, 120, 120, 63, 145, 135,
            120, 120, 177, 95, 105, 120, 120, 120, 120, 120
    };
    /** 官方 STANDUP_TOTAL_TIME（robotStandup 嘅 move 時間）。 */
    private static final int OFFICIAL_BOOT_STANDUP_TIME_MS = 2000;

    // actionInfo.txt 行格式（GBK 编码）：<fileId>##<nameCn>##<nameEn>##<type>，
    // 与旧 AIDL getActionList 行顺序不同（彼为 id/type/nameCn/nameEn），此处重排，
    // 前端收到的 JSON 形状与以前完全一致，app-actions.js 无需改动。
    private static final String ACTION_DIR = "/sdcard/actions";
    private static final String ACTION_INFO = "/sdcard/actions/actionInfo.txt";

    private final Context appContext;
    private final UbxPlayer ubxPlayer;

    private List<String[]> actionInfoCache; // 每项 [fileId, nameCn, nameEn, type]
    /** Cached parse of assets/web/xiaozhi_actions.json (203 動作, id/nameCn/nameEn) -
     *  loaded once lazily on first use rather than at onCreate(), since it's only
     *  needed if/when the XiaoZhi tab's play_action tool schema is actually requested.
     *  null until first load attempt; an empty (but non-null) list means the load was
     *  attempted and failed/produced nothing. */
    private volatile java.util.List<org.json.JSONObject> xiaozhiActionsCache;
    private volatile java.io.File lastPlayedFile;

    public ActionDirect(Context context, UbxPlayer ubxPlayer) {
        this.appContext = context.getApplicationContext();
        this.ubxPlayer = ubxPlayer;
    }

    /** The file currently or last played (used by ubx/speed replay while playing; may be null). */
    public java.io.File getLastPlayedFile() {
        return lastPlayedFile;
    }

    public void setLastPlayedFile(java.io.File f) {
        lastPlayedFile = f;
    }

    /** Clears the actionInfo.txt cache (call after an action pack is replaced; the next list reads the new file). */
    public synchronized void invalidateCache() {
        actionInfoCache = null;
    }

    private synchronized List<String[]> loadActionInfo() {
        if (actionInfoCache != null) return actionInfoCache;
        List<String[]> out = new ArrayList<>();
        try {
            java.io.File f = new java.io.File(ACTION_INFO);
            // Check the size first (missing, empty, or over 1MB is treated as an empty table).
            long flen = f.length();
            if (!f.isFile() || flen <= 0 || flen > 1024L * 1024L) {
                Log.w(TAG, "loadActionInfo skip unusual file len=" + flen);
                actionInfoCache = out;
                return out;
            }
            byte[] data = new byte[(int) flen];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                // Use the bytes actually read (a short read leaves NUL padding; UTF-8 strict must not fall back to GBK first).
                String text = decodeActionInfo(data, off);
                for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split("##", -1);
                if (cols.length < 4) continue;
                out.add(new String[]{cols[0].trim(), cols[1].trim(), cols[2].trim(), cols[3].trim()});
                }
            } finally {
                try { in.close(); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "loadActionInfo failed", e);
        }
        actionInfoCache = out;
        return out;
    }

    /** Decode actionInfo.txt: use strict UTF-8 when it succeeds, fall back to GBK only
     *  when it fails (GBK Chinese). new String(bytes,"UTF-8") never throws (bad bytes
     *  become U+FFFD), so a REPORT-mode CharsetDecoder is needed to tell the difference. */
    private static String decodeActionInfo(byte[] data, int len) throws Exception {
        try {
            java.nio.charset.CharsetDecoder dec = java.nio.charset.Charset.forName("UTF-8").newDecoder();
            dec.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return dec.decode(java.nio.ByteBuffer.wrap(data, 0, len)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(data, 0, len, "GBK");
        }
    }

    /** Reads assets/web/xiaozhi_actions.json as a catalog (shared by MCP fuzzy match and the random pool). */
    public java.util.List<org.json.JSONObject> loadXiaozhiActions() {
        java.util.List<org.json.JSONObject> cached = xiaozhiActionsCache;
        if (cached != null) return cached;
        java.util.List<org.json.JSONObject> result = new java.util.ArrayList<>();
        try (java.io.InputStream in = appContext.getAssets().open("web/xiaozhi_actions.json")) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            org.json.JSONArray arr = new org.json.JSONArray(buf.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getJSONObject(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "loadXiaozhiActions: failed to load assets/web/xiaozhi_actions.json: " + e);
        }
        xiaozhiActionsCache = result;
        return result;
    }

    /** Picks a random id from the "隨機短/隨機長/ACT*" action groups in
     *  xiaozhi_actions.json plus the head-gesture extras below - these are the
     *  robot's own pre-recorded filler-movement actions (the random groups hold 30 entries:
     *  隨機長1-10, 隨機短1-10 x 2 sets of ids, with duplicate names for the same
     *  name e.g. two "隨機短2" entries - both are valid, harmless to include
     *  twice in the pool; the ACT family holds 26 entries: ACT0-9 basic small movements +
     *  ACT2-1 through ACT13-3), meant for exactly this "play something to look alive" use case
     *  rather than reacting to any specific emotion/content. Groups matched by
     *  nameCn prefix rather than a hardcoded id list so this keeps working if
     *  xiaozhi_actions.json is regenerated with different ids; gesture extras
     *  are explicit ids (user-verified silent).
     *
     *  Two classes stay out of the pool (see the two tables below):
     *  - Sound actions: the firmware plays its own MP3/sound effect during playback
     *    (it clashes with TTS, and a sudden sound during a fallback answer is bad).
     *    Neither the repo nor the body files mark which actions are loud, so this is
     *    verified by ear on the real robot. When a new one is found, add its id to SOUND_ACTION_IDS.
     *  - Neuron stand-up actions (BackStand/FrontStand): not yet verified on the body.
     *    The robot may be lying down and suddenly stand up and hit something.
     *    They are not in the random groups; excluding them here is pure defense
     *    against a future JSON regeneration putting them in the wrong pool.
     *  Returns null (never throws) if the group is empty for any reason -
     *  caller must handle that as a normal "nothing to play" case, not a bug. */
    public String resolveRandomActionId() {
        java.util.List<org.json.JSONObject> actions = loadXiaozhiActions();
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (org.json.JSONObject a : actions) {
            String id = a.optString("id");
            String cn = a.optString("nameCn");
            boolean inRandomGroup = cn.startsWith("隨機短") || cn.startsWith("隨機長")
                    || cn.startsWith("ACT");
            boolean inBasicExtras = false;
            for (String eid : EXTRA_RANDOM_IDS) {
                if (eid.equals(id)) {
                    inBasicExtras = true;
                    break;
                }
            }
            if (!inRandomGroup && !inBasicExtras) continue;
            if (isNeuronAction(id, cn)) continue;
            boolean noisy = false;
            for (String sid : SOUND_ACTION_IDS) {
                if (sid.equals(id)) {
                    noisy = true;
                    break;
                }
            }
            if (noisy) continue;
            pool.add(id);
        }
        if (pool.isEmpty()) {
            Log.w(TAG, "resolveRandomActionId: pool empty after exclusions!");
            return null;
        }
        return pool.get(new java.util.Random().nextInt(pool.size()));
    }

    /** Random pool head-action extras: nod / head shake no / wave right hand / wave left hand
     *  (all verified silent) - user-specified as usable as random.
     *  Explicit ids, no name matching (survives a JSON regeneration that renames them).
     *  ACT0-9 and ACT2-1 through ACT13-3 follow the name-prefix rule above; no need to list them one by one. */
    private static final String[] EXTRA_RANDOM_IDS = {
            "1464835936012", "1464835936013", "1464835936017", "1464835936018",
    };

    /** 有聲效動作 id 表：桌面 UBX 目錄（alpha2/UBX/actions）有同名子目錄
     *  （入面有 mp3）者即播嗰陣韌體會自己出聲，共 87 個（全部已對過 203 catalog）。
     *  語意管線共用：SemanticCenter 播之前先 resolve 做真實 id 再查呢個表，
     *  有聲就成句 TTS 對白唔講（免同動作音效搶喇叭）；隨機 filler 池本身已驗證
     *  全 silent，唔使查但照行同一個判斷無妨。有新動作加落機先對一次 UBX 目錄。 */
    private static final String[] SOUND_ACTION_IDS = {
            "1464835936108",
            "1464835936109",
            "1464835936110",
            "1464835936111",
            "1464835936112",
            "1464835936113",
            "1464835936120",
            "1464835936121",
            "1464835936122",
            "1464835936123",
            "1464835936124",
            "1464835936125",
            "1464835936126",
            "1464835936127",
            "1464835936128",
            "1464835936129",
            "1466392694011",
            "1466392694013",
            "1466392694018",
            "1487230160190",
            "1487233706977",
            "1487234282378",
            "1487234521742",
            "1487234946100",
            "1487235353183",
            "1487236251311",
            "1487236451234",
            "1487236640811",
            "1487404045421",
            "1487404045422",
            "1487404045423",
            "1487404045424",
            "1487404045425",
            "1487404045427",
            "1496217022040",
            "1496218328201",
            "1496218474326",
            "1496218581505",
            "1496222666158",
            "1496223873017",
            "1497596360378",
            "1497601531838",
            "1509000679142",
            "1509000749494",
            "1509000823380",
            "1509000873942",
            "1509000925598",
            "1509000967649",
            "1509001004210",
            "1509001038579",
            "1509001083348",
            "1509001132466",
            "1509001176320",
            "1509001327218",
            "1509001368824",
            "1509001404283",
            "1509001441250",
            "1509001488632",
            "1509001526297",
            "1509001595235",
            "1509001630772",
            "1509004509884",
            "1509004572101",
            "1509004595409",
            "1509004621437",
            "1509004646036",
            "1509004671917",
            "1509004727034",
            "1509004756578",
            "1509004782446",
            "1509004810606",
            "1509004843153",
            "1509004868066",
            "1509004889922",
            "1509006885787",
            "1509006924534",
            "1509006974154",
            "1509007070001",
            "1509007097697",
            "1509007137500",
            "1510818517939",
            "1511247440171",
            "1511247506520",
            "1524572711082",
            "1524572829281",
            "1524573025741",
            "1524573159187",
    };

    /** Checks whether a real action id plays its own sound during playback (SOUND_ACTION_IDS above).
     *  null/empty string is always treated as silent (fail-safe: keep talking). */
    public static boolean isSoundAction(String id) {
        if (id == null || id.isEmpty()) return false;
        for (String sid : SOUND_ACTION_IDS) {
            if (sid.equals(id)) return true;
        }
        return false;
    }

    /** Neuron stand-up actions: the id or the Chinese/English name matches (the Blockly side
     *  calls them "Neuron stand-up actions", not yet tested on the body). */
    static boolean isNeuronAction(String id, String nameCn) {
        if ("BackStand".equals(id) || "FrontStand".equals(id)) return true;
        return "仰躺站立".equals(nameCn) || "趴著站立".equals(nameCn);
    }

    /** Resolves a human-supplied action name (Chinese or English, as passed by the
     *  XiaoZhi LLM to self.robot.play_action) to the actual on-device action id from
     *  xiaozhi_actions.json. Tried in order, first match wins:
     *  1. Exact id match (in case the caller *does* pass a raw id - still valid).
     *  2. Exact match against nameCn or nameEn (case-insensitive for nameEn).
     *  3. Substring match either direction (query contains the action name, or the
     *     action name contains the query) - handles the LLM paraphrasing slightly,
     *     catching the common case of extra/missing words around a name that
     *     otherwise matches exactly.
     *  Returns null if nothing matches closely enough - deliberately does not fall
     *  back to a "best guess" at low confidence, since a wrong action executing on
     *  physical hardware is worse than a clear "not found" the LLM can react to (see
     *  the "raise_left_hand" bug this whole mechanism exists to prevent). */
    public String resolveActionId(String query) {
        java.util.List<org.json.JSONObject> actions = loadXiaozhiActions();
        if (query == null) return null;
        String q = query.trim();
        if (q.isEmpty()) return null;

        for (org.json.JSONObject a : actions) {
            if (q.equals(a.optString("id"))) return a.optString("id");
        }
        for (org.json.JSONObject a : actions) {
            if (q.equals(a.optString("nameCn"))
                    || q.equalsIgnoreCase(a.optString("nameEn"))) {
                return a.optString("id");
            }
        }
        String qLower = q.toLowerCase(java.util.Locale.US);
        for (org.json.JSONObject a : actions) {
            String cn = a.optString("nameCn");
            String en = a.optString("nameEn").toLowerCase(java.util.Locale.US);
            if ((!cn.isEmpty() && (q.contains(cn) || cn.contains(q)))
                    || (!en.isEmpty() && (qLower.contains(en) || en.contains(qLower)))) {
                return a.optString("id");
            }
        }
        return null;
    }

    /** Action name/id resolution: fileId > nameEn > nameCn, plus same-directory xxx.ubx.
     *  A path with / only allows .ubx inside /sdcard/actions (canonical path locked). */
    private java.io.File resolveActionFile(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.endsWith(".ubx")) {
            java.io.File direct = n.indexOf('/') >= 0 || n.indexOf('\\') >= 0
                    ? new java.io.File(n) : new java.io.File(ACTION_DIR + "/" + n);
            try {
                String target = direct.getCanonicalPath();
                String base = new java.io.File(ACTION_DIR).getCanonicalPath();
                if (!target.equals(base) && target.startsWith(base + java.io.File.separator)
                        && target.toLowerCase(java.util.Locale.US).endsWith(".ubx")
                        && direct.isFile()) {
                    return direct;
                }
            } catch (Exception ignore) {}
            if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0) return null;
        }
        List<String[]> info = loadActionInfo();
        String id = null;
        for (String[] r : info) {
            if (r[0].equals(n)) { id = r[0]; break; }
        }
        if (id == null) {
            for (String[] r : info) {
                if (r[2].equalsIgnoreCase(n)) { id = r[0]; break; }
            }
        }
        if (id == null) {
            for (String[] r : info) {
                if (r[1].equals(n)) { id = r[0]; break; }
            }
        }
        if (id == null) return null;
        java.io.File f = new java.io.File(ACTION_DIR + "/" + id + ".ubx");
        return f.isFile() ? f : null;
    }

    public HttpServer.ApiResponse actionListDirect() {
        List<String[]> info = loadActionInfo();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"actions\":[");
        boolean first = true;
        for (String[] r : info) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(MainActivity.jsonSafe(r[0])).append("\",")
                    .append("\"type\":\"").append(MainActivity.jsonSafe(r[3])).append("\",")
                    .append("\"nameCn\":\"").append(MainActivity.jsonSafe(r[1])).append("\",")
                    .append("\"nameEn\":\"").append(MainActivity.jsonSafe(r[2])).append("\"}");
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    /** Whether UbxPlayer is playing anything (used by ColorTrackCenter walk-follow: while a walk
     *  action is playing, do not send head servos and do not stack new steps). */
    public boolean isPlaying() {
        try {
            return ubxPlayer.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The motion gate of the app (alpha2-brain#121). ApiDispatcher sets it. Without a gate
     *  every play call is refused: no gate means no approval. */
    private volatile MotionGate motionGate;

    public void setMotionGate(MotionGate gate) {
        motionGate = gate;
    }

    /** The refusal text, or null when the gate allows a whole action. Logs a refusal.
     *  The choice is in the pure function PlayRefusal (alpha2-brain#144), so a plain
     *  Java test can run it. */
    private String playRefusal(String what) {
        String why = PlayRefusal.refusalForPlay(motionGate, what);
        if (why != null) Log.w("MotionGate", "refused: " + why);
        return why;
    }

    /** Gate refusal of a whole-body call for the other classes that write servos (alpha2-brain#148).
     *  Null when allowed. Logs a refusal. */
    public String wholeBodyRefusal(String what) {
        return playRefusal(what);
    }

    /** Gate refusal of a move of one servo, or null. Logs a refusal (alpha2-brain#148). */
    public String servoRefusal(int servoId) {
        String why = PlayRefusal.refusalForServoMove(motionGate, servoId);
        if (why != null) Log.w("MotionGate", "refused: " + why);
        return why;
    }

    /** Gate refusal of a move of all servos, or null. Logs a refusal (alpha2-brain#148). */
    public String allServosRefusal() {
        String why = PlayRefusal.refusalForAllServosMove(motionGate);
        if (why != null) Log.w("MotionGate", "refused: " + why);
        return why;
    }

    public HttpServer.ApiResponse actionPlayDirect(String name) {
        String why = playRefusal("action/play");
        if (why != null) return new HttpServer.ApiResponse(403, "application/json; charset=utf-8", JsonUtil.okFalse(why));
        java.io.File f = resolveActionFile(name);
        if (f == null) return HttpServer.ApiResponse.error("unknown action: " + name);
        HardwareDirectManager dm = HardwareDirectManager.get(appContext);
        if (!dm.chest().isAvailable()) return HttpServer.ApiResponse.error("chest not available");
        UbxFile ubx;
        try {
            ubx = UbxParser.parseFile(f);
        } catch (Exception e) {
            return HttpServer.ApiResponse.error("parse failed: " + e.getMessage());
        }
        ubxPlayer.stop(); // preemption: playing a new action stops the old one
        if (!ubxPlayer.play(ubx, f.getName(), dm.chest(), f)) {
            return HttpServer.ApiResponse.error("cannot start: " + ubxPlayer.lastError());
        }
        lastPlayedFile = f;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"code\":\"API_ERROR_SUCCEED\",\"name\":\""
                + MainActivity.jsonSafe(f.getName()) + "\",\"total\":" + ubxPlayer.total() + "}");
    }

    /**
     * Shared internally: pure-direct play of a named action (fileId/Chinese or English name/xxx.ubx all
     * work), preemptive - stop the current one first, then play; matches the interruption semantics of
     * the stock playActionName. Shared by the gesture stop-all, the MCP tools, the semantic actions
     * and the random filler. HTTP action/play has its own "already playing is an error" guard, so it
     * does not go through this.
     */
    public UbxErrorCode.API_ERROR_CODE playActionDirect(String nameOrId) {
        // Motion gate (alpha2-brain#121): this call is the path of the voice chat, the semantic
        // actions, the gamepad, the colour track, the MCP tools and the stop recovery.
        if (playRefusal("playActionDirect(" + nameOrId + ")") != null) {
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
        java.io.File f = resolveActionFile(nameOrId);
        if (f == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        HardwareDirectManager dm = HardwareDirectManager.get(appContext);
        if (!dm.chest().isAvailable()) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        UbxFile ubx;
        try {
            ubx = UbxParser.parseFile(f);
        } catch (Exception e) {
            Log.w(TAG, "playActionDirect parse failed " + f, e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
        ubxPlayer.stop();
        if (!ubxPlayer.play(ubx, f.getName(), dm.chest(), f)) {
            Log.w(TAG, "playActionDirect not started: " + ubxPlayer.lastError());
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
        lastPlayedFile = f;
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    /**
     * 官方 v1.1.7.3 開機起身序列（參考 AlphaMainSeviceImpl.initThread）：
     *   1. robotStandup() 固定企姿 20 軸 raw + 2000ms——零檔案依賴，一開機就企。
     *   2. 之後先發官方開機握手：cmd 0x01 START（狀態查詢＋電源初始化，回 0x8A
     *      DC 狀態）＋ cmd 0x35[2] 查「充電時播放」（queryChargePlay 原文）。
     *      （官方次序就係企姿先，ChestStartThread 嗰兩條係 5s+ 之後先出，
     *      呢度等 2.2s 企姿 move 行完先補，唔阻住個 2s move。）
     *
     * 官方 robotStandup 用 cmd 0x34(52)。5-mic 本 repo 真機直驅實測 0x34 有
     * ACK 唔郁，故 5-mic 用同一 payload 行 cmd 0x03；2-mic 照官方用 cmd 0x34
     *（DirectChestController.setAllServos 內部按 HardwareVersion 分流）。
     *
     * 企姿發唔出（串口閂咗）短重試 3 次；一 send 得出即返 true——官方係
     * fire-and-forget，冇 ACK 可等，調用方唔好當 false 係「MCU 冇做」。
     */
    public boolean officialBootStandup() {
        // Motion gate (alpha2-brain#148): the stand-up pose moves all servos. With the gate off
        // no servo command goes out. The caller still sends START and the rest of the boot.
        String refused = PlayRefusal.refusalForBootStandup(motionGate);
        if (refused != null) {
            Log.w("MotionGate", "refused: " + refused);
            HardwareDirectManager gdm = HardwareDirectManager.get(appContext);
            if (gdm.chest().isAvailable()) {
                gdm.chest().sendStart();
                gdm.chest().queryChargePlay();
            }
            return false;
        }
        HardwareDirectManager dm = HardwareDirectManager.get(appContext);
        if (!dm.chest().isAvailable()) return false;
        boolean sent = false;
        for (int attempt = 0; attempt < 3 && !sent; attempt++) {
            if (dm.chest().setAllServos(OFFICIAL_BOOT_STANDUP_POSE,
                    (short) OFFICIAL_BOOT_STANDUP_TIME_MS)) {
                ubxPlayer.notePose(OFFICIAL_BOOT_STANDUP_POSE);
                sent = true;
                break;
            }
            Log.w(TAG, "official boot standup send failed, retry " + (attempt + 1) + "/3");
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (!sent) return false;
        // 等 2s 企姿 move 行完先發官方開機握手（同官方一樣係之後先出）。
        try {
            Thread.sleep(OFFICIAL_BOOT_STANDUP_TIME_MS + 200L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return true; // 企姿已經發出，握手唔發都唔改變結果
        }
        dm.chest().sendStart();
        dm.chest().queryChargePlay();
        // START／掃描可能令胸板重播上次動作（例如死機前最後一格 IK）。
        // 再送企姿兩次，確保開機最終停喺企姿，唔會「自己做返之前既動作」。
        for (int i = 0; i < 2; i++) {
            try {
                Thread.sleep(i == 0 ? 600L : 1200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return true;
            }
            if (dm.chest().setAllServos(OFFICIAL_BOOT_STANDUP_POSE,
                    (short) OFFICIAL_BOOT_STANDUP_TIME_MS)) {
                ubxPlayer.notePose(OFFICIAL_BOOT_STANDUP_POSE);
            }
        }
        return true;
    }

    /**
     * 内部共用：一键全停（动作部分）——截停 UbxPlayer 后补播 STOP_RECOVERY_ACTION_ID
     * 蹲下站起回位。回位播不播到不影响返回值。供 0x5e 手势（含拍头双 pad）、
     * MCP stop_action、HTTP action/stop 共用。
     */
    public UbxErrorCode.API_ERROR_CODE stopActionWithRecovery() {
        ubxPlayer.stop();
        try {
            UbxErrorCode.API_ERROR_CODE rec = playActionDirect(STOP_RECOVERY_ACTION_ID);
            if (rec != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                Log.w(TAG, "recovery not started: " + ubxPlayer.lastError());
            }
        } catch (Exception e) {
            Log.w(TAG, "recovery play failed", e);
        }
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    /**
     * XiaoZhi photo only (plan B: stand straight, then shoot) - cut off, play the stand-up
     * recovery, and wait until playback finishes (or times out) before returning, so the
     * robot is still during the shot. The caller is already on the MCP tool thread (not the ws read
     * loop), so blocking a few seconds is fine. A timeout or a failed start still returns (the
     * shutter matters more than the pose); the caller shoots directly.
     */
    public boolean stopAndRecoverBlocking(long timeoutMs) {
        stopActionWithRecovery();
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        try {
            // The recovery action starts with a small delay (parse + start the thread); wait a bit
            // first, otherwise the still check misjudges it as settled.
            Thread.sleep(300);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        while (isPlaying() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        boolean settled = !isPlaying();
        if (!settled) {
            Log.w(TAG, "stopAndRecoverBlocking: still playing after timeout");
        }
        return settled;
    }

    // -- MCP tools (XiaozhiBridge callTool switch forwards here) --

    /** Body of self.robot.list_actions (forwarded by XiaozhiBridge). Pure read, does not touch hardware. */
    public SonarCenter.McpResult mcpListActions() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (org.json.JSONObject a : loadXiaozhiActions()) {
            arr.put(a);
        }
        return SonarCenter.McpResult.ok(arr.toString());
    }

    /** Body of self.robot.play_action (forwarded by XiaozhiBridge).
     *  What XiaoZhi passes is an action name in human language (Chinese/English); fuzzy match
     *  here finds the id of the real body file. */
    public SonarCenter.McpResult mcpPlayAction(org.json.JSONObject arguments) {
        // Motion gate (alpha2-brain#144): the whole choice is in the pure function
        // PlayRefusal.route, so a plain Java test can run it (PlayRefusalTest). The
        // refusal text goes to the LLM, and it can say the motion is off. With the
        // gate on the result is unchanged (playActionDirect keeps its own gate check).
        return PlayRefusal.route(motionGate, "play_action",
                new PlayRefusal.Refused<SonarCenter.McpResult>() {
                    public SonarCenter.McpResult apply(String why) {
                        Log.w("MotionGate", "refused: " + why);
                        return SonarCenter.McpResult.err(why);
                    }
                },
                new PlayRefusal.Play<SonarCenter.McpResult>() {
                    public SonarCenter.McpResult get() {
                        return mcpPlayActionBody(arguments);
                    }
                });
    }

    /** The play part of mcpPlayAction; the gate choice is in PlayRefusal.route
     *  (alpha2-brain#144). */
    private SonarCenter.McpResult mcpPlayActionBody(org.json.JSONObject arguments) {
        String actionName = arguments.optString("name", "");
        if (actionName.isEmpty()) {
            return SonarCenter.McpResult.missingArg("name");
        }
        // A file name in /sdcard/actions (for example "Squat-myrobot.ubx") plays that file
        // directly. resolveActionFile() keeps the path inside ACTION_DIR. A server can then
        // offer only checked action files to the LLM (alpha2-brain#16).
        if (actionName.toLowerCase(java.util.Locale.US).endsWith(".ubx")) {
            if (resolveActionFile(actionName) == null) {
                return SonarCenter.McpResult.err("no action file \"" + actionName + "\" in " + ACTION_DIR);
            }
            UbxErrorCode.API_ERROR_CODE fileCode = playActionDirect(actionName);
            return new SonarCenter.McpResult(!MainActivity.isOk(fileCode),
                    String.valueOf(fileCode) + " (file \"" + actionName + "\")");
        }
        String resolvedId = resolveActionId(actionName);
        if (resolvedId == null) {
            return SonarCenter.McpResult.err("no action found matching \"" + actionName
                    + "\" - call self.robot.list_actions to see valid names");
        }
        UbxErrorCode.API_ERROR_CODE code = playActionDirect(resolvedId);
        return new SonarCenter.McpResult(!MainActivity.isOk(code),
                String.valueOf(code) + " (matched \"" + actionName + "\" -> id " + resolvedId + ")");
    }

    /** Body of self.robot.stop_action (forwarded by XiaozhiBridge).
     *  pure-direct: one-key stop-all + squat-then-stand-up recovery, same semantics as HTTP action/stop. */
    public SonarCenter.McpResult mcpStopAction() {
        UbxErrorCode.API_ERROR_CODE code = stopActionWithRecovery();
        return new SonarCenter.McpResult(!MainActivity.isOk(code), String.valueOf(code));
    }

    /** Body of self.robot.play_random_action (forwarded by XiaozhiBridge).
     *  Motion gate (alpha2-brain#144): the whole choice is in the pure function
     *  PlayRefusal.route, like mcpPlayAction. */
    public SonarCenter.McpResult mcpPlayRandomAction() {
        return PlayRefusal.route(motionGate, "play_random_action",
                new PlayRefusal.Refused<SonarCenter.McpResult>() {
                    public SonarCenter.McpResult apply(String why) {
                        Log.w("MotionGate", "refused: " + why);
                        return SonarCenter.McpResult.err(why);
                    }
                },
                new PlayRefusal.Play<SonarCenter.McpResult>() {
                    public SonarCenter.McpResult get() {
                        return mcpPlayRandomActionBody();
                    }
                });
    }

    /** The play part of mcpPlayRandomAction; the gate choice is in PlayRefusal.route
     *  (alpha2-brain#144). */
    private SonarCenter.McpResult mcpPlayRandomActionBody() {
        String randomId = resolveRandomActionId();
        if (randomId == null) {
            return SonarCenter.McpResult.err("no random-movement actions available");
        }
        UbxErrorCode.API_ERROR_CODE code = playActionDirect(randomId);
        return new SonarCenter.McpResult(!MainActivity.isOk(code),
                String.valueOf(code) + " (played random action id " + randomId + ")");
    }

}
