package com.ubtechinc.alpha.hardware;

// 2026-09: StaticValue/DeveloperPacketData moved in from the ubtechalpha2robot
// module into this package (RobotWire/DeveloperPacketData). No SDK dependency.

/**
 * Direct driver for the chest MCU: servos, sonar, PIR, firmware upgrade.
 * The protocol comes from StaticValue of 1.1.7.3.20 plus chapter 5 of
 * AIDL_REFERENCE, but it runs over DirectSerialPort, not the binder of
 * Alpha2SerialServiceUtil.
 * Comments stay plain for easier maintenance.
 */
public final class DirectChestController {
    private final DirectSerialPort port;

    public DirectChestController(DirectSerialPort port) { this.port = port; }

    /** Clamps to 0-255. A silent byte wrap would jerk the servo. */
    private static int clampAngle(int v) {
        if (v < 0) { android.util.Log.w("DirectChest", "angle clamp 0 (was " + v + ")"); return 0; }
        if (v > 255) { android.util.Log.w("DirectChest", "angle clamp 255 (was " + v + ")"); return 255; }
        return v;
    }

    /** Clamps time to 20-32767 (the spec limit, see servo/one). */
    private static short clampTime(int time) {
        if (time < 20) return 20;
        if (time > 32767) return 32767;
        return (short) time;
    }

