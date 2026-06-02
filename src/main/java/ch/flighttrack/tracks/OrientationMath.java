package ch.flighttrack.tracks;

final class OrientationMath {
    private OrientationMath() {
    }

    static double selectedForwardHeading(RawOrientation orientation) {
        double[] forward = selectedForwardVector(orientation);
        if (forward == null) return Double.NaN;
        return Math.atan2(forward[0], forward[1]);
    }

    static double selectedForwardPitch(RawOrientation orientation) {
        double[] forward = selectedForwardVector(orientation);
        if (forward == null) return Double.NaN;
        double horizontal = Math.hypot(forward[0], forward[1]);
        return Math.atan2(forward[2], horizontal);
    }

    static double selectedRoll(RawOrientation orientation) {
        double[] forward = selectedForwardVector(orientation);
        double[] right = selectedRightVector(orientation);
        if (forward == null || right == null) return Double.NaN;

        normalize(forward);
        normalize(right);
        double[] worldUp = new double[]{0.0, 0.0, 1.0};
        double[] levelRight = cross(forward, worldUp);
        if (length(levelRight) < 1.0e-6) return Double.NaN;
        normalize(levelRight);

        double[] levelUp = cross(levelRight, forward);
        normalize(levelUp);
        double rightOnLevelRight = dot(right, levelRight);
        double rightOnLevelUp = dot(right, levelUp);
        return Math.atan2(rightOnLevelUp, rightOnLevelRight);
    }

    static double[] selectedForwardVector(RawOrientation orientation) {
        if (orientation == null || !orientation.quaternion()) return null;
        return rotate(orientation, PhoneMount.FORWARD_X, PhoneMount.FORWARD_Y, PhoneMount.FORWARD_Z);
    }

    static double[] selectedRightVector(RawOrientation orientation) {
        if (orientation == null || !orientation.quaternion()) return null;
        return rotate(orientation, PhoneMount.RIGHT_X, PhoneMount.RIGHT_Y, PhoneMount.RIGHT_Z);
    }

    static double[] rotate(RawOrientation orientation, double vx, double vy, double vz) {
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

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double length(double[] v) {
        return Math.sqrt(dot(v, v));
    }

    private static void normalize(double[] v) {
        double length = length(v);
        if (length == 0.0) return;
        v[0] /= length;
        v[1] /= length;
        v[2] /= length;
    }
}
