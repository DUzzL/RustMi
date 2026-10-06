package com.github.tatercertified.nalim;

/** Boundary checks for Nalim helpers whose Java failure semantics are load-bearing. */
public final class MthInlineSemanticsTest {
    private MthInlineSemanticsTest() {}
    private static final double FRAC_BIAS = Double.longBitsToDouble(4805340802404319232L);
    private static final double[] ASIN_TAB = new double[257];
    private static final double[] COS_TAB = new double[257];

    static {
        for (int i = 0; i < 257; i++) {
            double value = (double) i / 256.0D;
            double asin = Math.asin(value);
            ASIN_TAB[i] = asin;
            COS_TAB[i] = Math.cos(asin);
        }
    }

    public static void main(String[] args) {
        MthImpl mth = new MthImpl();
        parseIntMatchesVanilla(mth);
        divisionFailuresStayJava(mth);
        floatEdgesStayBitExact(mth);
        fastInverseSqrtMatchesVanilla(mth);
        atan2AndHsvMatchVanilla(mth);
        remainingScalarCutoversMatchVanilla(mth);
        remainingInlineMathMatchesVanilla(mth);
    }

    private static void parseIntMatchesVanilla(MthImpl mth) {
        check(mth.parseInt(null, 91) == 91, "null fallback");
        check(mth.parseInt("", 91) == 91, "empty fallback");
        check(mth.parseInt(" ", 91) == 91, "space is not trimmed");
        check(mth.parseInt("\t12", 91) == 91, "tab is not trimmed");
        check(mth.parseInt("12abc", 91) == 91, "partial parse rejected");
        check(mth.parseInt("+12", 91) == 12, "plus sign");
        check(mth.parseInt("-", 91) == 91, "bare minus rejected");
        check(mth.parseInt("+", 91) == 91, "bare plus rejected");
        check(mth.parseInt("-2147483648", 91) == Integer.MIN_VALUE, "minimum int");
        check(mth.parseInt("2147483648", 91) == 91, "positive overflow fallback");
        check(mth.parseInt("-2147483649", 91) == 91, "negative overflow fallback");
        check(mth.parseInt("١٢", 91) == 12, "Unicode decimal digits");
        check(mth.parseInt("0", 91) == 0, "zero");
        check(mth.parseInt("42", 91) == 42, "positive value");
        check(mth.parseInt("-7", 91) == -7, "negative value");
    }

    private static void divisionFailuresStayJava(MthImpl mth) {
        expectArithmetic(() -> mth.floorDiv(1, 0), "floorDiv by zero");
        expectArithmetic(() -> mth.floorMod(1, 0), "floorMod by zero");
        expectArithmetic(() -> mth.isMultipleOf(1, 0), "isMultipleOf by zero");
        check(mth.floorDiv(Integer.MIN_VALUE, -1) == Integer.MIN_VALUE, "floorDiv overflow");
        check(mth.floorMod(Integer.MIN_VALUE, -1) == 0, "floorMod overflow");
        check(mth.isMultipleOf(Integer.MIN_VALUE, -1), "multiple overflow");
    }

    private static void floatEdgesStayBitExact(MthImpl mth) {
        check(Float.floatToRawIntBits(mth.wrapDegrees(-0.0F))
                        == Float.floatToRawIntBits(-0.0F),
                "wrap preserves negative zero");
        check(Float.isNaN(mth.wrapDegrees(Float.POSITIVE_INFINITY)), "wrap infinity");
        check(Double.isNaN(mth.floorMod(1.0, 0.0)), "double modulo zero");
        check(Float.isNaN(mth.clampAngle(Float.NaN, 0.0F, 1.0F)), "angle NaN");
        check(!mth.approximatelyEquals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                "equal infinity");
        double widenedFloatThreshold = (double) 1.0E-5F;
        check(!mth.approximatelyEquals(0.0, 1.0E-5),
                "double literal is above widened-float threshold");
        check(mth.approximatelyEquals(0.0, Math.nextDown(widenedFloatThreshold)),
                "one ulp below threshold");
        check(!mth.approximatelyEquals(0.0, widenedFloatThreshold), "strict threshold");
        for (int value = Byte.MIN_VALUE; value <= Byte.MAX_VALUE; value++) {
            byte packed = (byte) value;
            float want = packed * 360 / 256.0F;
            check(Float.floatToRawIntBits(mth.unpackDegrees(packed))
                            == Float.floatToRawIntBits(want),
                    "unpack byte " + value);
        }
    }

