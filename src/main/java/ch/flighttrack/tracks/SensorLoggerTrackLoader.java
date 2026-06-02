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
    private static final Path TRACKS = Path.of("tracks");
    private static final String DEFAULT_TRACK = "2025-10-18_12-30-13";

    public List<Path> discoverTracks() throws IOException {
        if (!Files.isDirectory(TRACKS)) return List.of();
        try (Stream<Path> stream = Files.list(TRACKS)) {
            return stream.filter(Files::isDirectory).sorted().toList();
        }
    }

    public Optional<Path> defaultTrackDirectory() throws IOException {
        List<Path> tracks = discoverTracks();
        Optional<Path> preferred = tracks.stream()
                .filter(path -> DEFAULT_TRACK.equals(path.getFileName().toString()))
                .findFirst();
        return preferred.isPresent() ? preferred : tracks.stream().findFirst();
    }

    public TrackSummary load(Path trackDirectory) throws IOException {
        return scan(trackDirectory).summary();
    }

    public LoadedTrackData loadDetailedTrack(Path trackDirectory) throws IOException {
        TrackFiles files = scan(trackDirectory);
        return new TelemetryFusionEngine().load(files.summary());
    }

    private TrackFiles scan(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) throw new IOException("Track directory does not exist: " + dir.toAbsolutePath());
        List<SensorFileSummary> summaries = new ArrayList<>();
        Map<String, Path> csv = new HashMap<>();
        Map<String, String> metadata = Map.of();
        Optional<LocationSummary> location = Optional.empty();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).filter(this::isCsv).sorted().toList()) {
                CsvHead head = head(file);
                String name = file.getFileName().toString();
                csv.put(name.toLowerCase(Locale.ROOT), file);
                summaries.add(new SensorFileSummary(file, name, head.headers(), head.rows()));
                if ("Location.csv".equalsIgnoreCase(name)) location = summarizeLocation(file, head.headers());
                if ("metadata.csv".equalsIgnoreCase(name)) metadata = metadata(file);
            }
        }
        return new TrackFiles(new TrackSummary(dir, List.copyOf(summaries), location, metadata), Map.copyOf(csv));
    }

    private boolean isCsv(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private CsvHead head(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return new CsvHead(List.of(), 0);
            int rows = 0;
            while (reader.readLine() != null) rows++;
            return new CsvHead(csv(header), rows);
        }
    }

    private Map<String, String> metadata(Path file) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = csv(line);
                if (row.size() >= 2) result.put(row.get(0), row.get(1));
            }
        }
        return result;
    }

    private Optional<LocationSummary> summarizeLocation(Path file, List<String> headers) throws IOException {
        Map<String, Integer> h = index(headers);
        Integer lat = first(h, "latitude", "lat");
        Integer lon = first(h, "longitude", "lng", "lon");
        Integer alt = first(h, "altitude", "altitude_wgs84", "altitude_above_mean_sea_level");
        if (lat == null || lon == null) return Optional.empty();
        int n = 0;
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
        double minAlt = Double.POSITIVE_INFINITY, maxAlt = Double.NEGATIVE_INFINITY;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = csv(line);
                Optional<Double> la = dbl(row, lat);
                Optional<Double> lo = dbl(row, lon);
                if (la.isEmpty() || lo.isEmpty()) continue;
                n++;
                minLat = Math.min(minLat, la.get());
                maxLat = Math.max(maxLat, la.get());
                minLon = Math.min(minLon, lo.get());
                maxLon = Math.max(maxLon, lo.get());
                if (alt != null) {
                    Optional<Double> a = dbl(row, alt);
                    if (a.isPresent()) {
                        minAlt = Math.min(minAlt, a.get());
                        maxAlt = Math.max(maxAlt, a.get());
                    }
                }
            }
        }
        if (n == 0) return Optional.empty();
        if (Double.isInfinite(minAlt)) {
            minAlt = Double.NaN;
            maxAlt = Double.NaN;
        }
        return Optional.of(new LocationSummary(n, minLat, maxLat, minLon, maxLon, minAlt, maxAlt));
    }

    private Map<String, Integer> index(List<String> headers) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i).trim();
            result.put(header.toLowerCase(Locale.ROOT), i);
            result.put(normalizedHeader(header), i);
        }
        return result;
    }

    private Integer first(Map<String, Integer> index, String... names) {
        for (String name : names) {
            Integer value = index.get(name.toLowerCase(Locale.ROOT));
            if (value != null) return value;
            value = index.get(normalizedHeader(name));
            if (value != null) return value;
        }
        return null;
    }

    private String normalizedHeader(String header) {
        return header.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Optional<Double> dbl(List<String> row, int index) {
        if (index < 0 || index >= row.size() || row.get(index).isBlank()) return Optional.empty();
        try {
            return Optional.of(Double.parseDouble(row.get(index).trim()));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private List<String> csv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result;
    }

    private record CsvHead(List<String> headers, int rows) {}
    private record TrackFiles(TrackSummary summary, Map<String, Path> files) {}
}
