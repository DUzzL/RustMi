package com.github.tatercertified.nalim.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.Arrays;
import java.util.Random;

/**
 * Compares the allocation-free FULL-cube exposure ray against Minecraft's
 * BlockGetter.traverseBlocks + VoxelShape.clip implementation.
 */
public final class ExplosionSeenClipTest {
    private static final int ORIGIN = -5;
    private static final int SIZE = 11;

    private ExplosionSeenClipTest() {
    }

    public static void main(String[] args) {
        explicitClipBoundaries();
        randomizedParity();
        randomizedSeenParity();
    }

    private static void explicitClipBoundaries() {
        ExplosionEngine.Ctx c = context();
        setFull(c, 0, 0, 0);

        assertRay(c, "origin inside full cube", true,
                0.5, 0.5, 0.5, 3.5, 0.5, 0.5);
        assertRay(c, "cross full cube", true,
                -2.0, 0.5, 0.5, 2.0, 0.5, 0.5);
        assertRay(c, "stationary inside full cube", false,
                0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
        assertRay(c, "near-degenerate inside full cube", false,
                0.5, 0.5, 0.5, 0.5001, 0.5, 0.5);
        assertRay(c, "minimum face moving outward", false,
                0.0, 0.5, 0.5, -2.0, 0.5, 0.5);
        assertRay(c, "minimum face moving inward", true,
                0.0, 0.5, 0.5, 2.0, 0.5, 0.5);
        assertRay(c, "minimum face ending inside cube", true,
                0.0, 0.5, 0.5, 0.5, 0.5, 0.5);
        assertRay(c, "inside near maximum face", true,
                0.9999, 0.5, 0.5, 0.5, 0.5, 0.5);
        assertParity(c, "corner graze within transverse epsilon",
                0.5, 2.0, 5.0E-8, 0.5, -1.0, 3.00000005);
        assertParity(c, "corner graze outside transverse epsilon",
                0.5, 2.0, 5.0E-7, 0.5, -1.0, 3.0000005);
        assertParity(c, "inside endpoint within transverse epsilon",
                0.5, 2.0, 2.0000001, 0.5, 0.5, 0.5);
        assertParity(c, "inside endpoint outside transverse epsilon",
                0.5, 2.0, 2.000001, 0.5, 0.5, 0.5);
        assertRay(c, "parallel ray above cube is not enumerated", false,
                -2.0, 1.0 + 5.0E-8, 0.5, 2.0, 1.0 + 5.0E-8, 0.5);
        assertRay(c, "parallel ray farther above cube is not enumerated", false,
                -2.0, 1.0 + 2.0E-7, 0.5, 2.0, 1.0 + 2.0E-7, 0.5);

        Arrays.fill(c.full, 0L);
        assertRay(c, "empty snapshot", false,
                -2.0, 0.5, 0.5, 2.0, 0.5, 0.5);
    }

    private static void randomizedParity() {
        ExplosionEngine.Ctx c = context();
        Random random = new Random(0x5EE17C11A5L);
        for (int i = 0; i < 50_000; i++) {
            if ((i & 63) == 0) {
                randomizeSnapshot(c, random);
            }

            double sx = coordinate(random);
            double sy = coordinate(random);
            double sz = coordinate(random);
            double ex = coordinate(random);
            double ey = coordinate(random);
            double ez = coordinate(random);
            switch (i % 6) {
                case 0 -> ex = sx;
                case 1 -> {
                    ex = sx + (random.nextDouble() - 0.5) * 2.0E-4;
                    ey = sy + (random.nextDouble() - 0.5) * 2.0E-4;
                    ez = sz + (random.nextDouble() - 0.5) * 2.0E-4;
                }
                case 2 -> sx = Math.rint(sx) + (random.nextBoolean() ? 1.0E-7 : -1.0E-7);
                case 3 -> sy = Math.rint(sy);
                case 4 -> {
                    int bx = random.nextInt(7) - 3;
                    int by = random.nextInt(7) - 3;
                    int bz = random.nextInt(7) - 3;
                    setFull(c, bx, by, bz);
                    sx = bx + 0.1 + random.nextDouble() * 0.8;
                    sy = by + 0.1 + random.nextDouble() * 0.8;
                    sz = bz + 0.1 + random.nextDouble() * 0.8;
                }
                default -> ez = Math.rint(ez);
            }
            assertParity(c, "random ray " + i, sx, sy, sz, ex, ey, ez);
        }
    }

    private static void randomizedSeenParity() {
        ExplosionEngine.Ctx c = context();
        Random random = new Random(0x5EE1AABB26L);
        for (int i = 0; i < 2_000; i++) {
            if ((i & 15) == 0) {
                randomizeSnapshot(c, random);
            }
            double width = 0.05 + random.nextDouble() * 2.0;
            double height = 0.05 + random.nextDouble() * 2.5;
            double depth = 0.05 + random.nextDouble() * 2.0;
            double x = -3.0 + random.nextDouble() * 6.0;
            double y = -3.5 + random.nextDouble() * 5.0;
            double z = -3.0 + random.nextDouble() * 6.0;
            AABB box = new AABB(
                    x - width / 2.0, y, z - depth / 2.0,
                    x + width / 2.0, y + height, z + depth / 2.0);
            Vec3 center = new Vec3(
                    -3.5 + random.nextDouble() * 7.0,
                    -3.5 + random.nextDouble() * 7.0,
                    -3.5 + random.nextDouble() * 7.0);
            assertSeenParity(c, "random seen box " + i, center, box);
        }
    }

    private static ExplosionEngine.Ctx context() {
        ExplosionEngine.Ctx c = new ExplosionEngine.Ctx();
        c.ox = ORIGIN;
        c.oy = ORIGIN;
        c.oz = ORIGIN;
        c.dx = SIZE;
        c.dy = SIZE;
        c.dz = SIZE;
        c.full = new long[(SIZE * SIZE * SIZE + 63) >>> 6];
        return c;
    }

    private static void randomizeSnapshot(ExplosionEngine.Ctx c, Random random) {
        Arrays.fill(c.full, 0L);
        for (int y = ORIGIN; y < ORIGIN + SIZE; y++) {
            for (int z = ORIGIN; z < ORIGIN + SIZE; z++) {
                for (int x = ORIGIN; x < ORIGIN + SIZE; x++) {
                    if (random.nextDouble() < 0.18) {
                        setFull(c, x, y, z);
                    }
                }
            }
        }
    }

    private static double coordinate(Random random) {
        return -4.5 + random.nextDouble() * 9.0;
    }

    private static void assertRay(ExplosionEngine.Ctx c, String label, boolean expected,
                                  double sx, double sy, double sz,
                                  double ex, double ey, double ez) {
        boolean vanilla = vanillaHits(c, sx, sy, sz, ex, ey, ez);
        if (vanilla != expected) {
            throw new AssertionError(label + ": baseline was " + vanilla + ", expected " + expected);
        }
        assertParity(c, label, sx, sy, sz, ex, ey, ez);
    }

    private static void assertParity(ExplosionEngine.Ctx c, String label,
                                     double sx, double sy, double sz,
                                     double ex, double ey, double ez) {
        boolean fast = ExplosionEngine.ddaHits(c, sx, sy, sz, ex, ey, ez);
        boolean vanilla = vanillaHits(c, sx, sy, sz, ex, ey, ez);
        if (fast != vanilla) {
            throw new AssertionError(label + ": fast=" + fast + ", vanilla=" + vanilla
                    + ", from=(" + sx + ',' + sy + ',' + sz + ")"
                    + ", to=(" + ex + ',' + ey + ',' + ez + ')');
        }
    }

    private static void assertSeenParity(
            ExplosionEngine.Ctx c, String label, Vec3 center, AABB box) {
        float fast = ExplosionEngine.computeSeen(c, center, box);
        float vanilla = vanillaSeen(c, center, box);
        if (Float.floatToRawIntBits(fast) != Float.floatToRawIntBits(vanilla)) {
            throw new AssertionError(label + ": fast=" + fast + ", vanilla=" + vanilla
                    + ", center=" + center + ", box=" + box);
        }
    }

    private static float vanillaSeen(ExplosionEngine.Ctx c, Vec3 center, AABB box) {
        double xs = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double ys = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double zs = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) {
            return 0.0F;
        }
        int visible = 0;
        int count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double x = box.minX + (box.maxX - box.minX) * xx;
                    double y = box.minY + (box.maxY - box.minY) * yy;
                    double z = box.minZ + (box.maxZ - box.minZ) * zz;
                    if (!vanillaHits(c, x + xOffset, y, z + zOffset,
                            center.x, center.y, center.z)) {
                        visible++;
                    }
                    count++;
                }
            }
        }
        return (float) visible / count;
    }

    private static boolean vanillaHits(ExplosionEngine.Ctx c,
                                       double sx, double sy, double sz,
                                       double ex, double ey, double ez) {
        Vec3 from = new Vec3(sx, sy, sz);
        Vec3 to = new Vec3(ex, ey, ez);
        return BlockGetter.traverseBlocks(from, to, c, (ctx, pos) -> {
            if (!isFull(ctx, pos)) {
                return null;
            }
            return Shapes.block().clip(from, to, pos) == null ? null : Boolean.TRUE;
        }, ignored -> Boolean.FALSE);
    }

    private static boolean isFull(ExplosionEngine.Ctx c, BlockPos pos) {
        int lx = pos.getX() - c.ox;
        int ly = pos.getY() - c.oy;
        int lz = pos.getZ() - c.oz;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= c.dx || ly >= c.dy || lz >= c.dz) {
            return false;
        }
        int index = (ly * c.dz + lz) * c.dx + lx;
        return (c.full[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    private static void setFull(ExplosionEngine.Ctx c, int x, int y, int z) {
        int lx = x - c.ox;
        int ly = y - c.oy;
        int lz = z - c.oz;
        int index = (ly * c.dz + lz) * c.dx + lx;
        c.full[index >>> 6] |= 1L << (index & 63);
    }
}
