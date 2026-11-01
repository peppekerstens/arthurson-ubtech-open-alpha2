package com.open.alpha2;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;

import java.util.Map;

/**
 * Servo read stream (alpha2-rl#3): the app reads a set of servos with chest cmd 0x17 at a fixed
 * rate in its own thread and sends one {@code servo_reads} event on {@code /ws} for each cycle.
 *
 * <p>Why: one read over HTTP costs about 45 ms (request, chest reply, {@code chest_rcv} on
 * {@code /ws}), so a 20 Hz policy loop on a PC cannot read and move in 50 ms. With this stream
 * the PC uses the newest pushed reads and does not wait for a reply.
 *
 * <p>Safety:
 * <ul>
 *   <li>The stream sends only cmd 0x17 read frames ({@code F8 8F 08 00 00 17 id sum ED}), the
 *       same bytes as {@code servo/back-angle}. It never moves a servo and never writes.</li>
 *   <li>It stops by itself when no client renews it: {@code servo/stream/start} sets a deadline
 *       ({@code ttl_ms}, default 5000). A client calls start again to renew.</li>
 *   <li>A chest or servo firmware upgrade stops the stream and waits for its thread to end
 *       before the first upgrade frame ({@link #haltForUpgrade}). During the upgrade,
 *       {@code servo/stream/start} gives 409. The busy check and the send of a batch are one
 *       step (alpha2-brain#26). The stream pauses while the chest port is not open.</li>
 *   <li>Only the servos that answer cmd 0x17 are allowed (2, 3, 5, 6, 17 to 22). Other ids
 *       give 400 (alpha2-brain#29).</li>
 *   <li>It writes one frame batch for each cycle through {@code DirectSerialPort.sendRaw}, which is
 *       synchronized. Thus other chest frames (app commands, heartbeat handling) go out between
 *       two batches and never inside a frame.</li>
 * </ul>
 *
 * <p>The replies of the stream are not sent as {@code chest_rcv} events while {@code raw=false}
 * (the default): at 20 Hz and 8 servos that would be 160 events each second for each WebSocket
 * client. ChestQuery still sees each reply. When another requester reads the same id
 * ({@code servo/back-angle}, or a cmd 0x17 frame on {@code debug/serial/send}), the next reply
 * for that id goes out as {@code chest_rcv} (alpha2-brain#29).
 *
 * <p>The logic is in the pure Java class {@link ServoStreamCore} (unit test
 * ServoStreamCoreTest). This class connects it to Android.
 *
 * <p>Event data: {@code {"seq":N,"t_ms":<elapsedRealtime ms>,"read_ms":<first send to last reply>,
 * "ids":[...],"angles":[... or null],"accel":[x,y,z] or null,"accel_age_ms":N or null}}.
 */
public final class ServoReadStream {
    private static final String TAG = "ServoReadStream";

    public static final int HZ_MIN = 1;
    public static final int HZ_MAX = 25;
    public static final int HZ_DEFAULT = 20;
    public static final int TTL_MIN_MS = 500;
    public static final int TTL_MAX_MS = 10000;
    public static final int TTL_DEFAULT_MS = 5000;
    /** At most this many ids: the servos that answer cmd 0x17. */
    public static final int MAX_IDS = ServoStreamCore.READABLE_IDS.length;
    /** At most this many reads each second (servo bus and chest link load). */
    public static final int MAX_READS_PER_S = 200;
    /** The 8 kg servos of the Alpha 2 that answer cmd 0x17 (alpha2_rl READABLE_SERVOS). */
    public static final int[] DEFAULT_IDS = {2, 3, 5, 6, 17, 18, 19, 20};
    /** An upgrade waits at most this long for the stream thread to end. */
    public static final long HALT_TIMEOUT_MS = 2000;

    /** True while a chest or servo firmware upgrade runs: the stream sends nothing then. */
    public interface BusyCheck {
        boolean busy();
    }

    private static volatile ServoReadStream sInstance;

    public static void init(Context context, BusyCheck busy) {
        if (sInstance == null) {
            sInstance = new ServoReadStream(context.getApplicationContext(), busy);
        }
    }

    public static ServoReadStream get() {
        return sInstance;
    }

    /** The serial reader thread calls this for each chest frame (payload[0] = cmd).
     *  Returns true when the frame is a reply to the stream and must not go out as chest_rcv. */
    public static boolean onChestPayload(byte[] payload) {
        ServoReadStream s = sInstance;
        return s != null && s.core.onPayload(payload);
    }

