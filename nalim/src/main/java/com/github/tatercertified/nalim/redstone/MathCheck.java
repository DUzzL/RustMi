package com.github.tatercertified.nalim.redstone;

import net.minecraft.util.Mth;

import java.util.Random;

/**
 * In-game probe for the math saga: sweeps the LIVE (mixin-transformed,
 * Knot-loaded, JIT-compiled) {@link Mth} against locally computed vanilla
 * references — the exact same checks that pass bitwise in the offline
 * harness. If anything lies HERE but not offline, the corruption is a
 * live-server linking/JIT effect; if everything matches, math values are
 * exonerated entirely and the trajectory bug lives elsewhere.
 */
public final class MathCheck {

    private MathCheck() {}

    public static String run() {
        StringBuilder out = new StringBuilder();
        int total = 0;

        float[] SINE = new float[65536];
        for (int i = 0; i < 65536; i++) SINE[i] = (float) Math.sin(i / 10430.378350470453);   // vanilla 26.3 table
        double[] asinTab = new double[257];
        double[] cosTab = new double[257];
        for (int i = 0; i < 257; i++) {
            double value = (double) i / 256.0D;
            double asin = Math.asin(value);
            asinTab[i] = asin;
            cosTab[i] = Math.cos(asin);
        }

        Random r = new Random(42);
        int n = 2000;
        double[] dv = new double[n];
        float[] fv = new float[n];
        for (int i = 0; i < n; i++) {
            dv[i] = (r.nextDouble() * 2 - 1) * Math.pow(10, r.nextInt(16) - 6);
            fv[i] = (float) ((r.nextFloat() * 2 - 1) * Math.pow(10, r.nextInt(10) - 4));
        }

        int bad;

        bad = 0;
        for (double v : dv) {
            float want = SINE[(int) ((long) (v * 10430.378350470453) & 65535L)];
            if (Float.floatToRawIntBits(want) != Float.floatToRawIntBits(Mth.sin(v))) bad++;
        }
        total += report(out, "sin", bad, n);

        bad = 0;
        for (double v : dv) {
            float want = SINE[(int) ((long) (v * 10430.378350470453 + 16384.0) & 65535L)];
            if (Float.floatToRawIntBits(want) != Float.floatToRawIntBits(Mth.cos(v))) bad++;
        }
        total += report(out, "cos", bad, n);

        bad = 0;
        float[] sqrtEdges = {
                0.0F, -0.0F, Float.MIN_VALUE, Float.MIN_NORMAL, Float.MAX_VALUE,
                -1.0F, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.intBitsToFloat(0x7fa12345), Float.intBitsToFloat(0xffa12345)
        };
        for (float v : sqrtEdges) {
            if (Float.floatToRawIntBits((float) Math.sqrt(v))
                    != Float.floatToRawIntBits(Mth.sqrt(v))) bad++;
        }
        for (float v : fv) {
            if (Float.floatToRawIntBits((float) Math.sqrt(v))
                    != Float.floatToRawIntBits(Mth.sqrt(v))) bad++;
        }
        total += report(out, "sqrt", bad, sqrtEdges.length + n);

        bad = 0;
        double[] fastInvEdges = {
                0.0, -0.0, 1.0, 2.0, Double.MIN_VALUE, Double.MIN_NORMAL,
                Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.longBitsToDouble(0x7ff8123456789abCL),
                Double.longBitsToDouble(0xfff8123456789abCL)
        };
        for (double v : fastInvEdges) {
            double half = 0.5D * v;
            long bits = Double.doubleToRawLongBits(v);
            bits = 6910469410427058090L - (bits >> 1);
            double want = Double.longBitsToDouble(bits);
            want *= 1.5D - half * want * want;
            if (Double.doubleToRawLongBits(want)
                    != Double.doubleToRawLongBits(Mth.fastInvSqrt(v))) bad++;
        }
        for (double v : dv) {
            double half = 0.5D * v;
            long bits = Double.doubleToRawLongBits(v);
            bits = 6910469410427058090L - (bits >> 1);
            double want = Double.longBitsToDouble(bits);
            want *= 1.5D - half * want * want;
            if (Double.doubleToRawLongBits(want)
                    != Double.doubleToRawLongBits(Mth.fastInvSqrt(v))) bad++;
        }
        total += report(out, "fastInvSqrt", bad, fastInvEdges.length + n);
        bad = 0;
        int atanAndHsvChecks = 0;
        double[][] atanEdges = {
                {0.0D, 0.0D}, {-0.0D, 0.0D}, {0.0D, -0.0D}, {-0.0D, -0.0D},
                {1.0D, 0.0D}, {-1.0D, 0.0D}, {0.0D, 1.0D}, {0.0D, -1.0D},
                {Double.MIN_VALUE, Double.MIN_VALUE}, {Double.NaN, 1.0D}
        };
        for (double[] pair : atanEdges) {
            double want = vanillaAtan2(pair[0], pair[1], asinTab, cosTab);
            if (Double.doubleToRawLongBits(want)
                    != Double.doubleToRawLongBits(Mth.atan2(pair[0], pair[1]))) bad++;
            atanAndHsvChecks++;
        }
        for (int i = 1; i < n; i++) {
            double want = vanillaAtan2(dv[i - 1], dv[i], asinTab, cosTab);
            if (Double.doubleToRawLongBits(want)
                    != Double.doubleToRawLongBits(Mth.atan2(dv[i - 1], dv[i]))) bad++;
            atanAndHsvChecks++;
        }
        float[][] hsvEdges = {
                {0.0F, 0.0F, 0.0F}, {-0.0F, 1.0F, 1.0F},
                {-0.1F, 0.75F, 0.5F}, {0.99999994F, 1.0F, 1.0F},
                {1.0F, 1.0F, 1.0F}, {Float.NaN, 0.5F, 0.5F},
                {Float.POSITIVE_INFINITY, 0.5F, 0.5F}
        };
        for (float[] hsv : hsvEdges) {
            if (vanillaHsvToArgb(hsv[0], hsv[1], hsv[2], 0xA5)
                    != Mth.hsvToArgb(hsv[0], hsv[1], hsv[2], 0xA5)) bad++;
            if (vanillaHsvToArgb(hsv[0], hsv[1], hsv[2], 0)
                    != Mth.hsvToRgb(hsv[0], hsv[1], hsv[2])) bad++;
            atanAndHsvChecks += 2;
        }
        try {
            Mth.hsvToArgb(-0.2F, 1.0F, 1.0F, 0);
            bad++;
        } catch (RuntimeException expected) {
            // Vanilla rejects negative sectors; the native predecessor aborted here.
        }
        atanAndHsvChecks++;
        total += report(out, "atan2+hsv", bad, atanAndHsvChecks);

        bad = 0;
        int rootAndLengthChecks = 0;
        for (float v : sqrtEdges) {
            float want = 1.0F / (float) Math.sqrt(v);
            if (Float.floatToRawIntBits(want)
                    != Float.floatToRawIntBits(Mth.invSqrt(v))) bad++;
            rootAndLengthChecks++;
        }
        for (double v : fastInvEdges) {
            double want = 1.0D / Math.sqrt(v);
            if (Double.doubleToRawLongBits(want)
                    != Double.doubleToRawLongBits(Mth.invSqrt(v))) bad++;
            int wantSign = v == 0.0D ? 0 : (v > 0.0D ? 1 : -1);
            if (wantSign != Mth.sign(v)) bad++;
            rootAndLengthChecks += 2;
        }
        for (int i = 1; i < n; i++) {
            double a = dv[i - 1], b = dv[i];
            double want2 = Math.sqrt(a * a + b * b);
            if (Double.doubleToRawLongBits(want2)
                    != Double.doubleToRawLongBits(Mth.length(a, b))) bad++;
            double c = dv[(i + 1) % n];
            double want3 = Math.sqrt(a * a + b * b + c * c);
            if (Double.doubleToRawLongBits(want3)
                    != Double.doubleToRawLongBits(Mth.length(a, b, c))) bad++;
            float af = fv[i - 1], bf = fv[i], cf = fv[(i + 1) % n];
            // 26.3: Mth.length(FF)F squares in float (lengthSquared(FF)F), roots in double.
            float wantFloat2 = (float) Math.sqrt(af * af + bf * bf);
            if (Float.floatToRawIntBits(wantFloat2)
                    != Float.floatToRawIntBits(Mth.length(af, bf))) bad++;
            float wantSquared3 = af * af + bf * bf + cf * cf;
            if (Float.floatToRawIntBits(wantSquared3)
                    != Float.floatToRawIntBits(Mth.lengthSquared(af, bf, cf))) bad++;
            rootAndLengthChecks += 4;
        }
        total += report(out, "invSqrt+sign+length", bad, rootAndLengthChecks);

        bad = 0;
        int castChecks = 0;
        float[] castFloats = {
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                0.0F, -0.0F, 0.5F, -0.5F, 1.0F, -1.0F,
                0x1.0p31F, -0x1.0p31F, Float.MAX_VALUE, -Float.MAX_VALUE
        };
        for (float v : castFloats) {
            if ((int) Math.floor(v) != Mth.floor(v)) bad++;
            if ((int) Math.ceil(v) != Mth.ceil(v)) bad++;
            castChecks += 2;
        }
        double[] castDoubles = {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                0.0, -0.0, 0.5, -0.5, 1.0, -1.0,
                2_147_483_647.0, Math.nextUp(2_147_483_647.0),
                -2_147_483_648.0, Math.nextDown(-2_147_483_648.0),
                0x1.0p63, -0x1.0p63, Math.nextDown(0x1.0p63), Math.nextUp(-0x1.0p63)
        };
        for (double v : castDoubles) {
            if ((int) Math.floor(v) != Mth.floor(v)) bad++;
            if ((int) Math.ceil(v) != Mth.ceil(v)) bad++;
            if ((long) Math.floor(v) != Mth.lfloor(v)) bad++;
            if ((long) Math.ceil(v) != Mth.ceilLong(v)) bad++;
            castChecks += 4;
        }
        for (double v : dv) {
            if ((int) Math.floor(v) != Mth.floor(v)) bad++;
            if ((int) Math.ceil(v) != Mth.ceil(v)) bad++;
            if ((long) Math.floor(v) != Mth.lfloor(v)) bad++;
            if ((long) Math.ceil(v) != Mth.ceilLong(v)) bad++;
            castChecks += 4;
        }
        for (float v : fv) {
            if ((int) Math.floor(v) != Mth.floor(v)) bad++;
            if ((int) Math.ceil(v) != Mth.ceil(v)) bad++;
            castChecks += 2;
        }
        total += report(out, "floor+ceil(f+d+i+l)", bad, castChecks);

        bad = 0;
        int primitiveChecks = 0;
        float[] absFloats = {
                0.0F, -0.0F, Float.MIN_VALUE, -Float.MIN_VALUE,
                Float.MAX_VALUE, -Float.MAX_VALUE,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.intBitsToFloat(0x7fa12345), Float.intBitsToFloat(0xffa12345)
        };
        for (float v : absFloats) {
            if (Float.floatToRawIntBits(Math.abs(v)) != Float.floatToRawIntBits(Mth.abs(v))) bad++;
            primitiveChecks++;
        }
        for (float v : fv) {
            if (Float.floatToRawIntBits(Math.abs(v)) != Float.floatToRawIntBits(Mth.abs(v))) bad++;
            primitiveChecks++;
        }
        int[] absInts = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int v : absInts) {
            if (Math.abs(v) != Mth.abs(v)) bad++;
            primitiveChecks++;
        }
        for (int i = 0; i < n; i++) {
            int v = r.nextInt();
            if (Math.abs(v) != Mth.abs(v)) bad++;
            primitiveChecks++;
        }

