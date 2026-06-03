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
        List<RawVector> accelerometerUncalibrated = reader.vector(directory.resolve("AccelerometerUncalibrated.csv"));
        List<RawVector> gravity = reader.vector(directory.resolve("Gravity.csv"));
        List<RawVector> magnetometer = reader.vector(directory.resolve("Magnetometer.csv"));
        List<RawVector> magnetometerUncalibrated = reader.vector(directory.resolve("MagnetometerUncalibrated.csv"));
        List<RawOrientation> orientation = reader.orientation(directory.resolve("Orientation.csv"));

        if (locations.isEmpty()) throw new IOException("Location.csv contains no usable location samples");
        if (barometer.isEmpty()) throw new IOException("Barometer.csv contains no usable barometric samples");

        long startTime = locations.get(0).timeNanos();
        AccelBias bias = estimateBias(accelerometer, startTime);
        Mount mount = detectMount(gravity, startTime);
        OrientationDiagnostic orientationDiagnostic = diagnoseOrientation(orientation, startTime);
        String attitudeDiagnostic = new AttitudeDiagnostics().diagnose(locations, orientation, startTime);
        VectorDiagnostic magneticDiagnostic = diagnoseVector("magnetometer", magnetometer, startTime);
        VectorDiagnostic uncalibratedAccelDiagnostic = diagnoseVector("accelerometer uncalibrated", accelerometerUncalibrated, startTime);
        GroundReference reference = lowestReference(locations, barometer, accelerometer, gravity, orientation, startTime);
        List<TrackPoint> points = fusedPoints(locations, barometer, accelerometer, gravity, orientation, reference, startTime, bias);
        if (points.isEmpty()) throw new IOException("No usable fused telemetry points found");

        System.out.printf("Telemetry fusion: locations=%d, barometer=%d, accelerometer=%d, accelUncalibrated=%d, gravity=%d, magnetometer=%d, magnetometerUncalibrated=%d, orientation=%d, fusedPoints=%d%n",
                locations.size(), barometer.size(), accelerometer.size(), accelerometerUncalibrated.size(), gravity.size(), magnetometer.size(), magnetometerUncalibrated.size(), orientation.size(), points.size());
        System.out.println("Telemetry fusion mode: conservative multi-rate timeline; GPS is horizontal truth, barometer is vertical truth.");
        System.out.println("Plane symbol attitude uses the selected orientation mount and carries the last valid quaternion attitude forward.");
        System.out.println("Aircraft-axis acceleration diagnostics use calibrated Accelerometer.csv with initial bias removed.");
        System.out.printf("Phone mount diagnostic: %s%n", mount.description());
        System.out.printf("Orientation diagnostic: %s%n", orientationDiagnostic.description());
        System.out.printf("Attitude/GPS course diagnostic: %s%n", attitudeDiagnostic);
        System.out.printf("Magnetometer diagnostic: %s%n", magneticDiagnostic.description());
        System.out.printf("Uncalibrated accelerometer diagnostic: %s%n", uncalibratedAccelDiagnostic.description());
        System.out.printf("Accelerometer initial bias diagnostic: x=%.4f, y=%.4f, z=%.4f%n", bias.x(), bias.y(), bias.z());
        return new LoadedTrackData(summary, reference, points, summary.metadata());
    }

    private GroundReference lowestReference(List<RawLocation> locations, List<RawBarometer> barometer,
                                            List<RawVector> accelerometer, List<RawVector> gravity,
                                            List<RawOrientation> orientation, long startTime) throws IOException {
        MergedCursor cursor = new MergedCursor(locations, barometer, accelerometer, gravity, orientation, startTime);
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
                                         List<RawOrientation> orientation,
                                         GroundReference reference, long startTime, AccelBias bias) {
        java.util.ArrayList<TrackPoint> result = new java.util.ArrayList<>();
        MergedCursor cursor = new MergedCursor(locations, barometer, accelerometer, gravity, orientation, startTime);
        AttitudeState attitude = initialAttitude(orientation, startTime);
        AccelerationState acceleration = new AccelerationState(Double.NaN, Double.NaN, Double.NaN);
        long firstTime = -1L;
        MergedSample sample;
        while ((sample = cursor.next()) != null) {
            if (firstTime < 0L) firstTime = sample.timeNanos();
            RawLocation location = sample.location();
            double x = gpsX(location, reference);
            double y = gpsY(location, reference);
            double z = sample.baroAltitude() - reference.barometricAltitudeMeters();
            double seconds = (sample.timeNanos() - firstTime) / 1_000_000_000.0;
            attitude = attitude.withLatest(sample.orientation());
            acceleration = acceleration.withLatest(sample.accelerometer(), bias);
            boolean moving = Math.abs(z) > 1.0 || Math.hypot(x, y) > 5.0
                    || (!Double.isNaN(location.speedMetersPerSecond()) && location.speedMetersPerSecond() > 1.5);
            result.add(new TrackPoint(sample.timeNanos(), seconds, location.latitude(), location.longitude(),
                    location.gpsAltitudeMeters(), sample.baroAltitude(), location.speedMetersPerSecond(), x, y, z,
                    moving, attitude.heading(), attitude.pitch(), attitude.roll(),
                    acceleration.forward(), acceleration.right(), acceleration.up()));
        }
        return List.copyOf(result);
    }

    private AttitudeState initialAttitude(List<RawOrientation> orientation, long startTime) {
        int index = firstOrientationAtOrAfter(orientation, startTime);
        if (index >= orientation.size()) return new AttitudeState(Double.NaN, Double.NaN, Double.NaN);
        return new AttitudeState(Double.NaN, Double.NaN, Double.NaN).withLatest(orientation.get(index));
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
        double magnitude = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double flatTiltDegrees = magnitude == 0.0 ? Double.NaN : Math.toDegrees(Math.acos(Math.min(1.0, Math.abs(gz) / magnitude)));
        String side = gz < 0.0 ? "screen likely up" : "screen likely down";
        return new Mount(String.format(Locale.ROOT,
                "avg gravity x=%.3f y=%.3f z=%.3f, |g|=%.3f, flat tilt=%.1f deg, %s",
                gx, gy, gz, magnitude, flatTiltDegrees, side));
    }

    private OrientationDiagnostic diagnoseOrientation(List<RawOrientation> samples, long startTime) {
        int start = firstOrientationAtOrAfter(samples, startTime);
        if (start >= samples.size()) return new OrientationDiagnostic("no orientation data parsed");
        RawOrientation sample = samples.get(start);
        if (sample.quaternion()) {
            double norm = Math.sqrt(sample.x() * sample.x() + sample.y() * sample.y() + sample.z() * sample.z() + sample.w() * sample.w());
            return new OrientationDiagnostic(String.format(Locale.ROOT,
                    "first quaternion at %.3fs: x=%.4f y=%.4f z=%.4f w=%.4f norm=%.4f",
                    (sample.timeNanos() - startTime) / 1_000_000_000.0, sample.x(), sample.y(), sample.z(), sample.w(), norm));
        }
        return new OrientationDiagnostic(String.format(Locale.ROOT,
                "first yaw/pitch/roll-style sample at %.3fs: yaw=%.3f pitch=%.3f roll=%.3f",
                (sample.timeNanos() - startTime) / 1_000_000_000.0, sample.x(), sample.y(), sample.z()));
    }

    private VectorDiagnostic diagnoseVector(String name, List<RawVector> samples, long startTime) {
        int start = firstVectorAtOrAfter(samples, startTime);
        int end = Math.min(samples.size(), start + 250);
        if (start >= end) return new VectorDiagnostic("no " + name + " data parsed");
        double sx = 0.0, sy = 0.0, sz = 0.0;
        for (int i = start; i < end; i++) {
            RawVector s = samples.get(i);
            sx += s.x(); sy += s.y(); sz += s.z();
        }
        int n = end - start;
        return new VectorDiagnostic(String.format(Locale.ROOT, "initial avg x=%.4f y=%.4f z=%.4f from %d samples", sx / n, sy / n, sz / n, n));
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
    private int firstOrientationAtOrAfter(List<RawOrientation> s, long t) { int lo = 0, hi = s.size(); while (lo < hi) { int m = (lo + hi) >>> 1; if (s.get(m).timeNanos() < t) lo = m + 1; else hi = m; } return lo; }

    private final class MergedCursor {
        private final List<RawLocation> locations;
        private final List<RawBarometer> barometer;
        private final List<RawVector> accelerometer;
        private final List<RawVector> gravity;
        private final List<RawOrientation> orientation;
        private int locationIndex;
        private int barometerIndex;
        private int accelerometerIndex;
        private int gravityIndex;
        private int orientationIndex;
        private RawLocation lastLocation;
        private RawBarometer lastBarometer;
        private RawVector lastAccelerometer;
        private RawVector lastGravity;
        private RawOrientation lastOrientation;

        MergedCursor(List<RawLocation> locations, List<RawBarometer> barometer, List<RawVector> accelerometer,
                     List<RawVector> gravity, List<RawOrientation> orientation, long startTime) {
            this.locations = locations;
            this.barometer = barometer;
            this.accelerometer = accelerometer;
            this.gravity = gravity;
            this.orientation = orientation;
            locationIndex = firstLocationAtOrAfter(locations, startTime);
            barometerIndex = firstBarometerAtOrAfter(barometer, startTime);
            accelerometerIndex = firstVectorAtOrAfter(accelerometer, startTime);
            gravityIndex = firstVectorAtOrAfter(gravity, startTime);
            orientationIndex = firstOrientationAtOrAfter(orientation, startTime);
            if (locationIndex > 0) lastLocation = locations.get(locationIndex - 1);
            if (barometerIndex > 0) lastBarometer = barometer.get(barometerIndex - 1);
            if (accelerometerIndex > 0) lastAccelerometer = accelerometer.get(accelerometerIndex - 1);
            if (gravityIndex > 0) lastGravity = gravity.get(gravityIndex - 1);
            if (orientationIndex > 0 && !orientation.isEmpty()) lastOrientation = orientation.get(orientationIndex - 1);
        }

        MergedSample next() {
            while (locationIndex < locations.size() || barometerIndex < barometer.size()
                    || accelerometerIndex < accelerometer.size() || gravityIndex < gravity.size()
                    || orientationIndex < orientation.size()) {
                long next = Long.MAX_VALUE;
                if (locationIndex < locations.size()) next = Math.min(next, locations.get(locationIndex).timeNanos());
                if (barometerIndex < barometer.size()) next = Math.min(next, barometer.get(barometerIndex).timeNanos());
                if (accelerometerIndex < accelerometer.size()) next = Math.min(next, accelerometer.get(accelerometerIndex).timeNanos());
                if (gravityIndex < gravity.size()) next = Math.min(next, gravity.get(gravityIndex).timeNanos());
                if (orientationIndex < orientation.size()) next = Math.min(next, orientation.get(orientationIndex).timeNanos());
                while (locationIndex < locations.size() && locations.get(locationIndex).timeNanos() == next) lastLocation = locations.get(locationIndex++);
                while (barometerIndex < barometer.size() && barometer.get(barometerIndex).timeNanos() == next) lastBarometer = barometer.get(barometerIndex++);
                while (accelerometerIndex < accelerometer.size() && accelerometer.get(accelerometerIndex).timeNanos() == next) lastAccelerometer = accelerometer.get(accelerometerIndex++);
                while (gravityIndex < gravity.size() && gravity.get(gravityIndex).timeNanos() == next) lastGravity = gravity.get(gravityIndex++);
                while (orientationIndex < orientation.size() && orientation.get(orientationIndex).timeNanos() == next) lastOrientation = orientation.get(orientationIndex++);
                if (lastLocation != null && lastBarometer != null) return new MergedSample(next, lastLocation, lastBarometer.altitudeMeters(), lastAccelerometer, lastGravity, lastOrientation);
            }
            return null;
        }
    }

    private record AttitudeState(double heading, double pitch, double roll) {
        AttitudeState withLatest(RawOrientation orientation) {
            double nextHeading = OrientationMath.selectedForwardHeading(orientation);
            double nextPitch = OrientationMath.selectedForwardPitch(orientation);
            double nextRoll = OrientationMath.selectedRoll(orientation);
            return new AttitudeState(
                    Double.isNaN(nextHeading) ? heading : nextHeading,
                    Double.isNaN(nextPitch) ? pitch : nextPitch,
                    Double.isNaN(nextRoll) ? roll : nextRoll
            );
        }
    }

    private record AccelerationState(double forward, double right, double up) {
        AccelerationState withLatest(RawVector acceleration, AccelBias bias) {
            if (acceleration == null) return this;
            double ax = acceleration.x() - bias.x();
            double ay = acceleration.y() - bias.y();
            double az = acceleration.z() - bias.z();
            double forward = ax * PhoneMount.FORWARD_X + ay * PhoneMount.FORWARD_Y + az * PhoneMount.FORWARD_Z;
            double right = ax * PhoneMount.RIGHT_X + ay * PhoneMount.RIGHT_Y + az * PhoneMount.RIGHT_Z;
            double up = ax * PhoneMount.UP_X + ay * PhoneMount.UP_Y + az * PhoneMount.UP_Z;
            return new AccelerationState(forward, right, up);
        }
    }

    private record AccelBias(double x, double y, double z) {}
    private record Mount(String description) {}
    private record OrientationDiagnostic(String description) {}
    private record VectorDiagnostic(String description) {}
    private record MergedSample(long timeNanos, RawLocation location, double baroAltitude, RawVector accelerometer, RawVector gravity, RawOrientation orientation) {}
}
