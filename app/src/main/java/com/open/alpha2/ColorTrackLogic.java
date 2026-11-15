package com.open.alpha2;

/**
 * Pure color-tracking algorithm (zero dependencies: java.* only, no android.* or project classes).
 *
 * Why a separate file: {@link ColorTrackCenter} pulls in CameraController/UbxApi
 * (which pulls in the external com.ubtechinc AAR); the pure javac runner in
 * `scripts/test-apivalidator.py` cannot compile that heavy closure. This file
 * uses java.* only, so ColorTrackTest tests it directly, without any stub.
 * The Center side calls it directly, with no extra wrapper layer.
 */
public final class ColorTrackLogic {
    private ColorTrackLogic() {}

    /** RGB(0-255) to HSV: {h 0-360, s 0-1, v 0-1}. Gray (delta=0) reports h = 0. */
    public static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float h = 0f;
        if (delta > 0f) {
            if (max == rf) {
                h = 60f * (((gf - bf) / delta) % 6f);
            } else if (max == gf) {
                h = 60f * (((bf - rf) / delta) + 2f);
            } else {
                h = 60f * (((rf - gf) / delta) + 4f);
            }
            if (h < 0f) h += 360f;
        }
        float s = (max <= 0f) ? 0f : delta / max;
        return new float[] { h, s, max };
    }

    /** Hue range test (wrap-aware: hMin>hMax crosses 0 deg, e.g. red 335-25). Endpoints inclusive. */
    public static boolean hueInRange(float h, float hMin, float hMax) {
        if (hMin <= hMax) return h >= hMin && h <= hMax;
        return h >= hMin || h <= hMax;
    }

    public static boolean matchHsv(float h, float s, float v,
            float hMin, float hMax, float sMin, float sMax, float vMin, float vMax) {
        return hueInRange(h, hMin, hMax) && s >= sMin && s <= sMax && v >= vMin && v <= vMax;
    }

    /** Largest connected blob (4-neighbor flood fill, iterative; no recursion, no stack overflow). */
    public static final class Blob {
        public int count;
        public long sumX;
        public long sumY;
        /** inclusive bbox。 */
        public int x0;
        public int y0;
        public int x1;
        public int y1;
    }

    /**
     * Returns the largest blob; returns null if every blob is smaller than minPixels (nothing found).
     * Does not modify the mask (visited is a separate array; the caller can reuse its mask).
     */
    public static Blob largestBlob(boolean[] mask, int w, int h, int minPixels) {
        if (mask == null || mask.length < w * h || w <= 0 || h <= 0) return null;
        boolean[] visited = new boolean[w * h];
        int[] stack = new int[w * h];
        Blob best = null;
        for (int i = 0; i < w * h; i++) {
            if (!mask[i] || visited[i]) continue;
            // New connected region: flood fill measures count, centroid, and bbox.
            int count = 0;
            long sumX = 0;
            long sumY = 0;
            int x0 = w;
            int y0 = h;
            int x1 = -1;
            int y1 = -1;
            int sp = 0;
            stack[sp++] = i;
            visited[i] = true;
            while (sp > 0) {
                int cur = stack[--sp];
                int cx = cur % w;
                int cy = cur / w;
                count++;
                sumX += cx;
                sumY += cy;
                if (cx < x0) x0 = cx;
                if (cx > x1) x1 = cx;
                if (cy < y0) y0 = cy;
                if (cy > y1) y1 = cy;
                // 4-neighbor (diagonals excluded: diagonal color patches are not one blob, which cuts false merges).
                if (cx > 0) {
                    int n = cur - 1;
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n; }
                }
                if (cx + 1 < w) {
                    int n = cur + 1;
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n; }
                }
                if (cy > 0) {
                    int n = cur - w;
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n; }
                }
                if (cy + 1 < h) {
                    int n = cur + w;
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n; }
                }
            }
            if (count >= minPixels && (best == null || count > best.count)) {
                Blob b = new Blob();
                b.count = count;
                b.sumX = sumX;
                b.sumY = sumY;
                b.x0 = x0;
                b.y0 = y0;
                b.x1 = x1;
                b.y1 = y1;
                best = b;
            }
        }
        return best;
    }

    /**
     * Walk-follow decision (pure function, unit-testable): returns "left"/"right"/"forward"/"none".
     * Turn only when the offset is large (face the target before moving, so the robot does not drift further).
     * Centered but small (far) takes one step forward; large enough (close) stands still (the head keeps aiming).
     */
    public static String walkDecision(float dx, float coverage, float turnDb, float closeCoverage) {
        if (Math.abs(dx) > turnDb) return dx < 0 ? "left" : "right";
        if (coverage < closeCoverage) return "forward";
        return "none";
    }
}