        int[][] clampInts = {
                {5, 0, 10}, {-5, 0, 10}, {15, 0, 10}, {5, 10, 3},
                {Integer.MIN_VALUE, -10, 10}, {Integer.MAX_VALUE, -10, 10}
        };
        for (int[] c : clampInts) {
            int want = Math.min(Math.max(c[0], c[1]), c[2]);
            if (want != Mth.clamp(c[0], c[1], c[2])) bad++;
            primitiveChecks++;
        }
        long[][] clampLongs = {
                {5L, 0L, 10L}, {-5L, 0L, 10L}, {15L, 0L, 10L}, {5L, 10L, 3L},
                {Long.MIN_VALUE, -10L, 10L}, {Long.MAX_VALUE, -10L, 10L}
        };
        for (long[] c : clampLongs) {
            long want = Math.min(Math.max(c[0], c[1]), c[2]);
            if (want != Mth.clamp(c[0], c[1], c[2])) bad++;
            primitiveChecks++;
        }
        float fnan = Float.intBitsToFloat(0x7fa12345);
        float[][] clampFloats = {
                {5.0F, 0.0F, 10.0F}, {-5.0F, 0.0F, 10.0F}, {15.0F, 0.0F, 10.0F},
                {5.0F, 10.0F, 3.0F}, {-0.0F, -1.0F, 0.0F},
                {fnan, 0.0F, 1.0F}, {1.0F, fnan, 2.0F}, {1.0F, 0.0F, fnan}
        };
        for (float[] c : clampFloats) {
            float want = c[0] < c[1] ? c[1] : Math.min(c[0], c[2]);
            if (Float.floatToRawIntBits(want) != Float.floatToRawIntBits(Mth.clamp(c[0], c[1], c[2]))) bad++;
            primitiveChecks++;
        }
        double dnan = Double.longBitsToDouble(0x7ff123456789abcdL);
        double[][] clampDoubles = {
                {5.0, 0.0, 10.0}, {-5.0, 0.0, 10.0}, {15.0, 0.0, 10.0},
                {5.0, 10.0, 3.0}, {-0.0, -1.0, 0.0},
                {dnan, 0.0, 1.0}, {1.0, dnan, 2.0}, {1.0, 0.0, dnan}
        };
        for (double[] c : clampDoubles) {
            double want = c[0] < c[1] ? c[1] : Math.min(c[0], c[2]);
            if (Double.doubleToRawLongBits(want) != Double.doubleToRawLongBits(Mth.clamp(c[0], c[1], c[2]))) bad++;
            primitiveChecks++;
        }
        for (int i = 0; i < n; i++) {
            int iv = r.nextInt(), imin = r.nextInt(), imax = r.nextInt();
            if (Math.min(Math.max(iv, imin), imax) != Mth.clamp(iv, imin, imax)) bad++;
            long lv = r.nextLong(), lmin = r.nextLong(), lmax = r.nextLong();
            if (Math.min(Math.max(lv, lmin), lmax) != Mth.clamp(lv, lmin, lmax)) bad++;
            float f = fv[i], fmin = fv[(i + 3) % n], fmax = fv[(i + 11) % n];
            float fw = f < fmin ? fmin : Math.min(f, fmax);
            if (Float.floatToRawIntBits(fw) != Float.floatToRawIntBits(Mth.clamp(f, fmin, fmax))) bad++;
            double d = dv[i], dmin = dv[(i + 3) % n], dmax = dv[(i + 11) % n];
            double dw = d < dmin ? dmin : Math.min(d, dmax);
            if (Double.doubleToRawLongBits(dw) != Double.doubleToRawLongBits(Mth.clamp(d, dmin, dmax))) bad++;
            primitiveChecks += 4;
        }

