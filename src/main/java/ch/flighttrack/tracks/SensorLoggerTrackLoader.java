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
    private static final Path DEFAULT_TRACKS_DIRECTORY = Path.of("tracks");
    private static final String PREFERRED_TRACK_DIRECTORY = "2025-10-18_12-30-13";
    private static final double EARTH_RADIUS_METERS = 6_378_137.0;

    public List<Path> discoverTracks() throws IOException {
        return discoverTracks(DEFAULT_TRACKS_DIRECTORY);
    }

    public List<Path> discoverTracks(Path tracksDirectory) throws IOException {
        if (!Files.isDirectory(tracksDirectory)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(tracksDirectory)) {
            return entries.filter(Files::isDirectory).sorted().toList();
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
        TrackFiles files = scanTrackFiles(trackDirectory);
        return files.summary();
    }

    public LoadedTrackData loadDetailedTrack(Path trackDirectory) throws IOException {
        TrackFiles files = scanTrackFiles(trackDirectory);
        Path locationFile = files.findCsv("Location.csv")
                .orElseThrow(() -> new IOException("Location.csv not found in " + trackDirectory.toAbsolutePath()));

        List<LocationSample> locations = readLocationSamples(locationFile);
        if (locations.isEmpty()) {
            throw new IOException("Location.csv contains no usable location samples");
        }

        List<BarometerSample> barometer = files.findCsv("Barometer.csv")
                .map(path -> {
                    try {
                        return readBarometerSamples(path);
                    } catch (IOException exception) {
                        throw new TrackLoadRuntimeException(exception);
                    }
                })
                .orElse(List.of());

        GroundReference reference = detectGroundReference(locations, barometer);
        List<TrackPoint> points = buildTrackPoints(locations, barometer, reference);
        return new LoadedTrackData(files.summary(), reference, List.copyOf(points), files.summary().metadata());
    }

    private TrackFiles scanTrackFiles(Path trackDirectory) throws IOException {
        if (!Files.isDirectory(trackDirectory)) {
            throw new IOException("Track directory does not exist: " + trackDirectory.toAbsolutePath());
        }

        List<SensorFileSummary> sensorFiles = new ArrayList<>();
        Optional<LocationSummary> locationSummary = Optional.empty();
        Map<String, String> metadata = Map.of();
        Map<String, Path> csvByName = new HashMap<>();

        try (Stream<Path> files = Files.list(trackDirectory)) {
            List<Path> csvFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted()
                    .toList();

            for (Path csvFile : csvFiles) {
                String fileName = csvFile.getFileName().toString();
                csvByName.put(fileName.toLowerCase(Locale.ROOT), csvFile);
                CsvSummary csvSummary = readCsvSummary(csvFile);
                sensorFiles.add(new SensorFileSummary(csvFile, fileName, csvSummary.headers(), csvSummary.rows()));

                if ("Location.csv".equalsIgnoreCase(fileName)) {
                    locationSummary = summarizeLocation(csvFile, csvSummary.headers());
                } else if ("metadata.csv".equalsIgnoreCase(fileName)) {
                    metadata = readMetadata(csvFile);
                }
            }
        }

        TrackSummary summary = new TrackSummary(trackDirectory, List.copyOf(sensorFiles), locationSummary, metadata);
        return new TrackFiles(summary, Map.copyOf(csvByName));
    }

    private List<LocationSample> readLocationSamples(Path locationFile) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(locationFile, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return List.of();
            }

            List<String> headers = parseCsvLine(headerLine);
            Map<String, Integer> columns = indexHeaders(headers);
            Integer timeColumn = firstExistingColumn(columns, "time", "timestamp");
            Integer secondsColumn = firstExistingColumn(columns, "seconds_elapsed", "seconds", "elapsed");
            Integer latitudeColumn = firstExistingColumn(columns, "latitude", "lat");
            Integer longitudeColumn = firstExistingColumn(columns, "longitude", "lng", "lon");
            Integer altitudeColumn = firstExistingColumn(columns, "altitude", "altitude_wgs84", "altitude_above_mean_sea_level");
            Integer speedColumn = firstExistingColumn(columns, "speed", "speed_meters_per_second", "horizontal_speed");

            if (latitudeColumn == null || longitudeColumn == null) {
                return List.of();
            }

            List<LocationSample> samples = new ArrayList<>();
            String row;
            while ((row = reader.readLine()) != null) {
                List<String> values = parseCsvLine(row);
                Optional<Double> latitude = readDouble(values, latitudeColumn);
                Optional<Double> longitude = readDouble(values, longitudeColumn);
                if (latitude.isEmpty() || longitude.isEmpty()) {
                    continue;
                }

                long timeNanos = timeColumn == null ? samples.size() : readLong(values, timeColumn).orElse(samples.size());
                double secondsElapsed = secondsColumn == null ? Double.NaN : readDouble(values, secondsColumn).orElse(Double.NaN);
                double altitude = altitudeColumn == null ? Double.NaN : readDouble(values, altitudeColumn).orElse(Double.NaN);
                double speed = speedColumn == null ? Double.NaN : readDouble(values, speedColumn).orElse(Double.NaN);

                samples.add(new LocationSample(timeNanos, secondsElapsed, latitude.get(), longitude.get(), altitude, speed));
            }

            samples.sort(Comparator.comparingLong(LocationSample::timeNanos));
            return samples;
        }
    }

    private List<BarometerSample> readBarometerSamples(Path barometerFile) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(barometerFile, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return List.of();
            }

            List<String> headers = parseCsvLine(headerLine);
            Map<String, Integer> columns = indexHeaders(headers);
            Integer timeColumn = firstExistingColumn(columns, "time", "timestamp");
            Integer secondsColumn = firstExistingColumn(columns, "seconds_elapsed", "seconds", "elapsed");
            Integer altitudeColumn = firstExistingColumn(columns,
                    "relative_altitude", "altitude", "barometric_altitude", "altitude_meters");
            Integer pressureColumn = firstExistingColumn(columns, "pressure", "pressure_kpa", "pressure_hpa");

            if (altitudeColumn == null) {
                return List.of();
            }

            List<BarometerSample> samples = new ArrayList<>();
            String row;
            while ((row = reader.readLine()) != null) {
                List<String> values = parseCsvLine(row);
                Optional<Double> altitude = readDouble(values, altitudeColumn);
                if (altitude.isEmpty()) {
                    continue;
                }

                long timeNanos = timeColumn == null ? samples.size() : readLong(values, timeColumn).orElse(samples.size());
                double secondsElapsed = secondsColumn == null ? Double.NaN : readDouble(values, secondsColumn).orElse(Double.NaN);
                double pressure = pressureColumn == null ? Double.NaN : readDouble(values, pressureColumn).orElse(Double.NaN);
                samples.add(new BarometerSample(timeNanos, secondsElapsed, altitude.get(), pressure));
            }

            samples.sort(Comparator.comparingLong(BarometerSample::timeNanos));
            return samples;
        }
    }

    private GroundReference detectGroundReference(List<LocationSample> locations, List<BarometerSample> barometer) {
        int maxSamples = Math.min(locations.size(), 300);
        double sumLatitude = 0.0;
        double sumLongitude = 0.0;
        double sumBarometricAltitude = 0.0;
        double minBarometricAltitude = Double.POSITIVE_INFINITY;
        double maxBarometricAltitude = Double.NEGATIVE_INFINITY;
        int count = 0;

        for (int i = 0; i < maxSamples; i++) {
            LocationSample location = locations.get(i);
            double barometricAltitude = altitudeFor(location, barometer);
            if (Double.isNaN(barometricAltitude)) {
                continue;
            }

            double speed = Double.isNaN(location.speedMetersPerSecond()) ? 0.0 : location.speedMetersPerSecond();
            minBarometricAltitude = Math.min(minBarometricAltitude, barometricAltitude);
            maxBarometricAltitude = Math.max(maxBarometricAltitude, barometricAltitude);

            if (speed <= 1.0) {
                sumLatitude += location.latitude();
                sumLongitude += location.longitude();
                sumBarometricAltitude += barometricAltitude;
                count++;
            }
        }

        if (count == 0 || maxBarometricAltitude - minBarometricAltitude > 2.0) {
            LocationSample first = locations.getFirst();
            return new GroundReference(first.latitude(), first.longitude(), altitudeFor(first, barometer));
        }

        return new GroundReference(sumLatitude / count, sumLongitude / count, sumBarometricAltitude / count);
    }

    private List<TrackPoint> buildTrackPoints(List<LocationSample> locations, List<BarometerSample> barometer, GroundReference reference) {
        List<TrackPoint> points = new ArrayList<>(locations.size());
        for (LocationSample location : locations) {
            double barometricAltitude = altitudeFor(location, barometer);
            double dLat = Math.toRadians(location.latitude() - reference.latitude());
            double dLon = Math.toRadians(location.longitude() - reference.longitude());
            double xEast = dLon * EARTH_RADIUS_METERS * Math.cos(Math.toRadians(reference.latitude()));
            double yNorth = dLat * EARTH_RADIUS_METERS;
            double zUp = barometricAltitude - reference.barometricAltitudeMeters();
            double horizontalDistance = Math.hypot(xEast, yNorth);
            boolean moving = Math.abs(zUp) > 1.0
                    || horizontalDistance > 5.0
                    || (!Double.isNaN(location.speedMetersPerSecond()) && location.speedMetersPerSecond() > 1.5);

            points.add(new TrackPoint(
                    location.timeNanos(),
                    location.secondsElapsed(),
                    location.latitude(),
                    location.longitude(),
                    location.gpsAltitudeMeters(),
                    barometricAltitude,
                    location.speedMetersPerSecond(),
                    xEast,
                    yNorth,
                    zUp,
                    moving));
        }
        return points;
    }

    private double altitudeFor(LocationSample location, List<BarometerSample> barometer) {
        if (barometer.isEmpty()) {
            return location.gpsAltitudeMeters();
        }

        BarometerSample nearest = nearestBarometer(location.timeNanos(), barometer);
        if (nearest == null || Double.isNaN(nearest.altitudeMeters())) {
            return location.gpsAltitudeMeters();
        }
        return nearest.altitudeMeters();
    }

    private BarometerSample nearestBarometer(long timeNanos, List<BarometerSample> barometer) {
        if (barometer.isEmpty()) {
            return null;
        }

        int low = 0;
        int high = barometer.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long candidate = barometer.get(mid).timeNanos();
            if (candidate < timeNanos) {
                low = mid + 1;
            } else if (candidate > timeNanos) {
                high = mid - 1;
            } else {
                return barometer.get(mid);
            }
        }

        if (low <= 0) {
            return barometer.getFirst();
        }
        if (low >= barometer.size()) {
            return barometer.getLast();
        }

        BarometerSample before = barometer.get(low - 1);
        BarometerSample after = barometer.get(low);
        return Math.abs(before.timeNanos() - timeNanos) <= Math.abs(after.timeNanos() - timeNanos) ? before : after;
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

    private Optional<Long> readLong(List<String> values, int columnIndex) {
        if (columnIndex < 0 || columnIndex >= values.size()) {
            return Optional.empty();
        }

        String value = values.get(columnIndex).trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            try {
                return Optional.of((long) Double.parseDouble(value));
            } catch (NumberFormatException ignoredAgain) {
                return Optional.empty();
            }
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

    private record TrackFiles(TrackSummary summary, Map<String, Path> csvByName) {
        Optional<Path> findCsv(String name) {
            return Optional.ofNullable(csvByName.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    private record LocationSample(long timeNanos, double secondsElapsed, double latitude, double longitude,
                                  double gpsAltitudeMeters, double speedMetersPerSecond) {
    }

    private record BarometerSample(long timeNanos, double secondsElapsed, double altitudeMeters, double pressure) {
    }

    private static final class TrackLoadRuntimeException extends RuntimeException {
        private TrackLoadRuntimeException(IOException cause) {
            super(cause);
        }
    }
}
