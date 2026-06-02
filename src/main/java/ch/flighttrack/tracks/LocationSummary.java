package ch.flighttrack.tracks;

public record LocationSummary(
        int samples,
        double minLatitude,
        double maxLatitude,
        double minLongitude,
        double maxLongitude,
        double minAltitude,
        double maxAltitude
) {
}
