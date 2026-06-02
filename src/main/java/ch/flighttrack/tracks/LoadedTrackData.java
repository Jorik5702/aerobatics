package ch.flighttrack.tracks;

import java.util.List;
import java.util.Map;

public record LoadedTrackData(
        TrackSummary summary,
        GroundReference groundReference,
        List<TrackPoint> points,
        Map<String, String> metadata
) {
    public int movingPointCount() {
        return (int) points.stream().filter(TrackPoint::moving).count();
    }
}
