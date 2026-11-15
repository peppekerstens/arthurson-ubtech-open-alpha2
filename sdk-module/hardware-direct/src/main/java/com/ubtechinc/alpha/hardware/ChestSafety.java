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
 * <p>Refuse a complete short-form frame with LEN below 7 (the long-form minimum,
 * D-22, alpha2-python#35): the firmware does not parse the short form, and such a
 * frame locks up the receive path. This rule wins over the sit-down reasons.
 *
 * <p>The scan is more strict than the Python decoder: at each F8 8F it tries the long
 * form with any SRC and DST and the legacy short form. A frame with a correct checksum
 * and tail in one of the two forms counts.
 *
 * <p>Whole frames only (alpha2-brain#25): without the opt-in, the data must be one or more
 * complete frames, back to back, each with a correct length, checksum and tail. Thus the
 * guard refuses a frame with a bad checksum or a bad length, junk bytes other than 0x00
 * padding, and a call that ends inside a frame. The UART is a byte stream: a frame split over two calls
 * ({@code F8 8F 08 05 00 19} and then {@code 00 26 ED}) would join again in the chest. With
 * whole frames only, each call ends at a frame end, so no call can complete a frame of an
 * earlier call. No reassembly buffer is necessary. All callers in the app send whole frames.
 *
 * <p>Pure java, no Android classes (unit tests: scripts/test-serialcodec.py and
 * scripts/test-apivalidator.py).
 */
public final class ChestSafety {
    public static final int CMD_SERVO_POWER = 0x19; // RobotWire.CHEST_SERVO_POWER
    public static final int CMD_POWER_SAVE = 0x40;  // RobotWire.CHEST_POWER_SAVE
    // Chest move commands (alpha2-brain#130): 0x03 moves one servo, 0x05 moves all
    // servos, 0x06 reads the angle and releases the servo. A read without release
    // (for example 0x17) is no move.
    public static final int CMD_MOVE_ONE = 0x03;
    public static final int CMD_MOVE_ALL = 0x05;
    public static final int CMD_READ_RELEASE = 0x06;
    // Other chest commands that move a servo or change its state (alpha2-brain#130). The
    // chest table is alpha2-firmware-re chest/V142/dispatch.md. The purpose of 0x4A, 0x4C,
    // 0x53 and 0x59 is only "servo command" there, so they count as moves (fail closed).
    public static final int CMD_CHANGE_SERVO_ID = 0x0A;
    public static final int CMD_WRITE_SERVO_TRIM = 0x0C;
    public static final int CMD_SERVO_GROUP = 0x23;
    public static final int CMD_SET_ALL_ANGLE = 0x34;
    public static final int CMD_SERVO_4A = 0x4A;
    public static final int CMD_SERVO_4C = 0x4C;
    public static final int CMD_PC_SERVO_53 = 0x53;
    public static final int CMD_PC_SERVO_59 = 0x59;
    // Servo firmware write (owner rule D-197, alpha2-servo#15): never through debug/serial/send.
    public static final int CMD_SERVO_FW_START = 0x10;
    public static final int CMD_SERVO_FW_PAGE = 0x11;
    // Commands that write flash or the identity: never through debug/serial/send, gate on or off.
    // Source: alpha2-firmware-re chest/V142/dispatch.md (V142 and V352 tables): 0x10, 0x11 servo
    // firmware START and PAGE; 0x30, 0x31 chest firmware START and PAGE; 0x32 chest firmware END,
    // also bootloader update (the V142 handler can overwrite the bootloader, see the table note);
    // 0x45, 0x46, 0x47 battery pack firmware (V352); 0x60 PC port: write data page;
    // 0x36 write serial number (EEPROM).
    private static boolean isNeverCommand(int c) {
        switch (c) {
            case 0x10: case 0x11: case 0x30: case 0x31: case 0x32:
            case 0x45: case 0x46: case 0x47: case 0x60: case 0x36:
                return true;
            default:
                return false;
        }
    }
    // State changes behind the motion gate: 0x18 hand pressure zero (calibration), 0x02 chest
    // power off, 0x1F power off head (V352), 0x27 Android power on/off (same table).
    private static boolean isStateCommand(int c) {
        return c == 0x18 || c == 0x02 || c == 0x1F || c == 0x27;
    }

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
        // 1. Each complete frame at each F8 8F (also inside the payload of another frame).
        for (int i = 0; i + 1 < n; i++) {
            if ((data[i] & 0xFF) != HEAD0 || (data[i + 1] & 0xFF) != HEAD1) continue;
            String r = checkLong(data, i);
            if (r != null) return r;
            r = checkShort(data, i);
            if (r != null) return r;
        }
        // 2. Whole frames only (alpha2-brain#25).
        return checkWholeFrames(data);
    }

    /**
     * The first chest move command in the raw frames (alpha2-brain#130): cmd 0x03
     * (move one), 0x05 (move all), 0x06 (read with release), and the servo commands
     * 0x0A, 0x0C, 0x23, 0x34, 0x4A, 0x4C, 0x53 and 0x59. Returns the command
     * byte, or -1 when no move command is present. Read commands (for example 0x17,
     * 0x39, 0x0E) are not move commands. A frame with a bad checksum, length or tail
     * is not a complete frame, so the scan skips it (checkChestFrames refuses it).
     */
    public static int findChestMoveCommand(byte[] data) {
        return findCommand(data);
    }

    /**
     * Finds a command that no gate allows (see isNeverCommand), or -1. This scan is lenient on
     * purpose (D-197): it does not need a correct length, checksum or tail. At each F8 8F it
     * looks at the byte of the long form (index 5) and of the short form (index 3). A header
     * that ends before the long-form command byte gives 0x100: the data may be the first
     * part of a frame that the chest joins with the next call. So a split frame or junk
     * bytes cannot hide a firmware write.
     */
    public static int findServoFirmwareCommand(byte[] data) {
        if (data == null) return -1;
        int n = data.length;
        for (int i = 0; i + 1 < n; i++) {
            if ((data[i] & 0xFF) != HEAD0 || (data[i + 1] & 0xFF) != HEAD1) continue;
            if (i + 5 >= n) return 0x100;
            if (isNeverCommand(data[i + 5] & 0xFF)) return data[i + 5] & 0xFF;
            if (isNeverCommand(data[i + 3] & 0xFF)) return data[i + 3] & 0xFF;
        }
        return -1;
    }

    private static boolean isMoveCommand(int cmd) {
        switch (cmd) {
            case CMD_MOVE_ONE: case CMD_MOVE_ALL: case CMD_READ_RELEASE:
            case CMD_CHANGE_SERVO_ID: case CMD_WRITE_SERVO_TRIM: case CMD_SERVO_GROUP:
            case CMD_SET_ALL_ANGLE: case CMD_SERVO_4A: case CMD_SERVO_4C:
            case CMD_PC_SERVO_53: case CMD_PC_SERVO_59:
                return true;
            default:
                return isStateCommand(cmd);
        }
    }

    private static int findCommand(byte[] data) {
        if (data == null) return -1;
        int n = data.length;
        for (int i = 0; i + 1 < n; i++) {
            if ((data[i] & 0xFF) != HEAD0 || (data[i + 1] & 0xFF) != HEAD1) continue;
            int cmd = -1;
            int total = longLength(data, i);
            if (total >= 0) {
                cmd = data[i + 5] & 0xFF;
            } else {
                total = shortLength(data, i);
                if (total >= 0) cmd = data[i + 3] & 0xFF;
            }
            if (isMoveCommand(cmd)) return cmd;
        }
        return -1;
    }

    /**
     * Returns null when {@code data} is zero or more complete chest frames back to back
     * (long or legacy short form, correct length, checksum and tail), else the reason.
     * 0x00 bytes between and after the frames are allowed.
     * It does not look at the command. {@link #checkChestFrames} uses it.
     */
    public static String checkWholeFrames(byte[] data) {
        if (data == null) return null;
        int n = data.length;
        int i = 0;
        while (i < n) {
            // 0x00 padding between or after frames is allowed: it can never start a frame
            // (set_uuid sends one 0x00 after its frame, a format verified on the robot).
            if (data[i] == 0) {
                i++;
                continue;
            }
            if (i + 1 >= n || (data[i] & 0xFF) != HEAD0 || (data[i + 1] & 0xFF) != HEAD1) {
                return NOT_WHOLE + ": no frame header F8 8F at byte " + i;
            }
            int len = longLength(data, i);
            if (len < 0) len = shortLength(data, i);
            if (len < 0) {
                return NOT_WHOLE + ": the frame at byte " + i
                        + " is not complete or has a bad length, checksum or tail";
            }
            i += len;
        }
        return null;
    }

    public static final String NOT_WHOLE = "chest data must be complete frames"
            + " (F8 8F .. checksum ED) with a correct length, checksum and tail. Only 0x00 padding"
            + " can come between and after the frames."
            + " A frame split over two calls, junk bytes and a bad checksum are refused,"
            + " because the chest could join them into a 'Sit down' frame (alpha2-brain#25)."
            + " Send allow_sit_down=true to send the bytes without a check";

    /** Minimum LEN of the legacy short form (D-22, alpha2-python#35). */
    public static final int SHORT_LEN_MIN = 7;

    /** Reason for a complete short form whose LEN is below the long-form minimum. */
    public static final String LEN_BELOW_MIN = "a short-form frame with LEN below 7 (the long-form minimum) locks up the"
            + " receive path of the chest firmware: it stops answering VERSION for 10 s or"
            + " more (alpha2-python#35, owner decision D-22). Send the long form. Send"
            + " allow_sit_down=true only to a simulator on a loopback address";

    /** Total length of a valid long-form frame at {@code start}, else -1. */
    private static int longLength(byte[] b, int start) {
        if (start + 5 >= b.length) return -1;
        int total = (b[start + 2] & 0xFF) + 1;
        if (total < 8 || start + total > b.length) return -1;
        int end = start + total;
        if ((b[end - 1] & 0xFF) != TAIL) return -1;
        if ((sum(b, start + 2, end - 2) & 0xFF) != (b[end - 2] & 0xFF)) return -1;
        return total;
    }

    /** Total length of a valid legacy short-form frame at {@code start}, else -1. */
    private static int shortLength(byte[] b, int start) {
        if (start + 3 >= b.length) return -1;
        int len = b[start + 2] & 0xFF;
        if (len < 1) return -1;
        int total = len + 5;
        if (start + total > b.length) return -1;
        int end = start + total;
        if ((b[end - 1] & 0xFF) != TAIL) return -1;
        if ((sum(b, start + 2, end - 2) & 0xFF) != (b[end - 2] & 0xFF)) return -1;
        return total;
    }

    /** Long form: F8 8F LEN SRC DST CMD PAYLOAD.. CHK ED, total = LEN + 1. */
    private static String checkLong(byte[] b, int start) {
        int total = longLength(b, start);
        if (total < 0) return null;
        int cmd = b[start + 5] & 0xFF;
        return checkCommand(cmd, copy(b, start + 6, start + total - 2));
    }

    /**
     * Legacy short form: F8 8F LEN CMD PAYLOAD.. CHK ED, total = LEN + 5.
     * A complete short frame with LEN below the long-form minimum is refused
     * before the cmd check (D-22, alpha2-python#35): the LEN rule wins over
     * the sit-down reasons.
     */
    private static String checkShort(byte[] b, int start) {
        int total = shortLength(b, start);
        if (total < 0) return null;
        if ((b[start + 2] & 0xFF) < SHORT_LEN_MIN) return LEN_BELOW_MIN;
        int cmd = b[start + 3] & 0xFF;
        return checkCommand(cmd, copy(b, start + 4, start + total - 2));
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
