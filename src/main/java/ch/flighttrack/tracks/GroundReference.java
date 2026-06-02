package ch.flighttrack.tracks;

public record GroundReference(
        double latitude,
        double longitude,
        double barometricAltitudeMeters
) {
}
