package com.open.alpha2;

/**
 * ColorTrackLogic unit test (zero dependencies, no JUnit).
 *
 * Same as ApiValidatorTest, run via `scripts/test-apivalidator.py` using pure
 * javac/java (ColorTrackLogic uses only java.*, so even android.jar is not
 * needed). `./gradlew assembleDebug` is completely unaffected (it is not in
 * the main sourceSet).
 */
public final class ColorTrackTest {
    private static int passed = 0;
    private static int failed = 0;

    private ColorTrackTest() {}

    private static void eq(float expected, float actual, String name) {
        if (Math.abs(expected - actual) < 0.5f) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(int expected, int actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(boolean expected, boolean actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eqNull(Object actual, String name) {
        if (actual == null) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected null");
        }
    }

    public static void main(String[] args) {
        // ── rgbToHsv: three primary colors + grayscale ──
        float[] red = ColorTrackLogic.rgbToHsv(255, 0, 0);
        eq(0f, red[0], "hsv.red.h");
        eq(1f, red[1], "hsv.red.s");
        eq(1f, red[2], "hsv.red.v");
        float[] green = ColorTrackLogic.rgbToHsv(0, 255, 0);
        eq(120f, green[0], "hsv.green.h");
        float[] blue = ColorTrackLogic.rgbToHsv(0, 0, 255);
        eq(240f, blue[0], "hsv.blue.h");
        float[] white = ColorTrackLogic.rgbToHsv(255, 255, 255);
        eq(0f, white[1], "hsv.white.s");
        eq(1f, white[2], "hsv.white.v");
        float[] black = ColorTrackLogic.rgbToHsv(0, 0, 0);
        eq(0f, black[2], "hsv.black.v");
        float[] gray = ColorTrackLogic.rgbToHsv(128, 128, 128);
        eq(0f, gray[1], "hsv.gray.s");
        // Non-integer hue: yellow (255,255,0) = 60 degrees.
        float[] yellow = ColorTrackLogic.rgbToHsv(255, 255, 0);
        eq(60f, yellow[0], "hsv.yellow.h");

        // ── hueInRange: plain + wrap + endpoints inclusive ──
        eq(true, ColorTrackLogic.hueInRange(10f, 0f, 30f), "hue.plain.in");
        eq(false, ColorTrackLogic.hueInRange(40f, 0f, 30f), "hue.plain.out");
        eq(true, ColorTrackLogic.hueInRange(0f, 0f, 30f), "hue.plain.loEdge");
        eq(true, ColorTrackLogic.hueInRange(350f, 335f, 25f), "hue.wrap.hi");
        eq(true, ColorTrackLogic.hueInRange(0f, 335f, 25f), "hue.wrap.zero");
        eq(true, ColorTrackLogic.hueInRange(25f, 335f, 25f), "hue.wrap.edge");
        eq(true, ColorTrackLogic.hueInRange(335f, 335f, 25f), "hue.wrap.loEdge");
        eq(false, ColorTrackLogic.hueInRange(30f, 335f, 25f), "hue.wrap.outHi");
        eq(false, ColorTrackLogic.hueInRange(330f, 335f, 25f), "hue.wrap.outLo");

        // ── matchHsv: default red band (335-25, s>=0.45, v 0.25-1) ──
        eq(true, ColorTrackLogic.matchHsv(0f, 1f, 1f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.red");
        eq(true, ColorTrackLogic.matchHsv(350f, 0.9f, 0.8f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.redWrap");
        eq(false, ColorTrackLogic.matchHsv(120f, 1f, 1f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.green");
        eq(false, ColorTrackLogic.matchHsv(0f, 0.2f, 0.9f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.lowSat");
        eq(false, ColorTrackLogic.matchHsv(0f, 0.9f, 0.1f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.dark");
        eq(false, ColorTrackLogic.matchHsv(0f, 0f, 1f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.white");
        // vMax tightened toward white light: v=1 is excluded.
        eq(false, ColorTrackLogic.matchHsv(0f, 0.9f, 1f, 335f, 25f, 0.45f, 1f, 0.25f, 0.9f), "match.vMax");
        // sMax tightened toward color (the white preset uses sMax=0.25): vivid
        // red is excluded, gray-white is included.
        eq(false, ColorTrackLogic.matchHsv(0f, 1f, 1f, 0f, 360f, 0f, 0.25f, 0.6f, 1f), "match.sMax.red");
        eq(true, ColorTrackLogic.matchHsv(0f, 0.1f, 0.95f, 0f, 360f, 0f, 0.25f, 0.6f, 1f), "match.sMax.white");
        eq(false, ColorTrackLogic.matchHsv(0f, 0.1f, 0.4f, 0f, 360f, 0f, 0.25f, 0.6f, 1f), "match.sMax.dark");

        // ── largestBlob: 5x5, (1..2,1..2) 2x2 block + (4,4) lone dot ──
        boolean[] mask = new boolean[25];
        mask[1 * 5 + 1] = true;
        mask[1 * 5 + 2] = true;
        mask[2 * 5 + 1] = true;
        mask[2 * 5 + 2] = true;
        mask[4 * 5 + 4] = true;
        ColorTrackLogic.Blob b = ColorTrackLogic.largestBlob(mask, 5, 5, 2);
        eq(4, b.count, "blob.count");
        eq(1, b.x0, "blob.x0");
        eq(2, b.x1, "blob.x1");
        eq(1, b.y0, "blob.y0");
        eq(2, b.y1, "blob.y1");
        eq(6, (int) b.sumX, "blob.sumX");
        eq(6, (int) b.sumY, "blob.sumY");
        // Nothing above the threshold, so null.
        eqNull(ColorTrackLogic.largestBlob(mask, 5, 5, 5), "blob.minPixels");
        eqNull(ColorTrackLogic.largestBlob(new boolean[25], 5, 5, 1), "blob.empty");
        eqNull(ColorTrackLogic.largestBlob(null, 5, 5, 1), "blob.null");
        // Diagonal dots do not connect: two diagonal dots are two domains, max
        // count=1.
        boolean[] diag = new boolean[25];
        diag[0] = true;
        diag[6] = true;
        ColorTrackLogic.Blob d = ColorTrackLogic.largestBlob(diag, 5, 5, 1);
        eq(1, d.count, "blob.diagonal");
        // The mask is not rewritten (the caller reuses it): run again, the
        // result is the same.
        ColorTrackLogic.Blob b2 = ColorTrackLogic.largestBlob(mask, 5, 5, 2);
        eq(4, b2.count, "blob.reusable");

        // ── walkDecision: offset turns, centered-far goes forward, close stands still ──
        eq(true, "left".equals(ColorTrackLogic.walkDecision(-0.5f, 0.05f, 0.35f, 0.12f)), "walk.left");
        eq(true, "right".equals(ColorTrackLogic.walkDecision(0.5f, 0.05f, 0.35f, 0.12f)), "walk.right");
        eq(true, "forward".equals(ColorTrackLogic.walkDecision(0.1f, 0.05f, 0.35f, 0.12f)), "walk.forward");
        eq(true, "none".equals(ColorTrackLogic.walkDecision(0.1f, 0.2f, 0.35f, 0.12f)), "walk.close");
        // Dead-zone boundary inclusive: exactly 0.35 does not turn (goes
        // forward, because it is small).
        eq(true, "forward".equals(ColorTrackLogic.walkDecision(0.35f, 0.05f, 0.35f, 0.12f)), "walk.edgeTurn");
        eq(true, "forward".equals(ColorTrackLogic.walkDecision(-0.35f, 0.05f, 0.35f, 0.12f)), "walk.edgeTurnNeg");
        // Close-enough boundary: exactly 0.12 stands still.
        eq(true, "none".equals(ColorTrackLogic.walkDecision(0f, 0.12f, 0.35f, 0.12f)), "walk.edgeClose");
        // Offset + close: the turn takes priority (go forward only when
        // straight).
        eq(true, "left".equals(ColorTrackLogic.walkDecision(-0.6f, 0.5f, 0.35f, 0.12f)), "walk.turnFirst");

        System.out.println("ColorTrackTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