        int[][] absMaxInts = {
                {0, 0}, {-1, 1}, {Integer.MIN_VALUE, 0}, {Integer.MIN_VALUE, Integer.MAX_VALUE}
        };
        for (int[] pair : absMaxInts) {
            int want = Math.max(Math.abs(pair[0]), Math.abs(pair[1]));
            if (want != Mth.absMax(pair[0], pair[1])) bad++;
            primitiveChecks++;
        }
        float[][] absMaxFloats = {
                {-0.0F, 0.0F}, {-1.0F, 2.0F}, {fnan, 1.0F}, {1.0F, fnan}
        };
        for (float[] pair : absMaxFloats) {
            float want = Math.max(Math.abs(pair[0]), Math.abs(pair[1]));
            if (Float.floatToRawIntBits(want) != Float.floatToRawIntBits(Mth.absMax(pair[0], pair[1]))) bad++;
            primitiveChecks++;
        }
        double[][] absMaxDoubles = {
                {-0.0, 0.0}, {-1.0, 2.0}, {dnan, 1.0}, {1.0, dnan}
        };
        for (double[] pair : absMaxDoubles) {
            double want = Math.max(Math.abs(pair[0]), Math.abs(pair[1]));
            if (Double.doubleToRawLongBits(want) != Double.doubleToRawLongBits(Mth.absMax(pair[0], pair[1]))) bad++;
            primitiveChecks++;
        }
        int[][] chessboard = {
                {0, 0, 1, -1}, {Integer.MIN_VALUE, 0, 0, 0},
                {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE}
        };
        for (int[] c : chessboard) {
            int want = Math.max(Math.abs(c[2] - c[0]), Math.abs(c[3] - c[1]));
            if (want != Mth.chessboardDistance(c[0], c[1], c[2], c[3])) bad++;
            primitiveChecks++;
        }
        for (int i = 0; i < n; i++) {
            int a = r.nextInt(), b = r.nextInt(), c = r.nextInt(), d = r.nextInt();
            if (Math.max(Math.abs(a), Math.abs(b)) != Mth.absMax(a, b)) bad++;
            if (Math.max(Math.abs(c - a), Math.abs(d - b)) != Mth.chessboardDistance(a, b, c, d)) bad++;
            float fa = fv[i], fb = fv[(i + 5) % n];
            float fw = Math.max(Math.abs(fa), Math.abs(fb));
            if (Float.floatToRawIntBits(fw) != Float.floatToRawIntBits(Mth.absMax(fa, fb))) bad++;
            double da = dv[i], db = dv[(i + 5) % n];
            double dw = Math.max(Math.abs(da), Math.abs(db));
            if (Double.doubleToRawLongBits(dw) != Double.doubleToRawLongBits(Mth.absMax(da, db))) bad++;
            primitiveChecks += 4;
        }
        total += report(out, "abs+clamp+absMax", bad, primitiveChecks);

