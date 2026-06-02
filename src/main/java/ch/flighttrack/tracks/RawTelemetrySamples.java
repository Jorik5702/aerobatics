package ch.flighttrack.tracks;

record RawLocation(
        long timeNanos,
        double secondsElapsed,
        double latitude,
        double longitude,
        double gpsAltitudeMeters,
        double speedMetersPerSecond
) {
}

record RawBarometer(long timeNanos, double altitudeMeters) {
}

record RawVector(long timeNanos, double x, double y, double z) {
}

record RawOrientation(
        long timeNanos,
        double x,
        double y,
        double z,
        double w,
        boolean quaternion
) {
}
