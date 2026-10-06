package com.github.tatercertified.rustmi.math;

/**
 * Vanilla {@code Mth.atan2}, shared by every backend.
 *
 * <p>The 257-entry tables are filled from this JVM's {@code Math.asin}/{@code Math.cos},
 * exactly like vanilla's static initializer. A natively built table cannot be bit-exact:
 * HotSpot's {@code Math.cos} intrinsic and the platform libm disagree in the last ulp
 * (e.g. native {@code atan2(-12, 11)} = -0.8288489822861668, vanilla -0.8288489822861667).
 * The function is a handful of flops, so a Java body that C2 inlines also beats any FFI call.
 */
public final class VanillaAtan2 {
    private static final double FRAC_BIAS = Double.longBitsToDouble(4805340802404319232L);
    private static final double[] ASIN_TAB = new double[257];
    private static final double[] COS_TAB = new double[257];

    static {
        for (int i = 0; i < 257; i++) {
            double asin = Math.asin((double) i / 256.0D);
            ASIN_TAB[i] = asin;
            COS_TAB[i] = Math.cos(asin);
        }
    }

    private VanillaAtan2() {}

    public static double atan2(double y, double x) {
        double lengthSquared = x * x + y * y;
        if (Double.isNaN(lengthSquared)) {
            return Double.NaN;
        }
        boolean negativeY = y < 0.0D;
        if (negativeY) {
            y = -y;
        }
        boolean negativeX = x < 0.0D;
        if (negativeX) {
            x = -x;
        }
        boolean swapped = y > x;
        if (swapped) {
            double swap = x;
            x = y;
            y = swap;
        }
        // Vanilla Mth.fastInvSqrt(double).
        double half = 0.5D * lengthSquared;
        double inverseLength = Double.longBitsToDouble(
                6910469410427058090L - (Double.doubleToRawLongBits(lengthSquared) >> 1));
        inverseLength = inverseLength * (1.5D - half * inverseLength * inverseLength);
        x *= inverseLength;
        y *= inverseLength;
        double biasedY = FRAC_BIAS + y;
        int index = (int) Double.doubleToRawLongBits(biasedY);
        double roundedY = biasedY - FRAC_BIAS;
        double error = y * COS_TAB[index] - x * roundedY;
        double correction = (6.0D + error * error) * error * 0.16666666666666666D;
        double result = ASIN_TAB[index] + correction;
        if (swapped) {
            result = Math.PI / 2.0D - result;
        }
        if (negativeX) {
            result = Math.PI - result;
        }
        if (negativeY) {
            result = -result;
        }
        return result;
    }
}