        bad = 0;
        int helperChecks = 0;
        for (double value : dv) {
            double want = vanillaWrap(value);
            if (Double.doubleToRawLongBits(want)
                    != Double.doubleToRawLongBits(Mth.wrapDegrees(value))) bad++;
            helperChecks++;
        }
        for (float value : fv) {
            float want = vanillaWrap(value);
            if (Float.floatToRawIntBits(want)
                    != Float.floatToRawIntBits(Mth.wrapDegrees(value))) bad++;
            helperChecks++;
        }
        float[] wrapFloats = {
                0.0F, -0.0F, 180.0F, Math.nextDown(180.0F), Math.nextUp(180.0F),
                -180.0F, Math.nextDown(-180.0F), Math.nextUp(-180.0F),
                540.0F, -540.0F, 1.0E30F, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NaN
        };
        for (float value : wrapFloats) {
            float want = vanillaWrap(value);
            if (Float.floatToRawIntBits(want)
                    != Float.floatToRawIntBits(Mth.wrapDegrees(value))) bad++;
            helperChecks++;
        }
        double[] wrapDoubles = {
                0.0, -0.0, 180.0, Math.nextDown(180.0), Math.nextUp(180.0),
                -180.0, Math.nextDown(-180.0), Math.nextUp(-180.0),
                540.0, -540.0, 1.0E300, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN
        };
        for (double value : wrapDoubles) {
            double want = vanillaWrap(value);
            if (Double.doubleToRawLongBits(want)
                    != Double.doubleToRawLongBits(Mth.wrapDegrees(value))) bad++;
            helperChecks++;
        }
        int[] wrapInts = {
                0, 180, -180, 359, -359, 540, -540, Integer.MIN_VALUE, Integer.MAX_VALUE
        };
        for (int value : wrapInts) {
            if (vanillaWrap(value) != Mth.wrapDegrees(value)) bad++;
            helperChecks++;
        }
        long[] wrapLongs = {
                0L, 180L, -180L, 359L, -359L, 540L, -540L, Long.MIN_VALUE, Long.MAX_VALUE
        };
        for (long value : wrapLongs) {
            float want = vanillaWrap(value);
            if (Float.floatToRawIntBits(want)
                    != Float.floatToRawIntBits(Mth.wrapDegrees(value))) bad++;
            helperChecks++;
        }

        int[][] divisionInputs = {
                {7, 3}, {-7, 3}, {7, -3}, {-7, -3}, {8, 4}, {-8, 4},
                {Integer.MIN_VALUE, -1}, {16_777_217, 1}, {2_000_000_000, 7}
        };
        for (int[] input : divisionInputs) {
            int a = input[0], b = input[1];
            if (Math.floorDiv(a, b) != Mth.floorDiv(a, b)) bad++;
            if (Math.floorMod(a, b) != Mth.positiveModulo(a, b)) bad++;
            if ((a % b == 0) != Mth.isMultipleOf(a, b)) bad++;
            helperChecks += 3;
        }
        try {
            Mth.floorDiv(1, 0);
            bad++;
        } catch (ArithmeticException expected) {
            // exact vanilla failure
        }
        try {
            Mth.positiveModulo(1, 0);
            bad++;
        } catch (ArithmeticException expected) {
            // exact vanilla failure
        }
        try {
            Mth.isMultipleOf(1, 0);
            bad++;
        } catch (ArithmeticException expected) {
            // exact vanilla failure
        }
        helperChecks += 3;

        float[][] floatMods = {
                {0.0F, 360.0F}, {-0.0F, 360.0F}, {-360.0F, 360.0F},
                {-10.5F, 360.0F}, {Float.MIN_VALUE, 3.0F},
                {Float.POSITIVE_INFINITY, 360.0F}, {Float.NaN, 360.0F}, {1.0F, 0.0F}
        };
        for (float[] input : floatMods) {
            float want = (input[0] % input[1] + input[1]) % input[1];
            if (Float.floatToRawIntBits(want)
                    != Float.floatToRawIntBits(Mth.positiveModulo(input[0], input[1]))) bad++;
            helperChecks++;
        }
        double[][] doubleMods = {
                {0.0, 360.0}, {-0.0, 360.0}, {-360.0, 360.0},
                {-10.5, 360.0}, {Double.MIN_VALUE, 3.0},
                {Double.POSITIVE_INFINITY, 360.0}, {Double.NaN, 360.0}, {1.0, 0.0}
        };
        for (double[] input : doubleMods) {
            double want = (input[0] % input[1] + input[1]) % input[1];
            if (Double.doubleToRawLongBits(want)
                    != Double.doubleToRawLongBits(Mth.positiveModulo(input[0], input[1]))) bad++;
            helperChecks++;
        }

        float[][] angleEdges = {
                {10.0F, 350.0F, 5.0F}, {0.0F, 180.0F, 15.0F},
                {-0.0F, 0.0F, -0.0F}, {Float.NaN, 0.0F, 1.0F},
                {0.0F, Float.NaN, 1.0F}, {0.0F, 1.0F, Float.NaN},
                {Float.POSITIVE_INFINITY, 0.0F, 1.0F},
                {0.0F, Float.POSITIVE_INFINITY, 1.0F}
        };
        for (float[] input : angleEdges) {
            bad += checkAngleHelpers(input[0], input[1], input[2]);
            helperChecks += 5;
        }
        for (int i = 0; i < n; i++) {
            float from = fv[i], to = fv[(i + 17) % n], step = fv[(i + 29) % n];
            bad += checkAngleHelpers(from, to, step);
            int value = r.nextInt();
            if (vanillaIdealHash(value) != Mth.murmurHash3Mixer(value)) bad++;
            helperChecks += 6;
        }
        total += report(out, "wrap+mod+angles+hash", bad, helperChecks);

        bad = 0;
        int lerpChecks = 0;
        for (int i = 0; i < n; i++) {
            float d = r.nextFloat() * 4 - 2, s = fv[i], e = fv[(i + 7) % n];
            float wl = s + d * (e - s);
            if (differs(wl, Mth.lerp(d, s, e))) bad++;
            float wc = d < 0F ? s : (d > 1F ? e : s + d * (e - s));
            if (differs(wc, Mth.clampedLerp(d, s, e))) bad++;
            double dd = r.nextDouble() * 4 - 2, ds = dv[i], de = dv[(i + 7) % n];
            double wld = ds + dd * (de - ds);
            if (differs(wld, Mth.lerp(dd, ds, de))) bad++;
            double wcd = dd < 0 ? ds : (dd > 1 ? de : ds + dd * (de - ds));
            if (differs(wcd, Mth.clampedLerp(dd, ds, de))) bad++;
            lerpChecks += 4;
        }

        float[] lerpFloats = {
                0.0F, -0.0F, 1.0F, -1.0F, Float.MIN_VALUE, Float.MAX_VALUE,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN
        };
        for (float d : lerpFloats) {
            for (float s : lerpFloats) {
                for (float e : lerpFloats) {
                    float want = s + d * (e - s);
                    if (differs(want, Mth.lerp(d, s, e))) bad++;
                    float clampedWant = d < 0.0F ? s : (d > 1.0F ? e : want);
                    if (differs(clampedWant, Mth.clampedLerp(d, s, e))) bad++;
                    lerpChecks += 2;
                }
            }
        }