    /** Call before another requester sends a cmd 0x17 read for {@code id}: its reply then goes
     *  out as chest_rcv also while the stream runs (alpha2-brain#29). */
    public static void noteExternalRead(int id) {
        ServoReadStream s = sInstance;
        if (s != null) s.core.noteExternalRead(id);
    }

    /** debug/serial/send: marks each cmd 0x17 read in the raw chest data (see noteExternalRead). */
    public static void noteExternalReads(byte[] rawChestData) {
        for (int id : ServoStreamCore.readIdsInFrames(rawChestData)) noteExternalRead(id);
    }

    /**
     * A chest or servo firmware upgrade calls this after it sets its busy flag and before
     * its first frame (alpha2-brain#26). It stops the stream and waits for the thread to
     * end. Returns false when the thread did not end in time: the upgrade must not start.
     */
    public static boolean haltForUpgrade(String why) {
        ServoReadStream s = sInstance;
        if (s == null) return true;
        boolean ok = s.core.haltForUpgrade(why, HALT_TIMEOUT_MS);
        if (!ok) Log.w(TAG, "the stream thread did not end in " + HALT_TIMEOUT_MS + " ms: " + why);
        return ok;
    }

    public static void shutdown() {
        ServoReadStream s = sInstance;
        if (s != null) s.core.stop("app shutdown");
    }

    private final ServoStreamCore core;

    private ServoReadStream(final Context appContext, final BusyCheck busyCheck) {
        core = new ServoStreamCore(new ServoStreamCore.Env() {
            @Override public long nowMs() { return SystemClock.elapsedRealtime(); }

            @Override public boolean portAvailable() {
                try {
                    return HardwareDirectManager.get(appContext).chest().isAvailable();
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override public boolean send(byte[] batch) {
                return HardwareDirectManager.get(appContext).chest().sendRaw(batch);
            }

            @Override public boolean busy() { return busyCheck != null && busyCheck.busy(); }

            @Override public String accelJson() {
                float[] acc = DeviceStatus.latestAccel();
                if (acc == null) return ",\"accel\":null,\"accel_age_ms\":null";
                long accAt = DeviceStatus.latestAccelAtMs();
                return ",\"accel\":[" + acc[0] + ',' + acc[1] + ',' + acc[2]
                        + "],\"accel_age_ms\":" + (SystemClock.elapsedRealtime() - accAt);
            }

            @Override public void publish(String type, String json) { EventBus.get().publish(type, json); }

            @Override public void log(String msg) { Log.i(TAG, msg); }
        }, DEFAULT_IDS, HZ_DEFAULT, TTL_DEFAULT_MS);
    }

    // -- API ---------------------------------------------------------------------------------

    /** servo/stream/start, servo/stream/stop, servo/stream/status. */
    public static HttpServer.ApiResponse api(String path, Map<String, String> query) {
        ServoReadStream s = sInstance;
        if (s == null) {
            return HttpServer.ApiResponse.error("servo stream not ready");
        }
        switch (path) {
            case "servo/stream/start":
                return s.startResponse(query);
            case "servo/stream/stop":
                s.core.stop("stop request");
                return HttpServer.ApiResponse.ok(s.core.statusJson());
            default:
                return HttpServer.ApiResponse.ok(s.core.statusJson());
        }
    }

    private HttpServer.ApiResponse startResponse(Map<String, String> query) {
        int newHz = ApiValidator.optionalIntRange(query, "hz", HZ_MIN, HZ_MAX, HZ_DEFAULT);
        int newTtl = ApiValidator.optionalIntRange(query, "ttl_ms", TTL_MIN_MS, TTL_MAX_MS, TTL_DEFAULT_MS);
        boolean newRaw = ApiValidator.optionalBoolean(query, "raw", false);
        int[] newIds = ApiValidator.optionalServoIdList(query, "ids", DEFAULT_IDS, MAX_IDS);
        String bad = ServoStreamCore.checkReadableIds(newIds);
        if (bad != null) throw new IllegalArgumentException("ids: " + bad);
        if (newIds.length * newHz > MAX_READS_PER_S) {
            throw new IllegalArgumentException("ids x hz must be at most " + MAX_READS_PER_S
                    + " reads per second, got " + newIds.length + " x " + newHz);
        }
        try {
            core.start(newIds, newHz, newTtl, newRaw);
        } catch (ServoStreamCore.BusyException e) {
            return new HttpServer.ApiResponse(409, "application/json; charset=utf-8",
                    JsonUtil.okFalse(e.getMessage()));
        }
        return HttpServer.ApiResponse.ok(core.statusJson());
    }
}
