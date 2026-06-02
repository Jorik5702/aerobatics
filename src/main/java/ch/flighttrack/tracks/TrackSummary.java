package ch.flighttrack.tracks;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record TrackSummary(
        Path directory,
        List<SensorFileSummary> sensorFiles,
        Optional<LocationSummary> locationSummary,
        Map<String, String> metadata
) {
    public String displayName() {
        Path fileName = directory.getFileName();
        return fileName == null ? directory.toString() : fileName.toString();
    }

    public int totalSamples() {
        return sensorFiles.stream().mapToInt(SensorFileSummary::rows).sum();
    }
}