        double[] lerpDoubles = {
                0.0, -0.0, 1.0, -1.0, Double.MIN_VALUE, Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN
        };
        for (double d : lerpDoubles) {
            for (double s : lerpDoubles) {
                for (double e : lerpDoubles) {
                    double want = s + d * (e - s);
                    if (differs(want, Mth.lerp(d, s, e))) bad++;
                    double clampedWant = d < 0.0 ? s : (d > 1.0 ? e : want);
                    if (differs(clampedWant, Mth.clampedLerp(d, s, e))) bad++;
                    lerpChecks += 2;
                }
            }
        }
        total += report(out, "lerp+clampedLerp", bad, lerpChecks);

        bad = 0;
        int inlineChecks = 0;
        for (int i = 0; i < n; i++) {
            float delta = r.nextFloat() * 4.0F - 2.0F;
            int start = r.nextInt(), end = r.nextInt();
            int distance = end - start;
            if (start + (int) Math.floor(delta * distance) != Mth.lerpInt(delta, start, end)) bad++;
            if (start + (int) Math.floor(delta * (distance - 1)) + (delta > 0.0F ? 1 : 0)
                    != Mth.lerpDiscrete(delta, start, end)) bad++;

            double dx = dv[i], dy = dv[(i + 1) % n];
            double a = dv[(i + 2) % n], b = dv[(i + 3) % n];
            double c = dv[(i + 4) % n], d = dv[(i + 5) % n];
            double ab = a + dx * (b - a);
            double cd = c + dx * (d - c);
            double want2 = ab + dy * (cd - ab);
            if (Double.doubleToRawLongBits(want2)
                    != Double.doubleToRawLongBits(Mth.lerp2(dx, dy, a, b, c, d))) bad++;

            float p0 = fv[i], p1 = fv[(i + 1) % n];
            float p2 = fv[(i + 2) % n], p3 = fv[(i + 3) % n];
            float wantCatmull = 0.5F * (2.0F * p1
                    + (p2 - p0) * delta
                    + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * delta * delta
                    + (3.0F * p1 - p0 - 3.0F * p2 + p3) * delta * delta * delta);
            if (Float.floatToRawIntBits(wantCatmull)
                    != Float.floatToRawIntBits(Mth.catmullrom(delta, p0, p1, p2, p3))) bad++;

            // 26.3: smoothstep/smoothstepDerivative are float-only.
            float value = fv[(i + 6) % n];
            float wantFade = value * value * value
                    * (value * (value * 6.0F - 15.0F) + 10.0F);
            if (Float.floatToRawIntBits(wantFade)
                    != Float.floatToRawIntBits(Mth.smoothstep(value))) bad++;
            float wantDerivative = 30.0F * value * value
                    * (value - 1.0F) * (value - 1.0F);
            if (Float.floatToRawIntBits(wantDerivative)
                    != Float.floatToRawIntBits(Mth.smoothstepDerivative(value))) bad++;

            int cubeBits = Float.floatToIntBits(p0);
            cubeBits = 1419967116 - cubeBits / 3;
            float wantCube = Float.intBitsToFloat(cubeBits);
            wantCube = 0.6666667F * wantCube + 1.0F / (3.0F * wantCube * wantCube * p0);
            wantCube = 0.6666667F * wantCube + 1.0F / (3.0F * wantCube * wantCube * p0);
            if (Float.floatToRawIntBits(wantCube)
                    != Float.floatToRawIntBits(Mth.fastInvCubeRoot(p0))) bad++;

            int positive = r.nextInt(1 << 30) + 1;
            int smallest = 1 << (32 - Integer.numberOfLeadingZeros(positive - 1));
            if (smallest != Mth.smallestEncompassingPowerOfTwo(positive)) bad++;
            if ((int) Math.ceil(Math.sqrt(positive)) != Mth.smallestSquareSide(positive)) bad++;
            int ceilLog = positive == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(positive - 1);
            if (ceilLog != Mth.ceillog2(positive)) bad++;
            if (31 - Integer.numberOfLeadingZeros(positive) != Mth.log2(positive)) bad++;

            float from = fv[(i + 4) % n], to = fv[(i + 5) % n];
            float wantDegrees = from + delta * vanillaWrap(to - from);
            if (Float.floatToRawIntBits(wantDegrees)
                    != Float.floatToRawIntBits(Mth.rotLerp(delta, from, to))) bad++;
            float radians = to - from;
            while (radians < -3.1415927F) radians += 6.2831855F;
            while (radians >= 3.1415927F) radians -= 6.2831855F;
            float wantRadians = from + delta * radians;
            if (Float.floatToRawIntBits(wantRadians)
                    != Float.floatToRawIntBits(Mth.rotLerpRad(delta, from, to))) bad++;
            float deviation = Math.abs(fv[(i + 6) % n]) + 1.0F;
            float wantWave = (Math.abs(from % deviation - deviation * 0.5F)
                    - deviation * 0.25F) / (deviation * 0.25F);
            if (Float.floatToRawIntBits(wantWave)
                    != Float.floatToRawIntBits(Mth.triangleWave(from, deviation))) bad++;
            inlineChecks += 14;
        }
        total += report(out, "inline power+interpolation", bad, inlineChecks);

