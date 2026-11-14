package com.ubtechinc.alpha.hardware;

/**
 * 2026-09: Moved in from the StaticValue of sdk-module/ubtechalpha2robot (part of
 * leaving Alpha2OpenSdk). Records only the wire values still used under pure-direct.
 * The rest (all constants designed for the no-longer-existing alpha2services
 * binder/broadcast) were deleted with the old module.
 *
 * <p>Every value is a wire contract with the robot MCU / the system broadcast.
 * It must match byte for byte. Do not change it.
 */
public final class RobotWire {
    private RobotWire() {
    }

    // -- Broadcast action strings (for third-party apps to receive) --------------------------
    public static final String CHEST_ACTION = "com.ubtechinc.services.chest";
    public static final String SONAR_DISTANCE_ACTION = "com.ubtechinc.sonar.distance";
    public static final String SONAR_DISTANCE_EXTRA = "sonar_distance";
    public static final String ALPHA_BT_CONNECTION = "com.ubtechinc.services.bluetooth";
    public static final String ALPHA_QR_CODE = "com.ubt.alpha2.qr_code";
    public static final String ALPHA_WIFI_RESULT = "com.ubt.alpha2.wifiresult";

    // -- Chest microcontroller serial command bytes ---------------------------
    public static final byte CHES_CMD_MOTORANGLE = 5;
    public static final byte CHEST_CMD_SETTING = 4;
    public static final byte CHES_CMD_START_UPDATE = 48;
    public static final byte CHES_CMD_UPDATE_PAGE = 49;
    public static final byte CHES_CMD_UPDATE_END = 50;
    public static final byte CHEST_READ_VERSION = 51;
    /** Dead: playback does not work on this (uses cmd 3). Kept only to avoid a name clash with 52. Do not use. */
    @Deprecated
    public static final byte CHEST_SET_ALL_ANGLE = 52;
    /** Read servo trim / cmd13 (DirectChestController.readServo). */
    public static final byte CHEST_CMD_READ_SERVO = 13;
    /** Write servo trim / cmd12 (DirectChestController.writeServoTrim, EEPROM holds across power loss). */
    public static final byte CHEST_CMD_WRITE_TRIM = 12;
    /** Group servo play frame: a.m sends the raw cmd 3 [20 axis bytes + short time] (proven by smali, do not mix with 52). */
    public static final byte CHEST_CMD_SENDMOTOR = 3;
    /** Modify servo ID / cmd10 (DirectChestController.modifyServoId, payload [old id][new id], servo code 205). */
    public static final byte CHEST_CMD_MODIFY_SERVO_ID = 10;
    /** Read servo firmware version / cmd14 (DirectChestController.readMotorVersion, payload [id], chest board forwards servo code 1 via FC CF). */
    public static final byte CHEST_CMD_READ_MOTOR_VERSION = 14;
    /** Read servo hardware info / cmd33 (DirectChestController.readMotorHardware, payload [id], servo code 18). */
    public static final byte CHEST_CMD_READ_MOTOR_HARDWARE = 33;
    /** Servo firmware upgrade start / cmd16 (DirectChestController.startMotorUpdate, payload [id][len BE32], inference see ServoUpgrade). */
    public static final byte CHEST_CMD_UPGRADE_MOTOR_START = 16;
    /** Servo firmware upgrade data page / cmd17 (DirectChestController.updateMotorPage, payload [id][0x01][seqHi][seqLo][64B]). */
    public static final byte CHEST_CMD_UPGRADE_MOTOR_PAGE = 17;
    /** Servo firmware upgrade result uplink code 134 [status][id] (status 0 = success). */
    public static final int CHEST_EVENT_MOTOR_UPGRADE = 134;
    public static final byte CHEST_READ_SID_EEPROM = 55;
    /** cmd 0x40 power save. [01 ..] (enter) can start "Sit down" on V142 (alpha2-brain#24). See ChestSafety. */
    public static final byte CHEST_POWER_SAVE = 64;
    /** cmd 0x19 (UBTECH SetPowerStateOp). [01] = power on (already on from boot).
     *  [00] is NOT a power off: it starts the stock "Sit down" move (alpha2-brain#24). See ChestSafety. */
    public static final byte CHEST_SERVO_POWER = 25;

    // -- Head microcontroller serial command bytes ----------------------------
    public static final byte LED_EAR = 1;
    public static final byte STOP_CMD = 8;
    public static final byte HEAD_CONTROL_BYPASS = 39;
    public static final byte HEADER_READ_VERSION = 51;
}
