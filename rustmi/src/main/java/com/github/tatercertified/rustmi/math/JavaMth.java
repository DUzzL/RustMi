package com.github.tatercertified.rustmi.math;

/**
 * Vanilla-exact Java bodies for every {@link Mth} operation (all except packRgb, which each
 * backend supplies). One shared copy keeps every backend bit-identical to vanilla —
 * Rust integer division by zero would abort the JVM where vanilla throws
 * {@link ArithmeticException}, and an FFM downcall costs more than these bodies.
 */
public abstract class JavaMth implements Mth {
    /** Minecraft's exact De Bruijn lookup order for ceillog2. */
    private static final int[] MULTIPLY_DE_BRUIJN_BIT_POSITION = {
            0, 1, 28, 2, 29, 14, 24, 3,
            30, 22, 20, 15, 25, 17, 4, 8,
            31, 27, 13, 23, 21, 19, 16, 7,
            26, 12, 18, 6, 11, 5, 10, 9
    };

    @Override
    public float sqrt(float value) {
        return (float) Math.sqrt(value);
    }

    @Override
    public int floor(float value) {
        return (int) Math.floor(value);
    }

    @Override
    public int floor(double value) {
        return (int) Math.floor(value);
    }

    @Override
    public long lfloor(double value) {
        return (long) Math.floor(value);
    }

    @Override
    public float abs(float value) {
        return Math.abs(value);
    }

    @Override
    public int abs(int value) {
        return Math.abs(value);
    }

    @Override
    public int ceil(float value) {
        return (int) Math.ceil(value);
    }

    @Override
    public int ceil(double value) {
        return (int) Math.ceil(value);
    }

    @Override
    public int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    @Override
    public long clamp(long value, long min, long max) {
        return Math.min(Math.max(value, min), max);
    }

    @Override
    public float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    @Override
    public double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    @Override
    public double clampedLerp(double delta, double start, double end) {
        return delta < 0.0 ? start : (delta > 1.0 ? end : start + delta * (end - start));
    }

    @Override
    public float clampedLerp(float delta, float start, float end) {
        return delta < 0.0F ? start : (delta > 1.0F ? end : start + delta * (end - start));
    }

    @Override
    public int method_76800(int i, int j) {
        return Math.max(Math.abs(i), Math.abs(j));
    }

    @Override
    public float method_76799(float f, float g) {
        return Math.max(Math.abs(f), Math.abs(g));
    }

    @Override
    public int method_76801(int i, int j, int k, int l) {
        return Math.max(Math.abs(k - i), Math.abs(l - j));
    }

    @Override
    public double absMax(double a, double b) {
        return Math.max(Math.abs(a), Math.abs(b));
    }

    @Override
    public int floorDiv(int dividend, int divisor) {
        return Math.floorDiv(dividend, divisor);
    }

    @Override
    public boolean approximatelyEquals(float a, float b) {
        return Math.abs(b - a) < 1.0E-5F;
    }

    @Override
    public boolean approximatelyEquals(double a, double b) {
        return Math.abs(b - a) < 1.0E-5F;
    }

    @Override
    public int floorMod(int dividend, int divisor) {
        return Math.floorMod(dividend, divisor);
    }

    @Override
    public float floorMod(float dividend, float divisor) {
        return (dividend % divisor + divisor) % divisor;
    }

    @Override
    public double floorMod(double dividend, double divisor) {
        return (dividend % divisor + divisor) % divisor;
    }

    @Override
    public boolean isMultipleOf(int a, int b) {
        return a % b == 0;
    }

    @Override
    public byte packDegrees(float degrees) {
        return (byte) ((int) Math.floor(degrees * 256.0F / 360.0F));
    }

    @Override
    public float unpackDegrees(byte packedDegrees) {
        return packedDegrees * 360 / 256.0F;
    }

    @Override
    public int wrapDegrees(int degrees) {
        int wrappedDegrees = degrees % 360;
        if (wrappedDegrees >= 180) {
            wrappedDegrees -= 360;
        }
        if (wrappedDegrees < -180) {
            wrappedDegrees += 360;
        }
        return wrappedDegrees;
    }

