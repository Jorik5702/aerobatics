package ch.flighttrack.tracks;

final class OrientationMath {
    private OrientationMath() {
    }

    static double selectedForwardHeading(RawOrientation orientation) {
        if (orientation == null || !orientation.quaternion()) return Double.NaN;
        double[] forward = rotate(orientation, PhoneMount.FORWARD_X, PhoneMount.FORWARD_Y, PhoneMount.FORWARD_Z);
        return Math.atan2(forward[0], forward[1]);
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
}
