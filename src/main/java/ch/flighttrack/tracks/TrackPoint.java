package ch.flighttrack.tracks;

public record TrackPoint(
        long timeNanos,
        double secondsElapsed,
        double latitude,
        double longitude,
        double gpsAltitudeMeters,
        double barometricAltitudeMeters,
        double speedMetersPerSecond,
        double xMeters,
        double yMeters,
        double zMeters,
        boolean moving,
        double headingRadians,
        double pitchRadians,
        double rollRadians,
        double accelerationForwardMetersPerSecondSquared,
        double accelerationRightMetersPerSecondSquared,
        double accelerationUpMetersPerSecondSquared,
        double barometricClimbRateMetersPerSecond,
        double barometricVerticalAccelerationMetersPerSecondSquared
) {
    public TrackPoint(
            long timeNanos,
            double secondsElapsed,
            double latitude,
            double longitude,
            double gpsAltitudeMeters,
            double barometricAltitudeMeters,
            double speedMetersPerSecond,
            double xMeters,
            double yMeters,
            double zMeters,
            boolean moving
    ) {
        this(timeNanos, secondsElapsed, latitude, longitude, gpsAltitudeMeters, barometricAltitudeMeters,
                speedMetersPerSecond, xMeters, yMeters, zMeters, moving,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    public TrackPoint(
            long timeNanos,
            double secondsElapsed,
            double latitude,
            double longitude,
            double gpsAltitudeMeters,
            double barometricAltitudeMeters,
            double speedMetersPerSecond,
            double xMeters,
            double yMeters,
            double zMeters,
            boolean moving,
            double headingRadians
    ) {
        this(timeNanos, secondsElapsed, latitude, longitude, gpsAltitudeMeters, barometricAltitudeMeters,
                speedMetersPerSecond, xMeters, yMeters, zMeters, moving,
                headingRadians, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    public TrackPoint(
            long timeNanos,
            double secondsElapsed,
            double latitude,
            double longitude,
            double gpsAltitudeMeters,
            double barometricAltitudeMeters,
            double speedMetersPerSecond,
            double xMeters,
            double yMeters,
            double zMeters,
            boolean moving,
            double headingRadians,
            double pitchRadians
    ) {
        this(timeNanos, secondsElapsed, latitude, longitude, gpsAltitudeMeters, barometricAltitudeMeters,
                speedMetersPerSecond, xMeters, yMeters, zMeters, moving,
                headingRadians, pitchRadians, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    public TrackPoint(
            long timeNanos,
            double secondsElapsed,
            double latitude,
            double longitude,
            double gpsAltitudeMeters,
            double barometricAltitudeMeters,
            double speedMetersPerSecond,
            double xMeters,
            double yMeters,
            double zMeters,
            boolean moving,
            double headingRadians,
            double pitchRadians,
            double rollRadians
    ) {
        this(timeNanos, secondsElapsed, latitude, longitude, gpsAltitudeMeters, barometricAltitudeMeters,
                speedMetersPerSecond, xMeters, yMeters, zMeters, moving,
                headingRadians, pitchRadians, rollRadians, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }
}
