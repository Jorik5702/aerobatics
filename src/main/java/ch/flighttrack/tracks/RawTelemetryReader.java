package ch.flighttrack.tracks;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class RawTelemetryReader {
    List<RawLocation> locations(Path file) throws IOException {
        List<RawLocation> result = new ArrayList<>();
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
                result.add(new RawLocation(
                        time == null ? result.size() : lng(row, time).orElse((long) result.size()),
                        seconds == null ? Double.NaN : dbl(row, seconds).orElse(Double.NaN),
                        latitude.get(), longitude.get(),
                        alt == null ? Double.NaN : dbl(row, alt).orElse(Double.NaN),
                        speed == null ? Double.NaN : dbl(row, speed).orElse(Double.NaN)));
            }
        }
        result.sort(Comparator.comparingLong(RawLocation::timeNanos));
        return result;
    }

    List<RawBarometer> barometer(Path file) throws IOException {
        List<RawBarometer> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return result;
            Map<String, Integer> h = index(csv(header));
            Integer time = first(h, "time", "timestamp");
            Integer altitude = first(h, "relativeAltitude", "relative_altitude", "altitude", "barometric_altitude", "altitude_meters");
            if (altitude == null) return result;
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = csv(line);
                Optional<Double> value = dbl(row, altitude);
                if (value.isEmpty()) continue;
                result.add(new RawBarometer(time == null ? result.size() : lng(row, time).orElse((long) result.size()), value.get()));
            }
        }
        result.sort(Comparator.comparingLong(RawBarometer::timeNanos));
        return result;
    }

    List<RawVector> vector(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        List<RawVector> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return result;
            Map<String, Integer> h = index(csv(header));
            Integer time = first(h, "time", "timestamp");
            Integer x = first(h, "x", "accelerationX", "gravityX", "magneticFieldX", "x_uncalib", "xUncalibrated");
            Integer y = first(h, "y", "accelerationY", "gravityY", "magneticFieldY", "y_uncalib", "yUncalibrated");
            Integer z = first(h, "z", "accelerationZ", "gravityZ", "magneticFieldZ", "z_uncalib", "zUncalibrated");
            if (x == null || y == null || z == null) return result;
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = csv(line);
                Optional<Double> vx = dbl(row, x);
                Optional<Double> vy = dbl(row, y);
                Optional<Double> vz = dbl(row, z);
                if (vx.isEmpty() || vy.isEmpty() || vz.isEmpty()) continue;
                result.add(new RawVector(time == null ? result.size() : lng(row, time).orElse((long) result.size()), vx.get(), vy.get(), vz.get()));
            }
        }
        result.sort(Comparator.comparingLong(RawVector::timeNanos));
        return result;
    }

    List<RawOrientation> orientation(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        List<RawOrientation> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return result;
            Map<String, Integer> h = index(csv(header));
            Integer time = first(h, "time", "timestamp");
            Integer qx = first(h, "qx", "quatX", "quaternionX", "x");
            Integer qy = first(h, "qy", "quatY", "quaternionY", "y");
            Integer qz = first(h, "qz", "quatZ", "quaternionZ", "z");
            Integer qw = first(h, "qw", "quatW", "quaternionW", "w");
            boolean quaternion = qx != null && qy != null && qz != null && qw != null;
            Integer yaw = first(h, "yaw", "azimuth", "heading");
            Integer pitch = first(h, "pitch");
            Integer roll = first(h, "roll");
            if (!quaternion && (yaw == null || pitch == null || roll == null)) return result;
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = csv(line);
                long sampleTime = time == null ? result.size() : lng(row, time).orElse((long) result.size());
                if (quaternion) {
                    Optional<Double> x = dbl(row, qx);
                    Optional<Double> y = dbl(row, qy);
                    Optional<Double> z = dbl(row, qz);
                    Optional<Double> w = dbl(row, qw);
                    if (x.isPresent() && y.isPresent() && z.isPresent() && w.isPresent()) {
                        result.add(new RawOrientation(sampleTime, x.get(), y.get(), z.get(), w.get(), true));
                    }
                } else {
                    Optional<Double> yv = dbl(row, yaw);
                    Optional<Double> pv = dbl(row, pitch);
                    Optional<Double> rv = dbl(row, roll);
                    if (yv.isPresent() && pv.isPresent() && rv.isPresent()) {
                        result.add(new RawOrientation(sampleTime, yv.get(), pv.get(), rv.get(), Double.NaN, false));
                    }
                }
            }
        }
        result.sort(Comparator.comparingLong(RawOrientation::timeNanos));
        return result;
    }

    private Map<String, Integer> index(List<String> headers) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i).trim();
            result.put(header.toLowerCase(Locale.ROOT), i);
            result.put(normalized(header), i);
        }
        return result;
    }

    private Integer first(Map<String, Integer> index, String... names) {
        for (String name : names) {
            Integer value = index.get(name.toLowerCase(Locale.ROOT));
            if (value != null) return value;
            value = index.get(normalized(name));
            if (value != null) return value;
        }
        return null;
    }

    private String normalized(String header) {
        return header.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
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
}