    /** Silent clamp to 0-255 (sonar / group servo use, no log). */
    private static int clampByte(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    public boolean open() { return port.open(); }
    public void close() { port.close(); }
    public boolean isAvailable() { return port.isAvailable(); }
    public void setFrameListener(DirectSerialPort.OnFrameListener l) { port.addListener(l); }

    /** Single servo: packs with DeveloperPacketData from this package, byte-for-byte like the stock implementation */
    public boolean setSingleServo(byte id, int angle, short time) {
        if (id < 1 || id > 22) return false;
        // The angle unit matches servo/one (0-255; the MCP schema uses the spec limit).
        // Without a clamp, a direct byte cast wraps and jerks the servo.
        // time keeps the lower bound 20 and the spec upper bound 32767.
        angle = clampAngle(angle);
        time = clampTime(time);
        DeveloperPacketData p = new DeveloperPacketData(5);
        p.putByte(id);
        p.putByte((byte) ((angle >> 8) & 0xFF));
        p.putByte((byte) (angle & 0xFF));
        p.putShort_(time);
        return port.send(RobotWire.CHES_CMD_MOTORANGLE, p.getBuffer());
    }

    /**
     * Group servo play frame (dance / .ubx): cmd 3 [20 axis bytes + short time],
     * as in the a.m original.
     * Note: the cmd 52 of setAllServos is a different purpose, left unchanged.
     * Playback always goes through this method.
     * Accepts 20 or 22 axes: with 22, the first 20 go over cmd 3, and 21/22
     * each get one extra cmd 05 (same time).
     */
    public boolean playAllServos(int[] angles, short time) {
        if (angles == null || (angles.length != 20 && angles.length != 22)) return false;
        time = clampTime(time);
        int[] first20 = angles.length == 20 ? angles : java.util.Arrays.copyOf(angles, 20);
        DeveloperPacketData p = new DeveloperPacketData(22);
        for (int a : first20) {
            p.putByte((byte) clampByte(a));
        }
        p.putShort_(time);
        boolean ok = port.send(RobotWire.CHEST_CMD_SENDMOTOR, p.getBuffer());
        if (angles.length == 22) {
            boolean ok21 = setSingleServo((byte) 21, angles[20], time);
            boolean ok22 = setSingleServo((byte) 22, angles[21], time);
            return ok && ok21 && ok22;
        }
        return ok;
    }

    /**
     * All servos: 20/22 angles. Note: cmd 52 has an ACK but the servos do not
     * move (old field test), so playback and full-pose moves always go over
     * cmd 3 (the dance channel, a.m private send original). Single-servo
     * fine-tuning goes over cmd 5 (setSingleServo, verified on the official
     * PC tuner on 2026-09-06, confirmed by the user by eye).
     * Pose tracking: see {@link ServoPoseTracker}.
     */
    public boolean setAllServos(int[] angles, short time) {
        return playAllServos(angles, time);
    }

    /** Sonar config: 1.1.7.3 uses subCmd=10 and the distance in cm. Clamp out-of-range values to 0-255 to prevent a byte wrap. */
    public boolean configureSonar(int distanceCm) {
        return port.send(RobotWire.CHEST_CMD_SETTING, new byte[]{10, (byte) clampByte(distanceCm)});
    }

    /** PIR enable: cmd=72, verified on 1.1.7.3 firmware */
    public boolean setPirEnabled(boolean enabled) {
        return port.send((byte) 72, new byte[]{(byte) (enabled ? 1 : 0)});
    }

    /** Power save, one byte (cmd 0x40). enable=true can start "Sit down" (alpha2-brain#24),
     *  so it is refused here. Use {@link #setPowerSave(boolean, boolean, boolean)}. */
    public boolean setPowerSave(boolean enable) {
        return setPowerSave(enable, false, false);
    }

    /** Power save with both bytes (cmd 0x40, UBTECH SetSavePowerModeOp):
     *  [enter 0/1, servo power off 0/1]. enter=true is refused (see below). */
    public boolean setPowerSave(boolean enter, boolean servoPowerOff) {
        return setPowerSave(enter, servoPowerOff, false);
    }

    /** cmd 0x40 [enter, servo power off]. enter=true can start the stock "Sit down" move
     *  (chest V142, alpha2-brain#24). It is sent only with allowSitDown=true.
     *  Squat and release the servos with cmd 6 first. enter=false needs no flag. */
    public boolean setPowerSave(boolean enter, boolean servoPowerOff, boolean allowSitDown) {
        byte[] p = new byte[]{(byte) (enter ? 1 : 0), (byte) (servoPowerOff ? 1 : 0)};
        if (!allowSitDown && ChestSafety.checkCommand(RobotWire.CHEST_POWER_SAVE, p) != null) {
            android.util.Log.w("DirectChest", "refused cmd 0x40 enter=1: " + ChestSafety.SIT_DOWN_40);
            return false;
        }
        return port.send(RobotWire.CHEST_POWER_SAVE, p);
    }

    /** cmd 0x19. on=true sends [01] (power on, already on from boot).
     *  on=false is refused: [00] is NOT a power off. It starts the stock "Sit down" move
     *  (chest V142 and V352, alpha2-brain#24). To release the servos, squat and send a
     *  cmd 6 read to each servo. Use {@link #setServoPower(boolean, boolean)} for [00]. */
    public boolean setServoPower(boolean on) {
        return setServoPower(on, false);
    }

    /** cmd 0x19 [on]. on=false starts "Sit down" and is sent only with allowSitDown=true. */
    public boolean setServoPower(boolean on, boolean allowSitDown) {
        if (!on && !allowSitDown) {
            android.util.Log.w("DirectChest", "refused cmd 0x19 [00]: " + ChestSafety.SIT_DOWN_19);
            return false;
        }
        return port.send(RobotWire.CHEST_SERVO_POWER, new byte[]{(byte) (on ? 1 : 0)});
    }

    /** Read servo angle (cmd 13). The reply frame is parsed via OnFrameListener */
    public boolean readServo(byte servoId) {
        if (servoId < 1 || servoId > 22) return false;
        return port.send(RobotWire.CHEST_CMD_READ_SERVO, new byte[]{servoId});
    }

    /**
     * Write servo trim / offset (cmd 12, param [id][BE16 signed]). Restored
     * from a field test on the official PC tuner on 2026-09-06: the editor
     * writes the full group once per minute, value = 3 x (command angle - home).
     * Reply {@code 09 05 00 0c 00 <id>} = OK, {@code 01 <id>} = no feedback on
     * that axis. The write survives power loss (official values still read back
     * after repeated reboots, see the 2026-09-06 analysis). This is the chest
     * EEPROM. A wrong write changes the factory calibration. The caller must
     * go through an explicit user write action before calling.
     */
    public boolean writeServoTrim(byte servoId, int trim) {
        if (servoId < 1 || servoId > 22) return false;
        if (trim < -32768) trim = -32768;
        if (trim > 32767) trim = 32767;
        byte[] p = new byte[]{(byte) servoId, (byte) ((trim >> 8) & 0xFF), (byte) (trim & 0xFF)};
        return port.send(RobotWire.CHEST_CMD_WRITE_TRIM, p);
    }

    /**
     * Change servo ID (cmd 10, payload [old id][new id]. The chest forwards it
     * to the bus via servo code 205). The write survives power loss in the
     * servo EEPROM. The caller must go through an explicit user write action
     * before calling. Note: two servos on the same bus must not share an ID.
     * Make sure the target new ID is free before changing.
     */
    public boolean modifyServoId(byte oldId, byte newId) {
        if (oldId < 1 || oldId > 22) return false;
        if (newId < 1 || newId > 22) return false;
        return port.send(RobotWire.CHEST_CMD_MODIFY_SERVO_ID, new byte[]{oldId, newId});
    }

    /**
     * Read servo firmware version (cmd 14, payload [id]). The chest forwards
     * servo code 1 to the bus under an FC CF header. The reply comes back as
     * a cmd=14 frame via OnFrameListener, claimed and parsed by ChestQuery.
     * Read only. It writes no EEPROM.
     */
    public boolean readMotorVersion(byte servoId) {
        if (servoId < 1 || servoId > 22) return false;
        return port.send(RobotWire.CHEST_CMD_READ_MOTOR_VERSION, new byte[]{servoId});
    }

    /**
     * Read servo hardware info (cmd 33, payload [id], servo code 18).
     * The reply comes back as a cmd=33 frame via OnFrameListener, claimed and
     * parsed by ChestQuery. Read only. It writes no EEPROM.
     */
    public boolean readMotorHardware(byte servoId) {
        if (servoId < 1 || servoId > 22) return false;
        return port.send(RobotWire.CHEST_CMD_READ_MOTOR_HARDWARE, new byte[]{servoId});
    }

    /**
     * Start servo firmware upgrade (cmd 16, payload [id][len BE32]). The
     * payload format is inferred (see ServoUpgrade: the chest cmd 48 uses the
     * same [len BE32] plus an id line head on every servo command). The
     * official upgrade APK has no servo upgrade implementation. Not verified
     * on hardware.
     * Note: after START the chest / servo may already be in the upgrade
     * state. The caller must follow the whole flow to the end.
     */
    public boolean startMotorUpdate(byte servoId, int fileLen) {
        if (servoId < 1 || servoId > 22) return false;
        if (fileLen <= 0) return false;
        byte[] p = new byte[5];
        p[0] = servoId;
        p[1] = (byte) ((fileLen >> 24) & 0xFF);
        p[2] = (byte) ((fileLen >> 16) & 0xFF);
        p[3] = (byte) ((fileLen >> 8) & 0xFF);
        p[4] = (byte) (fileLen & 0xFF);
        return port.send(RobotWire.CHEST_CMD_UPGRADE_MOTOR_START, p);
    }

    /**
     * Servo firmware upgrade data page (cmd 17, payload [id][0x01][seqHi][seqLo][64B]).
     * The 64B page matches the 64-byte flash page of the servo bootloader
     * (112 pages = 7168B app area, proven by disassembly of a no-name mega8).
     * bank 1 = page write (2 is reserved, see ServoUpgrade).
     * If data is under 64B, the caller pads with zero first (the doc says
     * pad with zero to 68 bytes).
     */
    public boolean updateMotorPage(byte servoId, int seq, byte[] data64) {
        if (servoId < 1 || servoId > 22) return false;
        if (data64 == null || data64.length > 64 || seq < 0 || seq > 0xFFFF) return false;
        byte[] p = new byte[68];
        p[0] = servoId;
        p[1] = 0x01;
        p[2] = (byte) ((seq >> 8) & 0xFF);
        p[3] = (byte) (seq & 0xFF);
        System.arraycopy(data64, 0, p, 4, data64.length);
        return port.send(RobotWire.CHEST_CMD_UPGRADE_MOTOR_PAGE, p);
    }

    /** Read chest firmware version, cmd 51 */
    public boolean readVersion() {
        return port.send(RobotWire.CHEST_READ_VERSION, null);
    }

    /** Read the robot SN/UUID (chest EEPROM, cmd 55 CHEST_READ_SID_EEPROM).
     *  Under pure-direct this replaces the alpha2services broadcast query
     *  (the APK is no longer on the device, so the broadcast sent by
     *  robot.requestRobotUUID() never gets a reply, see misc/request_uuid).
     *  No parameter: the encoded wire frame is F8 8F 07 00 00 37 3E ED.
     *  The reply comes back as a cmd=55 (0x37) frame via OnFrameListener,
     *  waited on and parsed by MainActivity.queryChestRobotUuid(). */
    public boolean readSidEeprom() {
        return port.send(RobotWire.CHEST_READ_SID_EEPROM, null);
    }

    /** Official upgrade request ID (the upgrade APK Alpha2SerialProtocol
     *  always uses ID=1. 05 is the LAP header. Misusing it makes the MCU
     *  answer NAK, which caused the earlier fake success). */
    public static final byte UPGRADE_ID = 1;

    /** Start the chest upgrade: cmd 48 + 4-byte big-endian fileLen
     *  (fileLen must be > 0. A negative value encodes as a huge file and
     *  risks bricking the MCU, so it is refused). index must be 1..127 (the
     *  official getUnusedIndex never uses 0. ChestUpgrade allocates rolling). */
    public boolean startUpdate(int fileLen, int index) {
        if (fileLen <= 0) return false;
        byte[] p = new byte[4];
        p[0] = (byte) ((fileLen >> 24) & 0xFF);
        p[1] = (byte) ((fileLen >> 16) & 0xFF);
        p[2] = (byte) ((fileLen >> 8) & 0xFF);
        p[3] = (byte) (fileLen & 0xFF);
        return port.sendUpgrade(UPGRADE_ID, (byte) index, RobotWire.CHES_CMD_START_UPDATE, p);
    }

    public boolean updatePage(byte[] pageData, int pageLen, int index) {
        // An unchecked pageLen causes NegativeArraySize/OOM/arraycopy out-of-bounds. Bad packets would reach the MCU.
        if (pageData == null || pageLen < 0 || pageLen > pageData.length) return false;
        if (pageLen == 0) return false;
        byte[] p = new byte[pageLen + 2];
        p[0] = (byte) ((pageLen >> 8) & 0xFF);
        p[1] = (byte) (pageLen & 0xFF);
        System.arraycopy(pageData, 0, p, 2, pageLen);
        return port.sendUpgrade(UPGRADE_ID, (byte) index, RobotWire.CHES_CMD_UPDATE_PAGE, p);
    }

    public boolean endUpdate(byte[] md5_16, int index) {
        if (md5_16 == null || md5_16.length != 16) return false;
        return port.sendUpgrade(UPGRADE_ID, (byte) index, RobotWire.CHES_CMD_UPDATE_END, md5_16);
    }

    /** Raw frame pass-through (used by debug/serial/send, set_uuid, version fallback). Under pure-direct this replaces robot.chest_sendRawData. */
    public boolean sendRaw(byte[] rawFrame) {
        return sendRaw(rawFrame, false);
    }

    /** Raw frame pass-through with the "Sit down" guard: frames with cmd 0x19 [00] or
     *  cmd 0x40 [01 ..] are refused unless allowSitDown=true (alpha2-brain#24). The data must
     *  also be whole frames with a correct length, checksum and tail (alpha2-brain#25). */
    public boolean sendRaw(byte[] rawFrame, boolean allowSitDown) {
        if (rawFrame == null || rawFrame.length == 0) return false;
        String why = ChestSafety.checkChestFrames(rawFrame, allowSitDown);
        if (why != null) {
            android.util.Log.w("DirectChest", "refused raw chest frame: " + why);
            return false;
        }
        return port.sendRaw(rawFrame);
    }
}