    @Override
    public float wrapDegrees(long degrees) {
        float wrappedDegrees = (float) (degrees % 360L);
        if (wrappedDegrees >= 180.0F) {
            wrappedDegrees -= 360.0F;
        }
        if (wrappedDegrees < -180.0F) {
            wrappedDegrees += 360.0F;
        }
        return wrappedDegrees;
    }

    @Override
    public float wrapDegrees(float degrees) {
        float wrappedDegrees = degrees % 360.0F;
        if (wrappedDegrees >= 180.0F) {
            wrappedDegrees -= 360.0F;
        }
        if (wrappedDegrees < -180.0F) {
            wrappedDegrees += 360.0F;
        }
        return wrappedDegrees;
    }

    @Override
    public double wrapDegrees(double degrees) {
        double wrappedDegrees = degrees % 360.0;
        if (wrappedDegrees >= 180.0) {
            wrappedDegrees -= 360.0;
        }
        if (wrappedDegrees < -180.0) {
            wrappedDegrees += 360.0;
        }
        return wrappedDegrees;
    }

    @Override
    public float subtractAngles(float start, float end) {
        return wrapDegrees(end - start);
    }

    @Override
    public float angleBetween(float first, float second) {
        return Math.abs(subtractAngles(first, second));
    }

    @Override
    public float clampAngle(float value, float mean, float delta) {
        float deltaAngle = subtractAngles(value, mean);
        float deltaAngleClamped = clamp(deltaAngle, -delta, delta);
        return mean - deltaAngleClamped;
    }

    @Override
    public float stepTowards(float from, float to, float step) {
        step = Math.abs(step);
        return from < to ? clamp(from + step, from, to) : clamp(from - step, to, from);
    }

    @Override
    public float stepUnwrappedAngleTowards(float from, float to, float step) {
        float difference = subtractAngles(from, to);
        return stepTowards(from, from + difference, step);
    }

