package ch.flighttrack.tracks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class TelemetryFusionEngine {
    private static final double EARTH_RADIUS_METERS = 6_378_137.0;

    LoadedTrackData load(TrackSummary summary) throws IOException {
        Path directory = summary.directory();
        RawTelemetryReader reader = new RawTelemetryReader();
        List<RawLocation> locations = reader.locations(directory.resolve("Location.csv"));
        List<RawBarometer> barometer = reader.barometer(directory.resolve("Barometer.csv"));
        List<RawVector> accelerometer = reader.vector(directory.resolve("Accelerometer.csv"));
        List<RawVector> gravity = reader.vector(directory.resolve("Gravity.csv"));

        if (locations.isEmpty()) throw new IOException("Location.csv contains no usable location samples");
        if (barometer.isEmpty()) throw new IOException("Barometer.csv contains no usable barometric samples");

        long startTime = locations.get(0).timeNanos();
        AccelBias bias = estimateBias(accelerometer, startTime);
        Mount mount = detectMount(gravity, startTime);
        GroundReference reference = lowestReference(locations, barometer, accelerometer, gravity, startTime);
        List<TrackPoint> points = fusedPoints(locations, barometer, accelerometer, gravity, reference, startTime);
        if (points.isEmpty()) throw new IOException("No usable fused telemetry points found");

        System.out.printf("Telemetry fusion: locations=%d, barometer=%d, accelerometer=%d, gravity=%d, fusedPoints=%d%n",
                locations.size(), barometer.size(), accelerometer.size(), gravity.size(), points.size());
        System.out.println("Telemetry fusion mode: conservative multi-rate timeline; GPS is horizontal truth, barometer is vertical truth.");
        System.out.println("Accelerometer and gravity are parsed for diagnostics only until the EKF orientation model is implemented.");
        System.out.printf("Phone mount diagnostic: %s%n", mount.description());
        System.out.printf("Accelerometer initial bias diagnostic: x=%.4f, y=%.4f, z=%.4f%n", bias.x(), bias.y(), bias.z());
        return new LoadedTrackData(summary, reference, points, summary.metadata());
    }

    private GroundReference lowestReference(List<RawLocation> locations, List<RawBarometer> barometer,
                                            List<RawVector> accelerometer, List<RawVector> gravity,
                                            long startTime) throws IOException {
        MergedCursor cursor = new MergedCursor(locations, barometer, accelerometer, gravity, startTime);
        MergedSample lowest = null;
        MergedSample sample;
        while ((sample = cursor.next()) != null) {
            if (lowest == null || sample.baroAltitude() < lowest.baroAltitude()) lowest = sample;
        }
        if (lowest == null) throw new IOException("No merged telemetry sample with location and barometric height found");
        return new GroundReference(lowest.location().latitude(), lowest.location().longitude(), lowest.baroAltitude());
    }

    private List<TrackPoint> fusedPoints(List<RawLocation> locations, List<RawBarometer> barometer,
                                         List<RawVector> accelerometer, List<RawVector> gravity,
                                         GroundReference reference, long startTime) {
        java.util.ArrayList<TrackPoint> result = new java.util.ArrayList<>();
        MergedCursor cursor = new MergedCursor(locations, barometer, accelerometer, gravity, startTime);
        long firstTime = -1L;
        MergedSample sample;
        while ((sample = cursor.next()) != null) {
            if (firstTime < 0L) firstTime = sample.timeNanos();
            RawLocation location = sample.location();
            double x = gpsX(location, reference);
            double y = gpsY(location, reference);
            double z = sample.baroAltitude() - reference.barometricAltitudeMeters();
            double seconds = (sample.timeNanos() - firstTime) / 1_000_000_000.0;
            boolean moving = Math.abs(z) > 1.0 || Math.hypot(x, y) > 5.0
                    || (!Double.isNaN(location.speedMetersPerSecond()) && location.speedMetersPerSecond() > 1.5);
            result.add(new TrackPoint(sample.timeNanos(), seconds, location.latitude(), location.longitude(),
                    location.gpsAltitudeMeters(), sample.baroAltitude(), location.speedMetersPerSecond(), x, y, z, moving));
        }
        return List.copyOf(result);
    }

    private AccelBias estimateBias(List<RawVector> samples, long startTime) {
        int start = firstVectorAtOrAfter(samples, startTime);
        int end = Math.min(samples.size(), start + 250);
        if (start >= end) return new AccelBias(0.0, 0.0, 0.0);
        double sx = 0.0, sy = 0.0, sz = 0.0;
        for (int i = start; i < end; i++) {
            RawVector s = samples.get(i);
            sx += s.x(); sy += s.y(); sz += s.z();
        }
        int n = end - start;
        return new AccelBias(sx / n, sy / n, sz / n);
    }

    private Mount detectMount(List<RawVector> gravity, long startTime) {
        int start = firstVectorAtOrAfter(gravity, startTime);
        int end = Math.min(gravity.size(), start + 250);
        if (start >= end) return new Mount("no gravity data; default mount not applied to position");
        double sx = 0.0, sy = 0.0, sz = 0.0;
        for (int i = start; i < end; i++) {
            RawVector s = gravity.get(i);
            sx += s.x(); sy += s.y(); sz += s.z();
        }
        int n = end - start;
        double gx = sx / n, gy = sy / n, gz = sz / n;
        String side = gz >= 0.0 ? "screen likely up" : "screen likely down";
        return new Mount(String.format(Locale.ROOT, "avg gravity x=%.3f y=%.3f z=%.3f, %s", gx, gy, gz, side));
    }

    private double gpsX(RawLocation l, GroundReference r) {
        return Math.toRadians(l.longitude() - r.longitude()) * EARTH_RADIUS_METERS * Math.cos(Math.toRadians(r.latitude()));
    }

    private double gpsY(RawLocation l, GroundReference r) {
        return Math.toRadians(l.latitude() - r.latitude()) * EARTH_RADIUS_METERS;
    }

    private int firstLocationAtOrAfter(List<RawLocation> s, long t) { int lo = 0, hi = s.size(); while (lo < hi) { int m = (lo + hi) >>> 1; if (s.get(m).timeNanos() < t) lo = m + 1; else hi = m; } return lo; }
    private int firstBarometerAtOrAfter(List<RawBarometer> s, long t) { int lo = 0, hi = s.size(); while (lo < hi) { int m = (lo + hi) >>> 1; if (s.get(m).timeNanos() < t) lo = m + 1; else hi = m; } return lo; }
    private int firstVectorAtOrAfter(List<RawVector> s, long t) { int lo = 0, hi = s.size(); while (lo < hi) { int m = (lo + hi) >>> 1; if (s.get(m).timeNanos() < t) lo = m + 1; else hi = m; } return lo; }

    private final class MergedCursor {
        private final List<RawLocation> locations;
        private final List<RawBarometer> barometer;
        private final List<RawVector> accelerometer;
        private final List<RawVector> gravity;
        private int locationIndex;
        private int barometerIndex;
        private int accelerometerIndex;
        private int gravityIndex;
        private RawLocation lastLocation;
        private RawBarometer lastBarometer;
        private RawVector lastAccelerometer;
        private RawVector lastGravity;

        MergedCursor(List<RawLocation> locations, List<RawBarometer> barometer, List<RawVector> accelerometer,
                     List<RawVector> gravity, long startTime) {
            this.locations = locations;
            this.barometer = barometer;
            this.accelerometer = accelerometer;
            this.gravity = gravity;
            locationIndex = firstLocationAtOrAfter(locations, startTime);
            barometerIndex = firstBarometerAtOrAfter(barometer, startTime);
            accelerometerIndex = firstVectorAtOrAfter(accelerometer, startTime);
            gravityIndex = firstVectorAtOrAfter(gravity, startTime);
            if (locationIndex > 0) lastLocation = locations.get(locationIndex - 1);
            if (barometerIndex > 0) lastBarometer = barometer.get(barometerIndex - 1);
            if (accelerometerIndex > 0) lastAccelerometer = accelerometer.get(accelerometerIndex - 1);
            if (gravityIndex > 0) lastGravity = gravity.get(gravityIndex - 1);
        }

        MergedSample next() {
            while (locationIndex < locations.size() || barometerIndex < barometer.size()
                    || accelerometerIndex < accelerometer.size() || gravityIndex < gravity.size()) {
                long next = Long.MAX_VALUE;
                if (locationIndex < locations.size()) next = Math.min(next, locations.get(locationIndex).timeNanos());
                if (barometerIndex < barometer.size()) next = Math.min(next, barometer.get(barometerIndex).timeNanos());
                if (accelerometerIndex < accelerometer.size()) next = Math.min(next, accelerometer.get(accelerometerIndex).timeNanos());
                if (gravityIndex < gravity.size()) next = Math.min(next, gravity.get(gravityIndex).timeNanos());
                while (locationIndex < locations.size() && locations.get(locationIndex).timeNanos() == next) lastLocation = locations.get(locationIndex++);
                while (barometerIndex < barometer.size() && barometer.get(barometerIndex).timeNanos() == next) lastBarometer = barometer.get(barometerIndex++);
                while (accelerometerIndex < accelerometer.size() && accelerometer.get(accelerometerIndex).timeNanos() == next) lastAccelerometer = accelerometer.get(accelerometerIndex++);
                while (gravityIndex < gravity.size() && gravity.get(gravityIndex).timeNanos() == next) lastGravity = gravity.get(gravityIndex++);
                if (lastLocation != null && lastBarometer != null) return new MergedSample(next, lastLocation, lastBarometer.altitudeMeters(), lastAccelerometer, lastGravity);
            }
            return null;
        }
    }

    private record AccelBias(double x, double y, double z) {}
    private record Mount(String description) {}
    private record MergedSample(long timeNanos, RawLocation location, double baroAltitude, RawVector accelerometer, RawVector gravity) {}
}