        bad = 0;
        int mappingChecks = 0;
        double[][] mappingEdges = {
                {0.0, 0.0, 1.0, 10.0, 20.0},
                {-0.0, 0.0, -0.0, -0.0, 0.0},
                {1.0, 1.0, 1.0, -1.0, 1.0},
                {Double.NaN, 0.0, 1.0, 2.0, 3.0},
                {0.5, Double.NaN, 1.0, 2.0, 3.0},
                {0.5, 0.0, Double.NaN, 2.0, 3.0},
                {0.5, 0.0, 1.0, Double.NaN, 3.0},
                {0.5, 0.0, 1.0, 2.0, Double.NaN},
                {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY, -Double.MAX_VALUE, Double.MAX_VALUE},
                {2.0, 1.0, 1.0, 10.0, 20.0},
                {-2.0, -1.0, -1.0, 10.0, 20.0},
                {Double.MAX_VALUE, -Double.MAX_VALUE, 0.0, -1.0, 1.0},
                {-Double.MAX_VALUE, Double.MAX_VALUE, 0.0, -1.0, 1.0}
        };
        for (double[] values : mappingEdges) {
            double value = values[0], oldStart = values[1], oldEnd = values[2];
            double newStart = values[3], newEnd = values[4];
            double progress = (value - oldStart) / (oldEnd - oldStart);
            double mapped = newStart + progress * (newEnd - newStart);
            double clampedMapped = progress < 0.0 ? newStart
                    : (progress > 1.0 ? newEnd : mapped);
            if (Double.doubleToRawLongBits(progress)
                    != Double.doubleToRawLongBits(Mth.inverseLerp(value, oldStart, oldEnd))) bad++;
            if (Double.doubleToRawLongBits(mapped)
                    != Double.doubleToRawLongBits(Mth.map(value, oldStart, oldEnd, newStart, newEnd))) bad++;
            if (Double.doubleToRawLongBits(clampedMapped)
                    != Double.doubleToRawLongBits(Mth.clampedMap(value, oldStart, oldEnd, newStart, newEnd))) bad++;
            float floatValue = (float) value, floatOldStart = (float) oldStart;
            float floatOldEnd = (float) oldEnd, floatNewStart = (float) newStart, floatNewEnd = (float) newEnd;
            float floatProgress = (floatValue - floatOldStart) / (floatOldEnd - floatOldStart);
            float floatMapped = floatNewStart + floatProgress * (floatNewEnd - floatNewStart);
            float floatClampedMapped = floatProgress < 0.0F ? floatNewStart
                    : (floatProgress > 1.0F ? floatNewEnd : floatMapped);
            if (Float.floatToRawIntBits(floatProgress)
                    != Float.floatToRawIntBits(Mth.inverseLerp(floatValue, floatOldStart, floatOldEnd))) bad++;
            if (Float.floatToRawIntBits(floatMapped)
                    != Float.floatToRawIntBits(Mth.map(
                            floatValue, floatOldStart, floatOldEnd, floatNewStart, floatNewEnd))) bad++;
            if (Float.floatToRawIntBits(floatClampedMapped)
                    != Float.floatToRawIntBits(Mth.clampedMap(
                            floatValue, floatOldStart, floatOldEnd, floatNewStart, floatNewEnd))) bad++;
            double squaredHypot = value * value + oldStart * oldStart;
            if (Double.doubleToRawLongBits(squaredHypot)
                    != Double.doubleToRawLongBits(Mth.lengthSquared(value, oldStart))) bad++;
            double squaredMagnitude = value * value + oldStart * oldStart + oldEnd * oldEnd;
            if (Double.doubleToRawLongBits(squaredMagnitude)
                    != Double.doubleToRawLongBits(Mth.lengthSquared(value, oldStart, oldEnd))) bad++;
            mappingChecks += 8;
        }
        for (int i = 0; i < n; i++) {
            double value = dv[i], oldStart = dv[(i + 1) % n], oldEnd = dv[(i + 3) % n];
            double newStart = dv[(i + 5) % n], newEnd = dv[(i + 7) % n];
            double progress = (value - oldStart) / (oldEnd - oldStart);
            double mapped = newStart + progress * (newEnd - newStart);
            double clampedMapped = progress < 0.0 ? newStart
                    : (progress > 1.0 ? newEnd : mapped);
            if (Double.doubleToRawLongBits(progress)
                    != Double.doubleToRawLongBits(Mth.inverseLerp(value, oldStart, oldEnd))) bad++;
            if (Double.doubleToRawLongBits(mapped)
                    != Double.doubleToRawLongBits(Mth.map(value, oldStart, oldEnd, newStart, newEnd))) bad++;
            if (Double.doubleToRawLongBits(clampedMapped)
                    != Double.doubleToRawLongBits(Mth.clampedMap(value, oldStart, oldEnd, newStart, newEnd))) bad++;

            float floatValue = fv[i], floatOldStart = fv[(i + 1) % n], floatOldEnd = fv[(i + 3) % n];
            float floatNewStart = fv[(i + 5) % n], floatNewEnd = fv[(i + 7) % n];
            float floatProgress = (floatValue - floatOldStart) / (floatOldEnd - floatOldStart);
            float floatMapped = floatNewStart + floatProgress * (floatNewEnd - floatNewStart);
            float floatClampedMapped = floatProgress < 0.0F ? floatNewStart
                    : (floatProgress > 1.0F ? floatNewEnd : floatMapped);
            if (Float.floatToRawIntBits(floatProgress)
                    != Float.floatToRawIntBits(Mth.inverseLerp(floatValue, floatOldStart, floatOldEnd))) bad++;
            if (Float.floatToRawIntBits(floatMapped)
                    != Float.floatToRawIntBits(Mth.map(
                            floatValue, floatOldStart, floatOldEnd, floatNewStart, floatNewEnd))) bad++;
            if (Float.floatToRawIntBits(floatClampedMapped)
                    != Float.floatToRawIntBits(Mth.clampedMap(
                            floatValue, floatOldStart, floatOldEnd, floatNewStart, floatNewEnd))) bad++;

            float floatFraction = floatValue - (int) Math.floor(floatValue);
            if (Float.floatToRawIntBits(floatFraction)
                    != Float.floatToRawIntBits(Mth.frac(floatValue))) bad++;
            double doubleFraction = value - (long) Math.floor(value);
            if (Double.doubleToRawLongBits(doubleFraction)
                    != Double.doubleToRawLongBits(Mth.frac(value))) bad++;

            double b = dv[(i + 11) % n], c = dv[(i + 13) % n];
            double squaredHypot = value * value + b * b;
            if (Double.doubleToRawLongBits(squaredHypot)
                    != Double.doubleToRawLongBits(Mth.lengthSquared(value, b))) bad++;
            double squaredMagnitude = value * value + b * b + c * c;
            if (Double.doubleToRawLongBits(squaredMagnitude)
                    != Double.doubleToRawLongBits(Mth.lengthSquared(value, b, c))) bad++;
            mappingChecks += 10;
        }
        float[] fractionFloats = {
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                0.0F, -0.0F, Float.MAX_VALUE, -Float.MAX_VALUE
        };
        for (float value : fractionFloats) {
            float want = value - (int) Math.floor(value);
            if (Float.floatToRawIntBits(want) != Float.floatToRawIntBits(Mth.frac(value))) bad++;
            mappingChecks++;
        }
        double[] fractionDoubles = {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                0.0, -0.0, Double.MAX_VALUE, -Double.MAX_VALUE
        };
        for (double value : fractionDoubles) {
            double want = value - (long) Math.floor(value);
            if (Double.doubleToRawLongBits(want) != Double.doubleToRawLongBits(Mth.frac(value))) bad++;
            mappingChecks++;
        }
        total += report(out, "map+frac+lengthSquared", bad, mappingChecks);

