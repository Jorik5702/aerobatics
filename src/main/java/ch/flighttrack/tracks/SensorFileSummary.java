package ch.flighttrack.tracks;

import java.nio.file.Path;
import java.util.List;

public record SensorFileSummary(
        Path path,
        String name,
        List<String> headers,
        int rows
) {
    public boolean hasColumn(String columnName) {
        return headers.stream().anyMatch(columnName::equalsIgnoreCase);
    }
}
