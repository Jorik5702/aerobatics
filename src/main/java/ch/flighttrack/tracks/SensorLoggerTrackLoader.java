package ch.flighttrack.tracks;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class SensorLoggerTrackLoader {
    private static final Path DEFAULT_TRACKS_DIRECTORY = Path.of("tracks");
    private static final String PREFERRED_TRACK_DIRECTORY = "2025-10-18_12-30-13";

    public List<Path> discoverTracks() throws IOException {
        return discoverTracks(DEFAULT_TRACKS_DIRECTORY);
    }

    public List<Path> discoverTracks(Path tracksDirectory) throws IOException {
        if (!Files.isDirectory(tracksDirectory)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(tracksDirectory)) {
            return entries
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        }
    }

    public Optional<Path> defaultTrackDirectory() throws IOException {
        List<Path> tracks = discoverTracks();
        Optional<Path> preferredTrack = tracks.stream()
                .filter(path -> PREFERRED_TRACK_DIRECTORY.equals(path.getFileName().toString()))
                .findFirst();

        return preferredTrack.or(() -> tracks.stream().findFirst());
    }

    public TrackSummary load(Path trackDirectory) throws IOException {
        if (!Files.isDirectory(trackDirectory)) {
            throw new IOException("Track directory does not exist: " + trackDirectory.toAbsolutePath());
        }

        List<SensorFileSummary> sensorFiles = new ArrayList<>();
        Optional<LocationSummary> locationSummary = Optional.empty();
        Map<String, String> metadata = Map.of();

        try (Stream<Path> files = Files.list(trackDirectory)) {
            List<Path> csvFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted()
                    .toList();

            for (Path csvFile : csvFiles) {
                CsvSummary csvSummary = readCsvSummary(csvFile);
                sensorFiles.add(new SensorFileSummary(csvFile, csvFile.getFileName().toString(), csvSummary.headers(), csvSummary.rows()));

                String fileName = csvFile.getFileName().toString();
                if ("Location.csv".equalsIgnoreCase(fileName)) {
                    locationSummary = summarizeLocation(csvFile, csvSummary.headers());
                } else if ("metadata.csv".equalsIgnoreCase(fileName)) {
                    metadata = readMetadata(csvFile);
                }
            }
        }

        return new TrackSummary(trackDirectory, List.copyOf(sensorFiles), locationSummary, metadata);
    }

    private CsvSummary readCsvSummary(Path csvFile) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new CsvSummary(List.of(), 0);
            }

            int rows = 0;
            while (reader.readLine() != null) {
                rows++;
            }

            return new CsvSummary(parseCsvLine(headerLine), rows);
        }
    }

    private Map<String, String> readMetadata(Path metadataFile) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(metadataFile, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return metadata;
            }

            List<String> headers = parseCsvLine(headerLine);
            String row;
            while ((row = reader.readLine()) != null) {
                List<String> values = parseCsvLine(row);
                if (headers.size() >= 2 && values.size() >= 2) {
                    metadata.put(values.get(0), values.get(1));
                } else if (!values.isEmpty()) {
                    metadata.put("row-" + metadata.size(), String.join(", ", values));
                }
            }
        }
        return metadata;
    }

    private Optional<LocationSummary> summarizeLocation(Path locationFile, List<String> headers) throws IOException {
        Map<String, Integer> columnIndex = indexHeaders(headers);
        Integer latitudeColumn = firstExistingColumn(columnIndex, "latitude", "lat");
        Integer longitudeColumn = firstExistingColumn(columnIndex, "longitude", "lng", "lon");
        Integer altitudeColumn = firstExistingColumn(columnIndex, "altitude", "altitude_wgs84", "altitude_above_mean_sea_level");

        if (latitudeColumn == null || longitudeColumn == null) {
            return Optional.empty();
        }

        int samples = 0;
        double minLatitude = Double.POSITIVE_INFINITY;
        double maxLatitude = Double.NEGATIVE_INFINITY;
        double minLongitude = Double.POSITIVE_INFINITY;
        double maxLongitude = Double.NEGATIVE_INFINITY;
        double minAltitude = Double.POSITIVE_INFINITY;
        double maxAltitude = Double.NEGATIVE_INFINITY;

        try (BufferedReader reader = Files.newBufferedReader(locationFile, StandardCharsets.UTF_8)) {
            reader.readLine();
            String row;
            while ((row = reader.readLine()) != null) {
                List<String> values = parseCsvLine(row);
                Optional<Double> latitude = readDouble(values, latitudeColumn);
                Optional<Double> longitude = readDouble(values, longitudeColumn);
                if (latitude.isEmpty() || longitude.isEmpty()) {
                    continue;
                }

                samples++;
                minLatitude = Math.min(minLatitude, latitude.get());
                maxLatitude = Math.max(maxLatitude, latitude.get());
                minLongitude = Math.min(minLongitude, longitude.get());
                maxLongitude = Math.max(maxLongitude, longitude.get());

                if (altitudeColumn != null) {
                    Optional<Double> altitude = readDouble(values, altitudeColumn);
                    if (altitude.isPresent()) {
                        minAltitude = Math.min(minAltitude, altitude.get());
                        maxAltitude = Math.max(maxAltitude, altitude.get());
                    }
                }
            }
        }

        if (samples == 0) {
            return Optional.empty();
        }

        if (Double.isInfinite(minAltitude)) {
            minAltitude = Double.NaN;
            maxAltitude = Double.NaN;
        }

        return Optional.of(new LocationSummary(samples, minLatitude, maxLatitude, minLongitude, maxLongitude, minAltitude, maxAltitude));
    }

    private Map<String, Integer> indexHeaders(List<String> headers) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            index.put(headers.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        return index;
    }

    private Integer firstExistingColumn(Map<String, Integer> columnIndex, String... names) {
        for (String name : names) {
            Integer index = columnIndex.get(name.toLowerCase(Locale.ROOT));
            if (index != null) {
                return index;
            }
        }
        return null;
    }

    private Optional<Double> readDouble(List<String> values, int columnIndex) {
        if (columnIndex < 0 || columnIndex >= values.size()) {
            return Optional.empty();
        }

        String value = values.get(columnIndex).trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        values.add(current.toString());
        return values;
    }

    private record CsvSummary(List<String> headers, int rows) {
    }
}
