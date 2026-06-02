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
        double headingRadians
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
                speedMetersPerSecond, xMeters, yMeters, zMeters, moving, Double.NaN);
    }
}
