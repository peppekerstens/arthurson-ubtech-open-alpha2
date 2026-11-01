package com.ubtechinc.alpha.hardware;

import java.io.ByteArrayOutputStream;

/**
 * Unit test for the "Sit down" guard in DirectChestController (alpha2-brain#24, #25, #30).
 * A fake port (DirectSerialPort.forTest) records each byte that would go to ttyS1.
 * Pure java.* plus the android.util.Log stub in scripts/test-stubs. No JUnit.
 * Run: scripts/test-apivalidator.py.
 */
public class DirectChestControllerTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void eq(Object want, Object got, String name) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }

    /** Long-form frame F8 8F LEN 00 00 CMD PAYLOAD CHK ED. */
    private static byte[] frame(int cmd, int... payload) {
        byte[] f = new byte[8 + payload.length];
        f[0] = (byte) 0xF8;
        f[1] = (byte) 0x8F;
        f[2] = (byte) (f.length - 1);
        f[5] = (byte) cmd;
        int sum = (f[2] & 0xFF) + cmd;
        for (int i = 0; i < payload.length; i++) {
            f[6 + i] = (byte) payload[i];
            sum += payload[i];
        }
        f[f.length - 2] = (byte) sum;
        f[f.length - 1] = (byte) 0xED;
        return f;
    }

    private static final class Rig {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final DirectChestController chest = new DirectChestController(DirectSerialPort.forTest(wire));

        int sent() { return wire.size(); }

        /** True when the wire has a frame with this cmd at the cmd offset of a frame. */
        boolean hasCmd(int cmd) {
            byte[] b = wire.toByteArray();
            for (int i = 0; i + 5 < b.length; i++) {
                if ((b[i] & 0xFF) == 0xF8 && (b[i + 1] & 0xFF) == 0x8F
                        && ((b[i + 5] & 0xFF) == cmd || (b[i + 3] & 0xFF) == cmd)) return true;
            }
            return false;
        }
    }

    public static void main(String[] args) {
        Rig r = new Rig();
        eq(true, r.chest.isAvailable(), "rig.available");

        // cmd 0x19: on=false is refused without the opt-in, also in the old one-argument form
        eq(false, r.chest.setServoPower(false), "servoPower.off.old");
        eq(false, r.chest.setServoPower(false, false), "servoPower.off");
        eq(0, r.sent(), "servoPower.off.nothingSent");
        eq(true, r.chest.setServoPower(true), "servoPower.on");
        eq(true, r.hasCmd(0x19), "servoPower.on.sent");
        r = new Rig();
        eq(true, r.chest.setServoPower(false, true), "servoPower.off.allowed");
        eq(true, r.hasCmd(0x19), "servoPower.off.allowed.sent");

        // cmd 0x40: enter=true is refused without the opt-in, in each overload
        r = new Rig();
        eq(false, r.chest.setPowerSave(true), "powerSave.enter.old1");
        eq(false, r.chest.setPowerSave(true, true), "powerSave.enter.old2");
        eq(false, r.chest.setPowerSave(true, false, false), "powerSave.enter");
        eq(0, r.sent(), "powerSave.enter.nothingSent");
        eq(true, r.chest.setPowerSave(false), "powerSave.leave");
        eq(true, r.chest.setPowerSave(false, true), "powerSave.leave2");
        eq(true, r.hasCmd(0x40), "powerSave.leave.sent");
        r = new Rig();
        eq(true, r.chest.setPowerSave(true, true, true), "powerSave.enter.allowed");
        eq(true, r.hasCmd(0x40), "powerSave.enter.allowed.sent");

        // sendRaw: the frame guard, also in the one-argument form
        byte[] f19off = frame(0x19, 0x00);
        byte[] f40enter = frame(0x40, 0x01, 0x01);
        byte[] fRead = frame(0x06, 0x03);
        r = new Rig();
        eq("f8 8f 08 00 00 19 00 21 ed", hex(f19off), "raw.layout");
        eq(false, r.chest.sendRaw(f19off), "raw.19off.old");
        eq(false, r.chest.sendRaw(f19off, false), "raw.19off");
        eq(false, r.chest.sendRaw(f40enter), "raw.40enter.old");
        eq(false, r.chest.sendRaw(f40enter, false), "raw.40enter");
        byte[] batch = new byte[fRead.length + f19off.length];
        System.arraycopy(fRead, 0, batch, 0, fRead.length);
        System.arraycopy(f19off, 0, batch, fRead.length, f19off.length);
        eq(false, r.chest.sendRaw(batch), "raw.batchSecond");
        // alpha2-brain#25: a split frame, a bad checksum and junk are refused
        eq(false, r.chest.sendRaw(java.util.Arrays.copyOfRange(f19off, 0, 6)), "raw.splitFirst");
        eq(false, r.chest.sendRaw(java.util.Arrays.copyOfRange(f19off, 6, 9)), "raw.splitSecond");
        byte[] bad = f19off.clone();
        bad[bad.length - 2] ^= 0x01;
        eq(false, r.chest.sendRaw(bad), "raw.badChecksum");
        eq(false, r.chest.sendRaw(new byte[]{0x00, 0x01}), "raw.junk");
        eq(false, r.chest.sendRaw(new byte[0]), "raw.empty");
        eq(false, r.chest.sendRaw(null), "raw.null");
        eq(0, r.sent(), "raw.nothingSent");
        // allowed frames go out unchanged
        eq(true, r.chest.sendRaw(fRead), "raw.read");
        eq(hex(fRead), hex(r.wire.toByteArray()), "raw.read.bytes");
        r = new Rig();
        eq(true, r.chest.sendRaw(frame(0x19, 0x01)), "raw.19on");
        eq(true, r.chest.sendRaw(frame(0x40, 0x00, 0x00)), "raw.40leave");
        r = new Rig();
        eq(true, r.chest.sendRaw(f19off, true), "raw.19off.allowed");
        eq(hex(f19off), hex(r.wire.toByteArray()), "raw.19off.allowed.bytes");

        System.out.println("DirectChestControllerTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