    private static void fastInverseSqrtMatchesVanilla(MthImpl mth) {
        double[] edges = {
                0.0, -0.0, 1.0, 2.0, Double.MIN_VALUE, Double.MIN_NORMAL,
                Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.longBitsToDouble(0x7ff8123456789abCL),
                Double.longBitsToDouble(0xfff8123456789abCL)
        };
        for (double value : edges) {
            check(Double.doubleToRawLongBits(mth.fastInverseSqrt(value))
                            == Double.doubleToRawLongBits(vanillaFastInverseSqrt(value)),
                    "fast inverse sqrt edge " + Long.toHexString(Double.doubleToRawLongBits(value)));
        }
        long state = 0x6A09E667F3BCC909L;
        for (int i = 0; i < 10_000; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            double value = Double.longBitsToDouble(state);
            check(Double.doubleToRawLongBits(mth.fastInverseSqrt(value))
                            == Double.doubleToRawLongBits(vanillaFastInverseSqrt(value)),
                    "fast inverse sqrt random " + Long.toHexString(state));
        }
    }

    private static double vanillaFastInverseSqrt(double x) {
        double half = 0.5D * x;
        long bits = Double.doubleToRawLongBits(x);
        bits = 6910469410427058090L - (bits >> 1);
        x = Double.longBitsToDouble(bits);
        x *= 1.5D - half * x * x;
        return x;
    }

    private static void atan2AndHsvMatchVanilla(MthImpl mth) {
        double[][] atanEdges = {
                {0.0D, 0.0D}, {-0.0D, 0.0D}, {0.0D, -0.0D}, {-0.0D, -0.0D},
                {1.0D, 0.0D}, {-1.0D, 0.0D}, {0.0D, 1.0D}, {0.0D, -1.0D},
                {Double.MIN_VALUE, Double.MIN_VALUE},
                {Double.MIN_NORMAL, -Double.MIN_NORMAL},
                {Double.NaN, 1.0D}, {1.0D, Double.NaN}
        };
        for (double[] pair : atanEdges) {
            check(Double.doubleToRawLongBits(mth.atan2(pair[0], pair[1]))
                            == Double.doubleToRawLongBits(vanillaAtan2(pair[0], pair[1])),
                    "atan2 edge " + Double.toHexString(pair[0]) + ", " + Double.toHexString(pair[1]));
        }
        long state = 0xBB67AE8584CAA73BL;
        for (int i = 0; i < 10_000; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            double y = (int) state * 0x1.0p-8;
            double x = (int) (state >>> 32) * 0x1.0p-8;
            check(Double.doubleToRawLongBits(mth.atan2(y, x))
                            == Double.doubleToRawLongBits(vanillaAtan2(y, x)),
                    "atan2 random " + Long.toHexString(state));
        }

        float[][] hsvEdges = {
                {0.0F, 0.0F, 0.0F}, {-0.0F, 1.0F, 1.0F},
                {-0.1F, 0.75F, 0.5F}, {0.99999994F, 1.0F, 1.0F},
                {1.0F, 1.0F, 1.0F}, {Float.NaN, 0.5F, 0.5F},
                {Float.POSITIVE_INFINITY, 0.5F, 0.5F}
        };
        for (float[] hsv : hsvEdges) {
            int expected = vanillaHsvToArgb(hsv[0], hsv[1], hsv[2], 0xA5);
            check(mth.hsvToArgb(hsv[0], hsv[1], hsv[2], 0xA5) == expected,
                    "HSV edge " + hsv[0] + ", " + hsv[1] + ", " + hsv[2]);
            check(mth.hsvToRgb(hsv[0], hsv[1], hsv[2])
                            == vanillaHsvToArgb(hsv[0], hsv[1], hsv[2], 0),
                    "HSV RGB edge " + hsv[0] + ", " + hsv[1] + ", " + hsv[2]);
        }
        expectRuntime(() -> mth.hsvToArgb(-0.2F, 1.0F, 1.0F, 0),
                "negative HSV sector");
        expectRuntime(() -> mth.hsvToArgb(Float.NEGATIVE_INFINITY, 1.0F, 1.0F, 0),
                "negative infinite HSV sector");
    }

