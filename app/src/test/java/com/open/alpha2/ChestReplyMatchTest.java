package com.open.alpha2;

/**
 * ChestReplyMatch unit test (no JUnit). scripts/test-apivalidator.py runs it
 * with plain javac/java.
 */
public final class ChestReplyMatchTest {
    private static int passed = 0;
    private static int failed = 0;

    private ChestReplyMatchTest() {}

    private static void eq(boolean expected, boolean actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static byte[] hex(String s) {
        String[] p = s.trim().split("\\s+");
        byte[] out = new byte[p.length];
        for (int i = 0; i < p.length; i++) out[i] = (byte) Integer.parseInt(p[i], 16);
        return out;
    }

    private static final byte VERSION = 0x33;

    public static void main(String[] args) {
        // VERSION reply with SRC 01 (stripSerialFrame cannot strip it).
        byte[] ver = hex("f8 8f 0e 01 00 33 41 4c 50 48 41 32 51 00 ed");
        eq(true, ChestReplyMatch.isReply(ver, new byte[] {0x01}, 1, VERSION), "version.src01");
        // VERSION reply with SRC 05.
        byte[] ver5 = hex("f8 8f 0a 05 00 33 41 4c 50 00 ed");
        eq(true, ChestReplyMatch.isReply(ver5, new byte[] {0x33, 0x41}, 2, VERSION), "version.src05");
        // Stripped payload only (no frame header).
        eq(true, ChestReplyMatch.isReply(null, new byte[] {0x33, 0x41}, 2, VERSION), "version.payload");
        // Heartbeat 0x8D and telemetry 0x8B are not a VERSION reply.
        byte[] hb = hex("f8 8f 09 00 00 8d 02 9e ed");
        eq(false, ChestReplyMatch.isReply(hb, new byte[] {(byte) 0x8D, 0x02}, 2, VERSION), "heartbeat.8d");
        byte[] tm = hex("f8 8f 0a 00 00 8b 01 02 9f ed");
        eq(false, ChestReplyMatch.isReply(tm, new byte[] {(byte) 0x8B, 0x01, 0x02}, 3, VERSION), "telemetry.8b");
        // PIR 0x93 and mute 0x91 events.
        eq(false, ChestReplyMatch.isReply(null, new byte[] {(byte) 0x93, 0x01}, 2, VERSION), "event.pir");
        eq(false, ChestReplyMatch.isReply(null, new byte[] {(byte) 0x91, 0x01}, 2, VERSION), "event.mute");
        // Other replies (UUID 0x37, battery 0x39, sonar ACK 0x04).
        eq(false, ChestReplyMatch.isReply(null, new byte[] {0x37, 0x00, 0x42}, 3, VERSION), "reply.uuid");
        eq(false, ChestReplyMatch.isReply(null, new byte[] {0x39, 0x03}, 2, VERSION), "reply.battery");
        eq(false, ChestReplyMatch.isReply(null, new byte[] {0x04, 0x00}, 2, VERSION), "reply.sonar");
        // A heartbeat and a VERSION reply in one read: the reply wins.
        byte[] both = hex("f8 8f 09 00 00 8d 02 9e ed f8 8f 0a 05 00 33 41 4c 50 00 ed");
        eq(true, ChestReplyMatch.isReply(both, new byte[] {(byte) 0x8D, 0x02}, 2, VERSION), "both.inOneRead");
        // Empty and short input.
        eq(false, ChestReplyMatch.isReply(null, null, 0, VERSION), "null");
        eq(false, ChestReplyMatch.isReply(new byte[0], new byte[0], 0, VERSION), "empty");
        eq(false, ChestReplyMatch.hasLongFrame(hex("f8 8f 07 05 00 33"), 6, VERSION), "short");

        System.out.println("ChestReplyMatchTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
