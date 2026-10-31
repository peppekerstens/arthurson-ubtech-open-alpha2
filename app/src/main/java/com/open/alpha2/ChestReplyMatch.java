package com.open.alpha2;

/**
 * Matches a chest serial frame to the command of a pending request.
 *
 * Pure java.* (no Android), so scripts/test-apivalidator.py can test it.
 *
 * The chest MCU sends events at all times: telemetry 0x8B (about 1 Hz),
 * heartbeat 0x8D (about every 5 s), PIR 0x93, mute key 0x91, obstacle 0x81.
 * A frame that comes while a request waits is a reply only when its command
 * byte is the command of the request. All other frames stay with the caller.
 */
final class ChestReplyMatch {
    private ChestReplyMatch() {}

    /**
     * Find a long wire frame (F8 8F LEN SRC DST CMD ...) with this command
     * byte at any position in the bytes. SRC can be 00, 01 or 05.
     */
    static boolean hasLongFrame(byte[] bytes, int len, byte cmd) {
        if (bytes == null || len < 8) return false;
        int n = Math.min(len, bytes.length);
        for (int i = 0; i + 5 < n; i++) {
            if ((bytes[i] & 0xFF) == 0xF8 && (bytes[i + 1] & 0xFF) == 0x8F
                    && bytes[i + 5] == cmd) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the frame is the reply to the request with command cmd.
     * First check the full wire frame. Then check the stripped payload,
     * where payload[0] is the command byte.
     */
    static boolean isReply(byte[] frame, byte[] payload, int plen, byte cmd) {
        if (frame != null && hasLongFrame(frame, frame.length, cmd)) return true;
        return payload != null && plen >= 1 && payload[0] == cmd;
    }
}
