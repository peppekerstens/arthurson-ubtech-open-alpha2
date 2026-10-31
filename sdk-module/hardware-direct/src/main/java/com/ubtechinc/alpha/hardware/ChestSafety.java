package com.ubtechinc.alpha.hardware;

/**
 * Guard against the chest "Sit down" trap (alpha2-brain#24, alpha2-python#18).
 *
 * <p>Firmware analysis in Renode (chest V142 and V352) shows:
 * <ul>
 *   <li>cmd 0x19 {@code [00]} is not a servo power off. It starts the stock "Sit down"
 *       move: the chest sends a move to all 20 servos. When the charger flag of the chest
 *       is set, it does nothing. cmd 0x19 {@code [01]} sets the power on, which is already
 *       on from boot.</li>
 *   <li>cmd 0x40 {@code [01 ...]} (power save, enter) also runs "Sit down" on V142.</li>
 * </ul>
 *
 * <p>To release the servos, squat and send a cmd 6 read to each servo. To hold them
 * again, send a move.
 *
 * <p>Same rules as alpha2-python e98d6d1 {@code protocol.check_chest_frames}: refuse
 * cmd 0x19 with a payload other than {@code [01]} and cmd 0x40 with a first byte other
 * than {@code 00}, unless the caller gives an explicit opt-in. Use it only for chest
 * frames: the head MCU uses cmd 0x19 for another job.
 *
 * <p>The scan is more strict than the Python decoder: at each F8 8F it tries the long
 * form with any SRC and DST and the legacy short form. A frame with a correct checksum
 * and tail in one of the two forms counts. Pure java, no Android classes (unit test:
 * scripts/test-serialcodec.py).
 */
public final class ChestSafety {
    public static final int CMD_SERVO_POWER = 0x19; // RobotWire.CHEST_SERVO_POWER
    public static final int CMD_POWER_SAVE = 0x40;  // RobotWire.CHEST_POWER_SAVE

    public static final String SIT_DOWN_19 = "cmd 0x19 [00] is not a servo power off."
            + " It starts the 'Sit down' move of the chest (firmware V142 and V352,"
            + " alpha2-brain#24). A standing robot can fall. To release the servos, squat"
            + " and send cmd 6 to each servo (a servo angle read)."
            + " Send allow_sit_down=true only when you want the sit-down move.";
    public static final String SIT_DOWN_40 = "cmd 0x40 [01] (power save, enter) can start the"
            + " 'Sit down' move of the chest (firmware V142, alpha2-brain#24). Squat and"
            + " release the servos with cmd 6 first. Send allow_sit_down=true only when you"
            + " want the sit-down move.";

    private static final int HEAD0 = 0xF8;
    private static final int HEAD1 = 0x8F;
    private static final int TAIL = 0xED;

    private ChestSafety() {}

    /** Returns null when the payload of {@code cmd} is safe, else the reason. */
    public static String checkCommand(int cmd, byte[] payload) {
        int c = cmd & 0xFF;
        byte[] p = payload == null ? new byte[0] : payload;
        if (c == CMD_SERVO_POWER && !(p.length == 1 && p[0] == 1)) return SIT_DOWN_19;
        if (c == CMD_POWER_SAVE && !(p.length >= 1 && p[0] == 0)) return SIT_DOWN_40;
        return null;
    }

    /**
     * Checks raw chest bytes (one or more wire frames). Returns null when no complete
     * frame starts "Sit down", else the reason. With {@code allowSitDown} it always
     * returns null.
     */
    public static String checkChestFrames(byte[] data, boolean allowSitDown) {
        if (allowSitDown || data == null) return null;
        int n = data.length;
        for (int i = 0; i + 1 < n; i++) {
            if ((data[i] & 0xFF) != HEAD0 || (data[i + 1] & 0xFF) != HEAD1) continue;
            String r = checkLong(data, i);
            if (r != null) return r;
            r = checkShort(data, i);
            if (r != null) return r;
        }
        return null;
    }

    /** Long form: F8 8F LEN SRC DST CMD PAYLOAD.. CHK ED, total = LEN + 1. */
    private static String checkLong(byte[] b, int start) {
        if (start + 5 >= b.length) return null;
        int len = b[start + 2] & 0xFF;
        int total = len + 1;
        if (total < 8 || start + total > b.length) return null;
        int end = start + total;
        if ((b[end - 1] & 0xFF) != TAIL) return null;
        if ((sum(b, start + 2, end - 2) & 0xFF) != (b[end - 2] & 0xFF)) return null;
        int cmd = b[start + 5] & 0xFF;
        return checkCommand(cmd, copy(b, start + 6, end - 2));
    }

    /** Legacy short form: F8 8F LEN CMD PAYLOAD.. CHK ED, total = LEN + 5. */
    private static String checkShort(byte[] b, int start) {
        if (start + 3 >= b.length) return null;
        int len = b[start + 2] & 0xFF;
        if (len < 1) return null;
        int total = len + 5;
        if (start + total > b.length) return null;
        int end = start + total;
        if ((b[end - 1] & 0xFF) != TAIL) return null;
        if ((sum(b, start + 2, end - 2) & 0xFF) != (b[end - 2] & 0xFF)) return null;
        int cmd = b[start + 3] & 0xFF;
        return checkCommand(cmd, copy(b, start + 4, end - 2));
    }

    private static int sum(byte[] b, int from, int to) {
        int s = 0;
        for (int i = from; i < to; i++) s += b[i] & 0xFF;
        return s;
    }

    private static byte[] copy(byte[] b, int from, int to) {
        byte[] out = new byte[Math.max(0, to - from)];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }
}