        bad = 0;
        int squareChecks = 0;
        float[] squareFloats = {
                0.0F, -0.0F, Float.MIN_VALUE, -Float.MIN_VALUE, 1.0E-20F,
                Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.intBitsToFloat(0x7fa12345), Float.intBitsToFloat(0xffa12345)
        };
        for (float v : squareFloats) {
            if (Float.floatToRawIntBits(v * v) != Float.floatToRawIntBits(Mth.square(v))) bad++;
            squareChecks++;
        }
        for (float v : fv) {
            if (Float.floatToRawIntBits(v * v) != Float.floatToRawIntBits(Mth.square(v))) bad++;
            squareChecks++;
        }

        double[] squareDoubles = {
                0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, 1.0E-160,
                Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.longBitsToDouble(0x7ff123456789abcdL),
                Double.longBitsToDouble(0xfff123456789abcdL)
        };
        for (double v : squareDoubles) {
            if (Double.doubleToRawLongBits(v * v) != Double.doubleToRawLongBits(Mth.square(v))) bad++;
            squareChecks++;
        }
        for (double v : dv) {
            if (Double.doubleToRawLongBits(v * v) != Double.doubleToRawLongBits(Mth.square(v))) bad++;
            squareChecks++;
        }

        int[] squareInts = {
                0, 1, -1, 46_340, 46_341, -46_341, Integer.MIN_VALUE, Integer.MAX_VALUE
        };
        for (int v : squareInts) {
            if (v * v != Mth.square(v)) bad++;
            squareChecks++;
        }

        long[] squareLongs = {
                0L, 1L, -1L, 3_037_000_499L, 3_037_000_500L, -3_037_000_500L,
                Long.MIN_VALUE, Long.MAX_VALUE
        };
        for (long v : squareLongs) {
            if (v * v != Mth.square(v)) bad++;
            squareChecks++;
        }

        for (int i = 0; i < n; i++) {
            int iv = r.nextInt();
            if (iv * iv != Mth.square(iv)) bad++;
            long lv = r.nextLong();
            if (lv * lv != Mth.square(lv)) bad++;
            squareChecks += 2;
        }
        total += report(out, "square(f+d+i+l)", bad, squareChecks);