    @Override
    public int parseInt(String string, int fallback) {
        try {
            return Integer.parseInt(string);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    public int smallestEncompassingPowerOfTwo(int value) {
        int i = value - 1;
        i |= i >> 1;
        i |= i >> 2;
        i |= i >> 4;
        i |= i >> 8;
        i |= i >> 16;
        return i + 1;
    }

    @Override
    public int smallestEncompassingSquareSideLength(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("itemCount must be greater than or equal to zero");
        }
        return (int) Math.ceil(Math.sqrt(value));
    }

    @Override
    public boolean isPowerOfTwo(int value) {
        return value != 0 && (value & value - 1) == 0;
    }

    @Override
    public int ceilLog2(int value) {
        value = isPowerOfTwo(value) ? value : smallestEncompassingPowerOfTwo(value);
        return MULTIPLY_DE_BRUIJN_BIT_POSITION[
                (int) ((long) value * 125613361L >> 27) & 31];
    }

    @Override
    public int floorLog2(int value) {
        return ceilLog2(value) - (isPowerOfTwo(value) ? 0 : 1);
    }

    @Override
    public long ceilLong(double value) {
        return (long) Math.ceil(value);
    }

    @Override
    public float fractionalPart(float value) {
        return value - (int) Math.floor(value);
    }

    @Override
    public double fractionalPart(double value) {
        return value - (long) Math.floor(value);
    }

    @Override
    public long hashCode(int x, int y, int z) {
        long value = (long) (x * 3129871) ^ (long) z * 116129781L ^ y;
        value = value * value * 42317861L + value * 11L;
        return value >> 16;
    }

    @Override
    public double getLerpProgress(double value, double start, double end) {
        return (value - start) / (end - start);
    }

    @Override
    public float getLerpProgress(float value, float start, float end) {
        return (value - start) / (end - start);
    }

    @Override
    public double atan2(double y, double x) {
        return VanillaAtan2.atan2(y, x);
    }

    @Override
    public float inverseSqrt(float x) {
        return 1.0F / (float) Math.sqrt(x);
    }

    @Override
    public double inverseSqrt(double x) {
        return 1.0D / Math.sqrt(x);
    }

    @Override
    public double fastInverseSqrt(double x) {
        double half = 0.5D * x;
        long bits = Double.doubleToRawLongBits(x);
        bits = 6910469410427058090L - (bits >> 1);
        x = Double.longBitsToDouble(bits);
        x *= 1.5D - half * x * x;
        return x;
    }

    @Override
    public float fastInverseSqrt(float x) {
        float half = 0.5F * x;
        int bits = Float.floatToIntBits(x);
        bits = 1597463007 - (bits >> 1);
        x = Float.intBitsToFloat(bits);
        x *= 1.5F - half * x * x;
        return x;
    }

    @Override
    public float fastInverseCbrt(float x) {
        int bits = Float.floatToIntBits(x);
        bits = 1419967116 - bits / 3;
        float value = Float.intBitsToFloat(bits);
        value = 0.6666667F * value + 1.0F / (3.0F * value * value * x);
        value = 0.6666667F * value + 1.0F / (3.0F * value * value * x);
        return value;
    }

    @Override
    public int hsvToRgb(float hue, float saturation, float value) {
        return hsvToArgb(hue, saturation, value, 0);
    }

    @Override
    public int hsvToArgb(float hue, float saturation, float value, int alpha) {
        int sector = (int) (hue * 6.0F) % 6;
        float fraction = hue * 6.0F - sector;
        float low = value * (1.0F - saturation);
        float falling = value * (1.0F - fraction * saturation);
        float rising = value * (1.0F - (1.0F - fraction) * saturation);
        float red;
        float green;
        float blue;
        switch (sector) {
            case 0 -> {
                red = value;
                green = rising;
                blue = low;
            }
            case 1 -> {
                red = falling;
                green = value;
                blue = low;
            }
            case 2 -> {
                red = low;
                green = value;
                blue = rising;
            }
            case 3 -> {
                red = low;
                green = falling;
                blue = value;
            }
            case 4 -> {
                red = rising;
                green = low;
                blue = value;
            }
            case 5 -> {
                red = value;
                green = low;
                blue = falling;
            }
            default -> throw new RuntimeException(
                    "Something went wrong when converting from HSV to RGB. Input was "
                            + hue + ", " + saturation + ", " + value);
        }
        int r = clamp((int) (red * 255.0F), 0, 255);
        int g = clamp((int) (green * 255.0F), 0, 255);
        int b = clamp((int) (blue * 255.0F), 0, 255);
        return alpha << 24 | r << 16 | g << 8 | b;
    }

    @Override
    public int idealHash(int value) {
        value ^= value >>> 16;
        value *= -2048144789;
        value ^= value >>> 13;
        value *= -1028477387;
        return value ^ value >>> 16;
    }

    @Override
    public int lerp(float delta, int start, int end) {
        return start + (int) Math.floor(delta * (end - start));
    }

    @Override
    public int lerpPositive(float delta, int start, int end) {
        int distance = end - start;
        return start + (int) Math.floor(delta * (distance - 1)) + (delta > 0.0F ? 1 : 0);
    }

    // Keep vanilla's exact operation order on the JIT-inlineable side.
    @Override
    public float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    @Override
    public double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    @Override
    public double lerp2(double deltaX, double deltaY,
                        double x0y0, double x1y0, double x0y1, double x1y1) {
        return lerp(deltaY, lerp(deltaX, x0y0, x1y0), lerp(deltaX, x0y1, x1y1));
    }

    @Override
    public double lerp3(double deltaX, double deltaY, double deltaZ,
                        double x0y0z0, double x1y0z0, double x0y1z0, double x1y1z0,
                        double x0y0z1, double x1y0z1, double x0y1z1, double x1y1z1) {
        return lerp(deltaZ,
                lerp2(deltaX, deltaY, x0y0z0, x1y0z0, x0y1z0, x1y1z0),
                lerp2(deltaX, deltaY, x0y0z1, x1y0z1, x0y1z1, x1y1z1));
    }

    @Override
    public float catmullRom(float delta, float p0, float p1, float p2, float p3) {
        return 0.5F * (2.0F * p1
                + (p2 - p0) * delta
                + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * delta * delta
                + (3.0F * p1 - p0 - 3.0F * p2 + p3) * delta * delta * delta);
    }

    @Override
    public float perlinFade(float value) {
        return value * value * value * (value * (value * 6.0F - 15.0F) + 10.0F);
    }

    @Override
    public float perlinFadeDerivative(float value) {
        return 30.0F * value * value * (value - 1.0F) * (value - 1.0F);
    }

    @Override
    public int sign(double value) {
        if (value == 0.0D) {
            return 0;
        }
        return value > 0.0D ? 1 : -1;
    }

    @Override
    public float lerpAngleDegrees(float delta, float start, float end) {
        return start + delta * wrapDegrees(end - start);
    }

    @Override
    public double lerpAngleDegrees(double delta, double start, double end) {
        return start + delta * wrapDegrees(end - start);
    }

    @Override
    public float lerpAngleRadians(float delta, float start, float end) {
        float difference = end - start;
        while (difference < -3.1415927F) {
            difference += 6.2831855F;
        }
        while (difference >= 3.1415927F) {
            difference -= 6.2831855F;
        }
        return start + delta * difference;
    }

    @Override
    public float wrap(float value, float maxDeviation) {
        return (Math.abs(value % maxDeviation - maxDeviation * 0.5F)
                - maxDeviation * 0.25F) / (maxDeviation * 0.25F);
    }

    // A native boundary costs more than these single operations and prevents
    // callers from inlining them into hot Minecraft loops.
    @Override
    public float square(float n) {
        return n * n;
    }

    @Override
    public double square(double n) {
        return n * n;
    }

    @Override
    public int square(int n) {
        return n * n;
    }

    @Override
    public long square(long n) {
        return n * n;
    }

    @Override
    public double clampedMap(double value, double oldStart, double oldEnd, double newStart, double newEnd) {
        return clampedLerp(getLerpProgress(value, oldStart, oldEnd), newStart, newEnd);
    }

    @Override
    public float clampedMap(float value, float oldStart, float oldEnd, float newStart, float newEnd) {
        return clampedLerp(getLerpProgress(value, oldStart, oldEnd), newStart, newEnd);
    }

    @Override
    public double map(double value, double oldStart, double oldEnd, double newStart, double newEnd) {
        return lerp(getLerpProgress(value, oldStart, oldEnd), newStart, newEnd);
    }

    @Override
    public float map(float value, float oldStart, float oldEnd, float newStart, float newEnd) {
        return lerp(getLerpProgress(value, oldStart, oldEnd), newStart, newEnd);
    }

    @Override
    public int roundUpToMultiple(int value, int divisor) {
        return -Math.floorDiv(-value, divisor) * divisor;
    }

    @Override
    public int ceilDiv(int a, int b) {
        return -Math.floorDiv(-a, b);
    }

    @Override
    public double squaredHypot(double a, double b) {
        return a * a + b * b;
    }

    @Override
    public double hypot(double a, double b) {
        return Math.sqrt(a * a + b * b);
    }

    // 26.3: lengthSquared(FF)F squares in float; only the root is taken in double.
    @Override
    public float hypot(float a, float b) {
        return (float) Math.sqrt(a * a + b * b);
    }

    @Override
    public double squaredMagnitude(double a, double b, double c) {
        return a * a + b * b + c * c;
    }

    @Override
    public double magnitude(double a, double b, double c) {
        return Math.sqrt(a * a + b * b + c * c);
    }

    @Override
    public float magnitude(float a, float b, float c) {
        return a * a + b * b + c * c;
    }

    @Override
    public double magnitude(int a, double b, int c) {
        return Math.sqrt((double) (a * a) + b * b + (double) (c * c));
    }

    @Override
    public int roundDownToMultiple(double a, int b) {
        return (int) Math.floor(a / b) * b;
    }

    @Override
    public int multiplyFraction(int numerator, int denominator, int multiplier) {
        return numerator * multiplier / denominator;
    }
}
