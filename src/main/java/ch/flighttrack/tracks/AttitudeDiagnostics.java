package ch.flighttrack.tracks;

import java.util.List;
import java.util.Locale;

final class AttitudeDiagnostics {
    private static final double EARTH_RADIUS_METERS = 6_378_137.0;

    String diagnose(List<RawLocation> locations, List<RawOrientation> orientations, long startTimeNanos) {
        if (locations.size() < 2) return "not enough GPS samples for attitude validation";
        if (orientations.isEmpty()) return "no orientation samples available for attitude validation";

        AxisStats plusY = new AxisStats("device +Y forward");
        AxisStats minusY = new AxisStats("device -Y forward");
        AxisStats plusX = new AxisStats("device +X forward");
        AxisStats minusX = new AxisStats("device -X forward");

        int orientationIndex = 0;
        for (int i = 1; i < locations.size(); i++) {
            RawLocation previous = locations.get(i - 1);
            RawLocation current = locations.get(i);
            if (current.timeNanos() < startTimeNanos) continue;

            double east = eastMeters(previous, current);
            double north = northMeters(previous, current);
            double distance = Math.hypot(east, north);
            double speed = current.speedMetersPerSecond();
            if (distance < 5.0 && (Double.isNaN(speed) || speed < 5.0)) continue;

            orientationIndex = nearestOrientationIndex(orientations, orientationIndex, current.timeNanos());
            RawOrientation orientation = orientations.get(orientationIndex);
            if (!orientation.quaternion()) continue;

            double gpsHeading = Math.atan2(east, north);
            plusY.add(gpsHeading, headingOfRotatedAxis(orientation, 0.0, 1.0, 0.0));
            minusY.add(gpsHeading, headingOfRotatedAxis(orientation, 0.0, -1.0, 0.0));
            plusX.add(gpsHeading, headingOfRotatedAxis(orientation, 1.0, 0.0, 0.0));
            minusX.add(gpsHeading, headingOfRotatedAxis(orientation, -1.0, 0.0, 0.0));
        }

        AxisStats best = best(plusY, minusY, plusX, minusX);
        if (best.count == 0) return "no usable moving GPS/orientation overlap for attitude validation";

        RawOrientation first = orientations.get(firstOrientationAtOrAfter(orientations, startTimeNanos));
        double[] up = rotate(first, 0.0, 0.0, 1.0);
        double[] forward = axisVector(best.name);
        double[] bestForward = rotate(first, forward[0], forward[1], forward[2]);

        return String.format(Locale.ROOT,
                "best forward axis=%s, mean heading error=%.1f deg over %d samples; first up vector=(%.3f, %.3f, %.3f), first best-forward vector=(%.3f, %.3f, %.3f)",
                best.name, Math.toDegrees(best.meanAbsError()), best.count,
                up[0], up[1], up[2], bestForward[0], bestForward[1], bestForward[2]);
    }

    private AxisStats best(AxisStats... stats) {
        AxisStats best = stats[0];
        for (AxisStats stat : stats) {
            if (stat.count > 0 && (best.count == 0 || stat.meanAbsError() < best.meanAbsError())) best = stat;
        }
        return best;
    }

    private double[] axisVector(String name) {
        return switch (name) {
            case "device -Y forward" -> new double[]{0.0, -1.0, 0.0};
            case "device +X forward" -> new double[]{1.0, 0.0, 0.0};
            case "device -X forward" -> new double[]{-1.0, 0.0, 0.0};
            default -> new double[]{0.0, 1.0, 0.0};
        };
    }

    private double headingOfRotatedAxis(RawOrientation orientation, double x, double y, double z) {
        double[] rotated = rotate(orientation, x, y, z);
        return Math.atan2(rotated[0], rotated[1]);
    }

    private double[] rotate(RawOrientation orientation, double vx, double vy, double vz) {
        double qx = orientation.x();
        double qy = orientation.y();
        double qz = orientation.z();
        double qw = orientation.w();
        double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (norm == 0.0) return new double[]{vx, vy, vz};
        qx /= norm;
        qy /= norm;
        qz /= norm;
        qw /= norm;

        double tx = 2.0 * (qy * vz - qz * vy);
        double ty = 2.0 * (qz * vx - qx * vz);
        double tz = 2.0 * (qx * vy - qy * vx);

        double rx = vx + qw * tx + (qy * tz - qz * ty);
        double ry = vy + qw * ty + (qz * tx - qx * tz);
        double rz = vz + qw * tz + (qx * ty - qy * tx);
        return new double[]{rx, ry, rz};
    }

    private int nearestOrientationIndex(List<RawOrientation> orientations, int currentIndex, long timeNanos) {
        int index = Math.max(0, Math.min(currentIndex, orientations.size() - 1));
        while (index + 1 < orientations.size()
                && Math.abs(orientations.get(index + 1).timeNanos() - timeNanos) <= Math.abs(orientations.get(index).timeNanos() - timeNanos)) {
            index++;
        }
        return index;
    }

    private int firstOrientationAtOrAfter(List<RawOrientation> orientations, long timeNanos) {
        int low = 0;
        int high = orientations.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (orientations.get(mid).timeNanos() < timeNanos) low = mid + 1;
            else high = mid;
        }
        return Math.min(low, orientations.size() - 1);
    }

    private double eastMeters(RawLocation previous, RawLocation current) {
        double referenceLatitude = Math.toRadians(previous.latitude());
        return Math.toRadians(current.longitude() - previous.longitude()) * EARTH_RADIUS_METERS * Math.cos(referenceLatitude);
    }

    private double northMeters(RawLocation previous, RawLocation current) {
        return Math.toRadians(current.latitude() - previous.latitude()) * EARTH_RADIUS_METERS;
    }

    private double angleDiff(double a, double b) {
        double diff = a - b;
        while (diff > Math.PI) diff -= Math.PI * 2.0;
        while (diff < -Math.PI) diff += Math.PI * 2.0;
        return diff;
    }

    private final class AxisStats {
        private final String name;
        private int count;
        private double sumAbsError;

        private AxisStats(String name) {
            this.name = name;
        }

        private void add(double gpsHeading, double orientationHeading) {
            double error = Math.abs(angleDiff(gpsHeading, orientationHeading));
            if (Double.isFinite(error)) {
                count++;
                sumAbsError += error;
            }
        }

        private double meanAbsError() {
            return count == 0 ? Double.POSITIVE_INFINITY : sumAbsError / count;
        }
    }
}
