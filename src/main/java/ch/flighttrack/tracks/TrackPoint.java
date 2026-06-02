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
        boolean moving
) {
}
