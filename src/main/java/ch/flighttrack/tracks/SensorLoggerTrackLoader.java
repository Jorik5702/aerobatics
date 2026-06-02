package ch.flighttrack.tracks;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final double EARTH_RADIUS_METERS = 6_378_137.0;
    private static final int STABLE_WINDOW_SAMPLES = 40;
    private static final double STABLE_ALTITUDE_RANGE_METERS = 0.8;
    private static final double STABLE_MAX_SPEED_METERS_PER_SECOND = 0.7;

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
        Path locationFile = files.csv("Location.csv")
                .orElseThrow(() -> new IOException("Location.csv not found in " + trackDirectory.toAbsolutePath()));
        List<LocationSample> allLocations = readLocations(locationFile);
        if (allLocations.isEmpty()) throw new IOException("Location.csv contains no usable location samples");

        List<BarometerSample> barometer = List.of();
        Optional<Path> barometerFile = files.csv("Barometer.csv");
        if (barometerFile.isPresent()) barometer = readBarometer(barometerFile.get());

        int stableStart = stableStartIndex(allLocations, barometer);
        List<LocationSample> locations = List.copyOf(allLocations.subList(stableStart, allLocations.size()));
        GroundReference reference = reference(locations, barometer);
        List<TrackPoint> points = points(locations, barometer, reference);
        return new LoadedTrackData(files.summary(), reference, points, files.summary().metadata());
    }

    private int stableStartIndex(List<LocationSample> locations, List<BarometerSample> barometer) {
        if (locations.size() <= STABLE_WINDOW_SAMPLES) return 0;
        int maxStart = locations.size() - STABLE_WINDOW_SAMPLES;
        for (int start = 0; start <= maxStart; start++) {
            double minAltitude = Double.POSITIVE_INFINITY;
            double maxAltitude = Double.NEGATIVE_INFINITY;
            double maxSpeed = 0.0;
            int usableAltitudeSamples = 0;
            for (int i = start; i < start + STABLE_WINDOW_SAMPLES; i++) {
                LocationSample sample = locations.get(i);
                double altitude = altitude(sample, barometer);
                if (!Double.isNaN(altitude)) {
                    minAltitude = Math.min(minAltitude, altitude);
                    maxAltitude = Math.max(maxAltitude, altitude);
                    usableAltitudeSamples++;
                }
                double speed = Double.isNaN(sample.speedMetersPerSecond()) ? 0.0 : sample.speedMetersPerSecond();
                maxSpeed = Math.max(maxSpeed, speed);
            }
            double altitudeRange = maxAltitude - minAltitude;
            if (usableAltitudeSamples > STABLE_WINDOW_SAMPLES / 2
                    && altitudeRange <= STABLE_ALTITUDE_RANGE_METERS
                    && maxSpeed <= STABLE_MAX_SPEED_METERS_PER_SECOND) {
                return start;
            }
        }
        return 0;
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
        return new TrackFiles(new TrackSummary(dir, summaries, location, metadata), csv);
    }

    private boolean isCsv(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private List<LocationSample> readLocations(Path file) throws IOException {
        List<LocationSample> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return result;
            Map<String, Integer> h = index(csv(header));
            Integer time = first(h, "time", "timestamp");
            Integer seconds = first(h, "seconds_elapsed", "seconds", "elapsed");
            Integer lat = first(h, "latitude", "lat");
            Integer lon = first(h, "longitude", "lng", "lon");
            Integer alt = first(h, "altitude", "altitude_wgs84", "altitude_above_mean_sea_level");
            Integer speed = first(h, "speed", "speed_meters_per_second", "horizontal_speed");
            if (lat == null || lon == null) return result;
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = csv(line);
                Optional<Double> latitude = dbl(row, lat);
                Optional<Double> longitude = dbl(row, lon);
                if (latitude.isEmpty() || longitude.isEmpty()) continue;
                result.add(new LocationSample(
                        time == null ? result.size() : lng(row, time).orElse((long) result.size()),
                        seconds == null ? Double.NaN : dbl(row, seconds).orElse(Double.NaN),
                        latitude.get(), longitude.get(),
                        alt == null ? Double.NaN : dbl(row, alt).orElse(Double.NaN),
                        speed == null ? Double.NaN : dbl(row, speed).orElse(Double.NaN)));
            }
        }
        result.sort(Comparator.comparingLong(LocationSample::timeNanos));
        return result;
    }

    private List<BarometerSample> readBarometer(Path file) throws IOException {
        List<BarometerSample> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return result;
            Map<String, Integer> h = index(csv(header));
            Integer time = first(h, "time", "timestamp");
            Integer altitude = first(h, "relative_altitude", "altitude", "barometric_altitude", "altitude_meters");
            if (altitude == null) return result;
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = csv(line);
                Optional<Double> value = dbl(row, altitude);
                if (value.isEmpty()) continue;
                result.add(new BarometerSample(time == null ? result.size() : lng(row, time).orElse((long) result.size()), value.get()));
            }
        }
        result.sort(Comparator.comparingLong(BarometerSample::timeNanos));
        return result;
    }

    private GroundReference reference(List<LocationSample> locations, List<BarometerSample> barometer) {
        LocationSample lowestLocation = locations.get(0);
        double lowestAltitude = altitude(lowestLocation, barometer);
        for (LocationSample location : locations) {
            double candidateAltitude = altitude(location, barometer);
            if (!Double.isNaN(candidateAltitude) && (Double.isNaN(lowestAltitude) || candidateAltitude < lowestAltitude)) {
                lowestLocation = location;
                lowestAltitude = candidateAltitude;
            }
        }
        return new GroundReference(lowestLocation.latitude(), lowestLocation.longitude(), lowestAltitude);
    }

    private List<TrackPoint> points(List<LocationSample> locations, List<BarometerSample> barometer, GroundReference ref) {
        List<TrackPoint> result = new ArrayList<>();
        for (LocationSample loc : locations) {
            double baro = altitude(loc, barometer);
            double x = Math.toRadians(loc.longitude() - ref.longitude()) * EARTH_RADIUS_METERS * Math.cos(Math.toRadians(ref.latitude()));
            double y = Math.toRadians(loc.latitude() - ref.latitude()) * EARTH_RADIUS_METERS;
            double z = baro - ref.barometricAltitudeMeters();
            boolean moving = Math.abs(z) > 1.0 || Math.hypot(x, y) > 5.0 || (!Double.isNaN(loc.speedMetersPerSecond()) && loc.speedMetersPerSecond() > 1.5);
            result.add(new TrackPoint(loc.timeNanos(), loc.secondsElapsed(), loc.latitude(), loc.longitude(), loc.gpsAltitudeMeters(), baro, loc.speedMetersPerSecond(), x, y, z, moving));
        }
        return List.copyOf(result);
    }

    private double altitude(LocationSample sample, List<BarometerSample> barometer) {
        if (barometer.isEmpty()) return sample.gpsAltitudeMeters();
        BarometerSample nearest = nearest(sample.timeNanos(), barometer);
        return nearest == null ? sample.gpsAltitudeMeters() : nearest.altitudeMeters();
    }

    private BarometerSample nearest(long time, List<BarometerSample> samples) {
        BarometerSample best = null;
        long bestDiff = Long.MAX_VALUE;
        for (BarometerSample sample : samples) {
            long diff = Math.abs(sample.timeNanos() - time);
            if (diff < bestDiff) { best = sample; bestDiff = diff; }
        }
        return best;
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
                Optional<Double> la = dbl(row, lat), lo = dbl(row, lon);
                if (la.isEmpty() || lo.isEmpty()) continue;
                n++;
                minLat = Math.min(minLat, la.get()); maxLat = Math.max(maxLat, la.get());
                minLon = Math.min(minLon, lo.get()); maxLon = Math.max(maxLon, lo.get());
                if (alt != null) {
                    Optional<Double> a = dbl(row, alt);
                    if (a.isPresent()) { minAlt = Math.min(minAlt, a.get()); maxAlt = Math.max(maxAlt, a.get()); }
                }
            }
        }
        if (n == 0) return Optional.empty();
        if (Double.isInfinite(minAlt)) { minAlt = Double.NaN; maxAlt = Double.NaN; }
        return Optional.of(new LocationSummary(n, minLat, maxLat, minLon, maxLon, minAlt, maxAlt));
    }

    private Map<String, Integer> index(List<String> headers) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) result.put(headers.get(i).trim().toLowerCase(Locale.ROOT), i);
        return result;
    }

    private Integer first(Map<String, Integer> index, String... names) {
        for (String name : names) {
            Integer value = index.get(name.toLowerCase(Locale.ROOT));
            if (value != null) return value;
        }
        return null;
    }

    private Optional<Double> dbl(List<String> row, int index) {
        if (index < 0 || index >= row.size() || row.get(index).isBlank()) return Optional.empty();
        try { return Optional.of(Double.parseDouble(row.get(index).trim())); } catch (NumberFormatException e) { return Optional.empty(); }
    }

    private Optional<Long> lng(List<String> row, int index) {
        if (index < 0 || index >= row.size() || row.get(index).isBlank()) return Optional.empty();
        try { return Optional.of(Long.parseLong(row.get(index).trim())); } catch (NumberFormatException e) { return Optional.empty(); }
    }

    private List<String> csv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) { result.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        result.add(current.toString());
        return result;
    }

    private record CsvHead(List<String> headers, int rows) {}
    private record TrackFiles(TrackSummary summary, Map<String, Path> files) {
        Optional<Path> csv(String name) { return Optional.ofNullable(files.get(name.toLowerCase(Locale.ROOT))); }
    }
    private record LocationSample(long timeNanos, double secondsElapsed, double latitude, double longitude, double gpsAltitudeMeters, double speedMetersPerSecond) {}
    private record BarometerSample(long timeNanos, double altitudeMeters) {}
}