        bad = 0;
        int packChecks = 0;
        float[] packDegrees = {
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                0.0F, -0.0F, 180.0F, -180.0F, 360.0F, -360.0F,
                1.0E30F, -1.0E30F
        };
        for (float v : packDegrees) {
            byte want = (byte) ((int) Math.floor(v * 256.0F / 360.0F));
            if (want != Mth.packDegrees(v)) bad++;
            packChecks++;
        }
        for (float v : fv) {
            byte want = (byte) ((int) Math.floor(v * 256.0F / 360.0F));
            if (want != Mth.packDegrees(v)) bad++;
            packChecks++;
        }
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte packed = (byte) i;
            float want = packed * 360 / 256.0F;
            if (Float.floatToRawIntBits(want)
                    != Float.floatToRawIntBits(Mth.unpackDegrees(packed))) bad++;
            packChecks++;
        }
        total += report(out, "pack+unpackDegrees", bad, packChecks);

        // Mth.equal — сидит в Entity.move (horizontalCollision) → траектории.
        // Пары: треть — вокруг порога 1.0E-5F, остальные далёкие; мусор в
        // верхних битах EAX от boolean-возврата даёт ложные true.
        bad = 0;
        int equalChecks = 0;
        for (int i = 0; i < n; i++) {
            float a = fv[i];
            float b = (i % 3 == 0) ? a + (r.nextFloat() - 0.5F) * 4.0E-5F : fv[(i + 13) % n];
            if ((Math.abs(b - a) < 1.0E-5F) != Mth.equal(a, b)) bad++;
            double da = dv[i];
            double db = (i % 3 == 0) ? da + (r.nextDouble() - 0.5) * 4.0E-5 : dv[(i + 13) % n];
            if ((Math.abs(db - da) < (double) 1.0E-5F) != Mth.equal(da, db)) bad++;
            equalChecks += 2;
        }
        float thresholdF = 1.0E-5F;
        float[][] equalFloats = {
                {-0.0F, 0.0F}, {Float.NaN, 0.0F},
                {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY},
                {0.0F, thresholdF}, {0.0F, Math.nextDown(thresholdF)},
                {0.0F, Math.nextUp(thresholdF)}
        };
        for (float[] input : equalFloats) {
            if ((Math.abs(input[1] - input[0]) < thresholdF)
                    != Mth.equal(input[0], input[1])) bad++;
            equalChecks++;
        }
        double thresholdD = (double) 1.0E-5F;
        double[][] equalDoubles = {
                {-0.0, 0.0}, {Double.NaN, 0.0},
                {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                {0.0, 1.0E-5}, {0.0, thresholdD},
                {0.0, Math.nextDown(thresholdD)}, {0.0, Math.nextUp(thresholdD)}
        };
        for (double[] input : equalDoubles) {
            if ((Math.abs(input[1] - input[0]) < thresholdD)
                    != Mth.equal(input[0], input[1])) bad++;
            equalChecks++;
        }
        total += report(out, "equal(f+d)", bad, equalChecks);

        bad = 0;
        for (int i = 0; i < n; i++) {
            int a = r.nextInt(1 << 20) - (1 << 19);
            int b = r.nextInt(64) + 1;
            if ((a % b == 0) != Mth.isMultipleOf(a, b)) bad++;
            int p = r.nextInt(1 << 24) + 1;
            if (((p & (p - 1)) == 0) != Mth.isPowerOfTwo(p)) bad++;
        }
        total += report(out, "isMultipleOf+isPowerOfTwo", bad, 2 * n);

        // Широкие углы — класс насыщения (упстримный `as i32` виден только при |v| > ~2.06e5)
        bad = 0;
        for (int i = 0; i < n; i++) {
            double v = (r.nextDouble() * 2 - 1) * 1.0E7;
            float want = SINE[(int) ((long) (v * 10430.378350470453) & 65535L)];
            if (Float.floatToRawIntBits(want) != Float.floatToRawIntBits(Mth.sin(v))) bad++;
            float wantC = SINE[(int) ((long) (v * 10430.378350470453 + 16384.0) & 65535L)];
            if (Float.floatToRawIntBits(wantC) != Float.floatToRawIntBits(Mth.cos(v))) bad++;
        }
        total += report(out, "sin/cos±1e7", bad, 2 * n);

        out.append(total == 0 ? "ВСЁ СОВПАЛО — живой Mth бит-в-бит ванильный" : "ИТОГО РАСХОЖДЕНИЙ: " + total);
        return out.toString();
    }

    private static int checkAngleHelpers(float from, float to, float step) {
        int bad = 0;
        float difference = vanillaWrap(to - from);
        if (Float.floatToRawIntBits(difference)
                != Float.floatToRawIntBits(Mth.degreesDifference(from, to))) bad++;
        float absoluteDifference = Math.abs(difference);
        if (Float.floatToRawIntBits(absoluteDifference)
                != Float.floatToRawIntBits(Mth.degreesDifferenceAbs(from, to))) bad++;
        float clampedDifference = vanillaClamp(difference, -step, step);
        float rotated = to - clampedDifference;
        if (Float.floatToRawIntBits(rotated)
                != Float.floatToRawIntBits(Mth.rotateIfNecessary(from, to, step))) bad++;
        float approached = vanillaApproach(from, to, step);
        if (Float.floatToRawIntBits(approached)
                != Float.floatToRawIntBits(Mth.approach(from, to, step))) bad++;
        float approachedDegrees = vanillaApproach(from, from + difference, step);
        if (Float.floatToRawIntBits(approachedDegrees)
                != Float.floatToRawIntBits(Mth.approachDegrees(from, to, step))) bad++;
        return bad;
    }

    private static float vanillaClamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float vanillaApproach(float from, float to, float step) {
        step = Math.abs(step);
        return from < to
                ? vanillaClamp(from + step, from, to)
                : vanillaClamp(from - step, to, from);
    }

    private static int vanillaWrap(int degrees) {
        int wrapped = degrees % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    private static float vanillaWrap(long degrees) {
        float wrapped = (float) (degrees % 360L);
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private static float vanillaWrap(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private static double vanillaWrap(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    // Bit-exact, except that any two NaNs match: the JLS leaves NaN bit patterns
    // unspecified, and when both operands of a float op are NaN (e.g. NaN * (Inf - Inf))
    // x86 propagates the first operand's sign, so interpreter vs C2 operand order flips it.
    private static boolean differs(float want, float got) {
        return Float.floatToRawIntBits(want) != Float.floatToRawIntBits(got)
                && !(Float.isNaN(want) && Float.isNaN(got));
    }

    private static boolean differs(double want, double got) {
        return Double.doubleToRawLongBits(want) != Double.doubleToRawLongBits(got)
                && !(Double.isNaN(want) && Double.isNaN(got));
    }

    private static int vanillaIdealHash(int value) {
        value ^= value >>> 16;
        value *= -2048144789;
        value ^= value >>> 13;
        value *= -1028477387;
        return value ^ value >>> 16;
    }

    private static double vanillaAtan2(
            double y, double x, double[] asinTab, double[] cosTab) {
        double lengthSquared = x * x + y * y;
        if (Double.isNaN(lengthSquared)) return Double.NaN;
        boolean negativeY = y < 0.0D;
        if (negativeY) y = -y;
        boolean negativeX = x < 0.0D;
        if (negativeX) x = -x;
        boolean swapped = y > x;
        if (swapped) {
            double swap = x;
            x = y;
            y = swap;
        }
        double inverseLength = vanillaFastInverseSqrt(lengthSquared);
        x *= inverseLength;
        y *= inverseLength;
        double biasedY = Double.longBitsToDouble(4805340802404319232L) + y;
        int index = (int) Double.doubleToRawLongBits(biasedY);
        double roundedY = biasedY - Double.longBitsToDouble(4805340802404319232L);
        double error = y * cosTab[index] - x * roundedY;
        double correction = (6.0D + error * error) * error * 0.16666666666666666D;
        double result = asinTab[index] + correction;
        if (swapped) result = Math.PI / 2.0D - result;
        if (negativeX) result = Math.PI - result;
        if (negativeY) result = -result;
        return result;
    }

    private static double vanillaFastInverseSqrt(double x) {
        double half = 0.5D * x;
        long bits = Double.doubleToRawLongBits(x);
        bits = 6910469410427058090L - (bits >> 1);
        x = Double.longBitsToDouble(bits);
        x *= 1.5D - half * x * x;
        return x;
    }

    private static int vanillaHsvToArgb(float hue, float saturation, float value, int alpha) {
        int sector = (int) (hue * 6.0F) % 6;
        float fraction = hue * 6.0F - sector;
        float low = value * (1.0F - saturation);
        float falling = value * (1.0F - fraction * saturation);
        float rising = value * (1.0F - (1.0F - fraction) * saturation);
        float red;
        float green;
        float blue;
        switch (sector) {
            case 0 -> { red = value; green = rising; blue = low; }
            case 1 -> { red = falling; green = value; blue = low; }
            case 2 -> { red = low; green = value; blue = rising; }
            case 3 -> { red = low; green = falling; blue = value; }
            case 4 -> { red = rising; green = low; blue = value; }
            case 5 -> { red = value; green = low; blue = falling; }
            default -> throw new RuntimeException(
                    "Something went wrong when converting from HSV to RGB. Input was "
                            + hue + ", " + saturation + ", " + value);
        }
        int r = Math.min(Math.max((int) (red * 255.0F), 0), 255);
        int g = Math.min(Math.max((int) (green * 255.0F), 0), 255);
        int b = Math.min(Math.max((int) (blue * 255.0F), 0), 255);
        return alpha << 24 | r << 16 | g << 8 | b;
    }

    private static int report(StringBuilder out, String name, int bad, int n) {
        if (bad > 0) {
            out.append(name).append(": ").append(bad).append('/').append(n).append("  ");
        }
        return bad;
    }
}
