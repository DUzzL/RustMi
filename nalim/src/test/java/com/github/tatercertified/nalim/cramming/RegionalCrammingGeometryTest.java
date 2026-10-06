package com.github.tatercertified.nalim.cramming;

/** Deterministic coverage proofs for regional cramming's XZ prefilter. */
public final class RegionalCrammingGeometryTest {
    private RegionalCrammingGeometryTest() {}

    public static void main(String[] args) {
        constantsCoverGhastPair();
        boundaryInclusion();
        oversizedFallbackAndColliderExpansion();
        sensorCoverageUsesInflatedBox();
        remoteWideColliderEnvelope();
    }

    private static void constantsCoverGhastPair() {
        double twoGhastReach = 2.0 + 2.0 + RegionalCrammingGeometry.SNAPSHOT_PAD;
        check(RegionalCrammingGeometry.HALO - RegionalCrammingGeometry.ORIGIN_MARGIN
                        >= twoGhastReach,
                "halo must cover a padded ghast-ghast pair");

        double origin = -RegionalCrammingGeometry.ORIGIN_MARGIN;
        double rejectedCollider = -RegionalCrammingGeometry.HALO - 0.4;
        check(!RegionalCrammingGeometry.colliderWithinHalo(
                        rejectedCollider, 0.0, 2.0, 2.0, -16.0, 144.0, -16.0, 144.0),
                "ordinary collider beyond the low halo is rejected");
        check(Math.abs(origin - rejectedCollider) > twoGhastReach,
                "a halo-rejected width-4 collider cannot pair with the farthest origin");
    }

    private static void boundaryInclusion() {
        double span = 128.0;
        check(RegionalCrammingGeometry.colliderWithinHalo(
                        -16.0, 64.0, 2.0, 2.0, -16.0, span + 16.0, -16.0, span + 16.0),
                "low halo boundary is inclusive");
        check(!RegionalCrammingGeometry.colliderWithinHalo(
                        span + 16.0, 64.0, 2.0, 2.0, -16.0, span + 16.0, -16.0, span + 16.0),
                "high halo boundary is exclusive");
        check(RegionalCrammingGeometry.originWithinCoveredArea(
                        -11.0, 64.0, 2.0, 2.0, -11.0, span + 11.0, -11.0, span + 11.0),
                "low origin boundary is inclusive");
        check(!RegionalCrammingGeometry.originWithinCoveredArea(
                        span + 11.0, 64.0, 2.0, 2.0, -11.0, span + 11.0, -11.0, span + 11.0),
                "high origin boundary is exclusive");
    }

    private static void oversizedFallbackAndColliderExpansion() {
        double span = 128.0;
        check(!RegionalCrammingGeometry.originWithinCoveredArea(
                        64.0, 64.0, 8.0, 8.0, -11.0, span + 11.0, -11.0, span + 11.0),
                "dragon-sized origin must use vanilla query");
        check(RegionalCrammingGeometry.colliderWithinHalo(
                        -21.9, 64.0, 8.0, 2.0, -16.0, span + 16.0, -16.0, span + 16.0),
                "wide collider expands the low halo");
        check(RegionalCrammingGeometry.colliderWithinHalo(
                        -22.0, 64.0, 8.0, 2.0, -16.0, span + 16.0, -16.0, span + 16.0),
                "expanded low halo boundary is inclusive");
        check(!RegionalCrammingGeometry.colliderWithinHalo(
                        span + 22.0, 64.0, 8.0, 2.0,
                        -16.0, span + 16.0, -16.0, span + 16.0),
                "expanded high halo boundary is exclusive");
    }

    private static void sensorCoverageUsesInflatedBox() {
        double coveredLo = -16.0;
        double coveredHi = 144.0;
        double bodyMin = 127.7;
        double bodyMax = 128.3;
        double exactRange = coveredHi
                - RegionalCrammingGeometry.SENSOR_COVERAGE_MARGIN - bodyMax;
        double inflatedMin = bodyMin - exactRange;
        double inflatedMax = bodyMax + exactRange;
        check(RegionalCrammingGeometry.sensorBoxCovered(
                        inflatedMin, inflatedMax,
                        64.0 - exactRange, 64.0 + exactRange,
                        coveredLo, coveredHi, coveredLo, coveredHi),
                "sensor box exactly at the safe high boundary is covered");
        check(!RegionalCrammingGeometry.sensorBoxCovered(
                        inflatedMin, Math.nextUp(inflatedMax),
                        64.0 - exactRange, 64.0 + exactRange,
                        coveredLo, coveredHi, coveredLo, coveredHi),
                "body half-width must participate in the sensor coverage gate");
        check(!RegionalCrammingGeometry.sensorBoxCovered(
                        Double.NaN, bodyMax, 0.0, 1.0,
                        coveredLo, coveredHi, coveredLo, coveredHi),
                "non-finite sensor boxes must fall back");
    }

    private static void remoteWideColliderEnvelope() {
        double originCenter = -11.0;
        double originHalf = RegionalCrammingGeometry.MAX_FAST_HALF_EXTENT;
        double envelopeMin = originCenter - originHalf
                - RegionalCrammingGeometry.SNAPSHOT_PAD;
        for (double candidateHalf : new double[]{0.3, 2.0, 4.0, 8.0, 32.0, 528.32}) {
            double candidateCenter = envelopeMin - candidateHalf + 0.01;
            double candidateMax = candidateCenter + candidateHalf;
            check(candidateMax > envelopeMin,
                    "actual-AABB query must see collider half=" + candidateHalf);
            check(Math.abs(originCenter - candidateCenter)
                            < originHalf + candidateHalf + RegionalCrammingGeometry.SNAPSHOT_PAD,
                    "padded kernel reach must match query envelope half=" + candidateHalf);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