    private static double vanillaAtan2(double y, double x) {
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
        double biasedY = FRAC_BIAS + y;
        int index = (int) Double.doubleToRawLongBits(biasedY);
        double roundedY = biasedY - FRAC_BIAS;
        double error = y * COS_TAB[index] - x * roundedY;
        double correction = (6.0D + error * error) * error * 0.16666666666666666D;
        double result = ASIN_TAB[index] + correction;
        if (swapped) result = Math.PI / 2.0D - result;
        if (negativeX) result = Math.PI - result;
        if (negativeY) result = -result;
        return result;
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


    private static void remainingScalarCutoversMatchVanilla(MthImpl mth) {
        float[] floatEdges = {
                0.0F, -0.0F, Float.MIN_VALUE, Float.MIN_NORMAL, Float.MAX_VALUE,
                -1.0F, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.intBitsToFloat(0x7fa12345), Float.intBitsToFloat(0xffa12345)
        };
        for (float value : floatEdges) {
            check(Float.floatToRawIntBits(mth.sqrt(value))
                            == Float.floatToRawIntBits((float) Math.sqrt(value)),
                    "sqrt edge " + Integer.toHexString(Float.floatToRawIntBits(value)));
            check(Float.floatToRawIntBits(mth.inverseSqrt(value))
                            == Float.floatToRawIntBits(1.0F / (float) Math.sqrt(value)),
                    "float inverse sqrt edge " + Integer.toHexString(Float.floatToRawIntBits(value)));
        }
        double[] doubleEdges = {
                0.0, -0.0, Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE,
                -1.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.longBitsToDouble(0x7ff8123456789abCL),
                Double.longBitsToDouble(0xfff8123456789abCL)
        };
        for (double value : doubleEdges) {
            check(Double.doubleToRawLongBits(mth.inverseSqrt(value))
                            == Double.doubleToRawLongBits(1.0D / Math.sqrt(value)),
                    "double inverse sqrt edge " + Long.toHexString(Double.doubleToRawLongBits(value)));
        }
        check(mth.sign(0.0) == 0 && mth.sign(-0.0) == 0, "sign zero");
        check(mth.sign(1.0) == 1 && mth.sign(-1.0) == -1, "sign finite");
        check(mth.sign(Double.NaN) == -1, "sign NaN");

        check(mth.hypot(3.0, 4.0) == 5.0, "double length");
        check(mth.hypot(3.0F, 4.0F) == 5.0F, "float length");
        // 26.3 squares in float: 1e20F^2 overflows to infinity (26.2 squared in double).
        check(mth.hypot(1.0E20F, 0.0F) == Float.POSITIVE_INFINITY, "float length squares in float");
        check(mth.magnitude(2.0, 3.0, 6.0) == 7.0, "3D length");
        check(mth.magnitude(2.0F, 3.0F, 6.0F) == 49.0F, "float lengthSquared");
        check(mth.roundUpToMultiple(7, 4) == 8, "round toward");
        check(mth.ceilDiv(7, 4) == 2, "positive ceil div");
        check(mth.roundDownToMultiple(-1.0, 4) == -4, "quantize");
        check(mth.multiplyFraction(3, 2, 5) == 7, "fraction truncation");
        expectArithmetic(() -> mth.roundUpToMultiple(1, 0), "roundToward by zero");
        expectArithmetic(() -> mth.ceilDiv(1, 0), "positiveCeilDiv by zero");
        expectArithmetic(() -> mth.multiplyFraction(1, 0, 1), "mulAndTruncate by zero");
    }

    private static void remainingInlineMathMatchesVanilla(MthImpl mth) {
        check(mth.smallestEncompassingPowerOfTwo(0) == 0, "smallest power zero");
        check(mth.smallestEncompassingPowerOfTwo(3) == 4, "smallest power three");
        check(mth.smallestEncompassingPowerOfTwo((1 << 30) + 1) == Integer.MIN_VALUE,
                "smallest power overflow");
        check(mth.isPowerOfTwo(Integer.MIN_VALUE), "signed high bit is vanilla power");
        check(mth.ceilLog2(0) == 0 && mth.floorLog2(0) == -1, "log zero");
        check(mth.ceilLog2(3) == 2 && mth.floorLog2(3) == 1, "log three");
        check(mth.smallestEncompassingSquareSideLength(0) == 0, "square side zero");
        check(mth.smallestEncompassingSquareSideLength(10) == 4, "square side ten");
        expectIllegalArgument(() -> mth.smallestEncompassingSquareSideLength(-1),
                "negative square side");

        float[] cubeEdges = {
                0.0F, -0.0F, 1.0F, -1.0F, Float.MIN_VALUE, Float.MAX_VALUE,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.intBitsToFloat(0x7fa12345), Float.intBitsToFloat(0xffa12345)
        };
        for (float x : cubeEdges) {
            int bits = Float.floatToIntBits(x);
            bits = 1419967116 - bits / 3;
            float want = Float.intBitsToFloat(bits);
            want = 0.6666667F * want + 1.0F / (3.0F * want * want * x);
            want = 0.6666667F * want + 1.0F / (3.0F * want * want * x);
            check(Float.floatToRawIntBits(mth.fastInverseCbrt(x))
                            == Float.floatToRawIntBits(want),
                    "fast inverse cube root " + Integer.toHexString(Float.floatToRawIntBits(x)));
        }

        check(mth.lerp(0.5F, 10, 15) == 12, "integer lerp");
        check(mth.lerpPositive(0.5F, 10, 15) == 13, "discrete lerp");
        check(Double.doubleToRawLongBits(mth.lerp2(0.25, 0.75, 1.0, 2.0, 3.0, 4.0))
                        == Double.doubleToRawLongBits(
                        mth.lerp(0.75, mth.lerp(0.25, 1.0, 2.0), mth.lerp(0.25, 3.0, 4.0))),
                "bilinear interpolation");
        // 0.7F: float evaluation differs from a double-evaluated-then-narrowed result.
        check(Float.floatToRawIntBits(mth.perlinFade(0.7F))
                        == Float.floatToRawIntBits(
                        0.7F * 0.7F * 0.7F * (0.7F * (0.7F * 6.0F - 15.0F) + 10.0F)),
                "smoothstep float operation order");
        check(Float.floatToRawIntBits(mth.perlinFadeDerivative(0.7F))
                        == Float.floatToRawIntBits(30.0F * 0.7F * 0.7F * (0.7F - 1.0F) * (0.7F - 1.0F)),
                "smoothstep derivative float operation order");
        check(Float.floatToRawIntBits(mth.lerpAngleDegrees(0.25F, 170.0F, -170.0F))
                        == Float.floatToRawIntBits(175.0F),
                "degree interpolation");
        check(Float.floatToRawIntBits(mth.wrap(0.0F, 4.0F))
                        == Float.floatToRawIntBits(1.0F),
                "triangle wave");
    }

    private static void expectIllegalArgument(Runnable call, String message) {
        try {
            call.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void expectArithmetic(Runnable call, String message) {
        try {
            call.run();
        } catch (ArithmeticException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void expectRuntime(Runnable call, String message) {
        try {
            call.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
