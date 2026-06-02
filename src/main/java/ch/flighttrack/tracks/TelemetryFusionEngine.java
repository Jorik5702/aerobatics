package ch.flighttrack.tracks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class TelemetryFusionEngine {
    private static final double EARTH_RADIUS_METERS = 6_378_137.0;
    private static final double GPS_POSITION_GAIN = 0.18;
    private static final double GPS_VELOCITY_GAIN = 0.12;

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
        List<TrackPoint> points = fusedPoints(locations, barometer, accelerometer, gravity, reference, bias, startTime);
        if (points.isEmpty()) throw new IOException("No usable fused telemetry points found");

        System.out.printf("Telemetry fusion: locations=%d, barometer=%d, accelerometer=%d, gravity=%d, fusedPoints=%d%n",
                locations.size(), barometer.size(), accelerometer.size(), gravity.size(), points.size());
        System.out.println("Telemetry fusion mode: GPS-corrected IMU prediction; barometer is vertical truth.");
        System.out.printf("Phone mount assumption: flat/top-forward. Gravity diagnostic: %s%n", mount.description());
        System.out.printf("Accelerometer initial bias: x=%.4f, y=%.4f, z=%.4f%n", bias.x(), bias.y(), bias.z());
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
                                         GroundReference reference, AccelBias bias, long startTime) {
        java.util.ArrayList<TrackPoint> result = new java.util.ArrayList<>();
        FusionState state = new FusionState(reference, bias);
        MergedCursor cursor = new MergedCursor(locations, barometer, accelerometer, gravity, startTime);
        long firstTime = -1L;
        MergedSample sample;
        while ((sample = cursor.next()) != null) {
            if (firstTime < 0L) firstTime = sample.timeNanos();
            FusedPosition position = state.update(sample);
            double z = sample.baroAltitude() - reference.barometricAltitudeMeters();
            double seconds = (sample.timeNanos() - firstTime) / 1_000_000_000.0;
            RawLocation location = sample.location();
            boolean moving = Math.abs(z) > 1.0 || Math.hypot(position.x(), position.y()) > 5.0
                    || (!Double.isNaN(location.speedMetersPerSecond()) && location.speedMetersPerSecond() > 1.5);
            result.add(new TrackPoint(sample.timeNanos(), seconds, location.latitude(), location.longitude(),
                    location.gpsAltitudeMeters(), sample.baroAltitude(), location.speedMetersPerSecond(),
                    position.x(), position.y(), z, moving));
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
        if (start >= end) return new Mount("no gravity data; using default device +Y forward, +X right, +Z up");
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

    private final class FusionState {
        private final GroundReference reference;
        private final AccelBias bias;
        private boolean initialized;
        private long lastTime;
        private double x;
        private double y;
        private double vx;
        private double vy;
        private double heading;
        private RawLocation previousGps;

        FusionState(GroundReference reference, AccelBias bias) {
            this.reference = reference;
            this.bias = bias;
        }

        FusedPosition update(MergedSample sample) {
            double gpsX = gpsX(sample.location(), reference);
            double gpsY = gpsY(sample.location(), reference);
            if (!initialized) {
                initialized = true;
                lastTime = sample.timeNanos();
                previousGps = sample.location();
                x = gpsX;
                y = gpsY;
                return new FusedPosition(x, y);
            }
            double dt = Math.max(0.0, (sample.timeNanos() - lastTime) / 1_000_000_000.0);
            lastTime = sample.timeNanos();
            if (dt > 0.0 && dt < 1.0) {
                updateHeading(sample.location());
                RawVector acc = sample.accelerometer();
                if (acc != null) {
                    double forward = acc.y() - bias.y();
                    double right = acc.x() - bias.x();
                    double eastAcc = Math.sin(heading) * forward + Math.cos(heading) * right;
                    double northAcc = Math.cos(heading) * forward - Math.sin(heading) * right;
                    vx += eastAcc * dt;
                    vy += northAcc * dt;
                }
                x += vx * dt;
                y += vy * dt;
            }
            double dx = gpsX - x;
            double dy = gpsY - y;
            x += GPS_POSITION_GAIN * dx;
            y += GPS_POSITION_GAIN * dy;
            if (dt > 0.0 && dt < 1.0) {
                vx += GPS_VELOCITY_GAIN * dx / dt;
                vy += GPS_VELOCITY_GAIN * dy / dt;
            }
            return new FusedPosition(x, y);
        }

        private void updateHeading(RawLocation location) {
            if (previousGps == null || location.timeNanos() == previousGps.timeNanos()) return;
            double dx = gpsX(location, reference) - gpsX(previousGps, reference);
            double dy = gpsY(location, reference) - gpsY(previousGps, reference);
            if (Math.hypot(dx, dy) > 0.8) heading = Math.atan2(dx, dy);
            previousGps = location;
        }
    }

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
    private record FusedPosition(double x, double y) {}
}
