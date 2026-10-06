package com.github.tatercertified.nalim.cramming;

/** Pure geometry behind the regional batch's conservative coverage gates. */
final class RegionalCrammingGeometry {
    static final double HALO = 16.0;
    static final double ORIGIN_MARGIN = 11.0;
    static final double MAX_FAST_HALF_EXTENT = 2.0;
    static final double SNAPSHOT_PAD = 0.5;
    static final double SENSOR_PAD = 2.0;
    static final double SENSOR_COVERAGE_MARGIN = MAX_FAST_HALF_EXTENT + SENSOR_PAD;

    private RegionalCrammingGeometry() {}

    static boolean colliderWithinHalo(double centerX, double centerZ,
                                      double halfX, double halfZ,
                                      double loX, double hiX, double loZ, double hiZ) {
        double extraX = halfX > MAX_FAST_HALF_EXTENT ? halfX - MAX_FAST_HALF_EXTENT : 0.0;
        double extraZ = halfZ > MAX_FAST_HALF_EXTENT ? halfZ - MAX_FAST_HALF_EXTENT : 0.0;
        return centerX >= loX - extraX && centerX < hiX + extraX
                && centerZ >= loZ - extraZ && centerZ < hiZ + extraZ;
    }

    static boolean originWithinCoveredArea(double centerX, double centerZ,
                                           double halfX, double halfZ,
                                           double loX, double hiX, double loZ, double hiZ) {
        return halfX <= MAX_FAST_HALF_EXTENT && halfZ <= MAX_FAST_HALF_EXTENT
                && centerX >= loX && centerX < hiX
                && centerZ >= loZ && centerZ < hiZ;
    }

    static boolean sensorBoxCovered(double minX, double maxX, double minZ, double maxZ,
                                    double coveredLoX, double coveredHiX,
                                    double coveredLoZ, double coveredHiZ) {
        return minX - SENSOR_COVERAGE_MARGIN >= coveredLoX
                && maxX + SENSOR_COVERAGE_MARGIN <= coveredHiX
                && minZ - SENSOR_COVERAGE_MARGIN >= coveredLoZ
                && maxZ + SENSOR_COVERAGE_MARGIN <= coveredHiZ;
    }
}
