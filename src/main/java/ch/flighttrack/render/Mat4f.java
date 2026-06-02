package ch.flighttrack.render;

public final class Mat4f {
    private final float[] values = new float[16];

    private Mat4f() {
    }

    public static Mat4f identity() {
        Mat4f matrix = new Mat4f();
        matrix.values[0] = 1.0f;
        matrix.values[5] = 1.0f;
        matrix.values[10] = 1.0f;
        matrix.values[15] = 1.0f;
        return matrix;
    }

    public static Mat4f perspective(float fovRadians, float aspect, float near, float far) {
        Mat4f matrix = new Mat4f();
        float tanHalfFov = (float) Math.tan(fovRadians / 2.0f);
        matrix.values[0] = 1.0f / (aspect * tanHalfFov);
        matrix.values[5] = 1.0f / tanHalfFov;
        matrix.values[10] = -(far + near) / (far - near);
        matrix.values[11] = -1.0f;
        matrix.values[14] = -(2.0f * far * near) / (far - near);
        return matrix;
    }

    public static Mat4f lookAt(float eyeX, float eyeY, float eyeZ,
                               float centerX, float centerY, float centerZ,
                               float upX, float upY, float upZ) {
        Vec3 forward = new Vec3(centerX - eyeX, centerY - eyeY, centerZ - eyeZ).normalised();
        Vec3 up = new Vec3(upX, upY, upZ).normalised();
        Vec3 side = forward.cross(up).normalised();
        Vec3 realUp = side.cross(forward);

        Mat4f matrix = identity();
        matrix.values[0] = side.x;
        matrix.values[4] = side.y;
        matrix.values[8] = side.z;
        matrix.values[1] = realUp.x;
        matrix.values[5] = realUp.y;
        matrix.values[9] = realUp.z;
        matrix.values[2] = -forward.x;
        matrix.values[6] = -forward.y;
        matrix.values[10] = -forward.z;
        matrix.values[12] = -side.dot(new Vec3(eyeX, eyeY, eyeZ));
        matrix.values[13] = -realUp.dot(new Vec3(eyeX, eyeY, eyeZ));
        matrix.values[14] = forward.dot(new Vec3(eyeX, eyeY, eyeZ));
        return matrix;
    }

    public Mat4f multiply(Mat4f other) {
        Mat4f result = new Mat4f();
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int i = 0; i < 4; i++) {
                    sum += values[i * 4 + row] * other.values[column * 4 + i];
                }
                result.values[column * 4 + row] = sum;
            }
        }
        return result;
    }

    public float[] values() {
        return values.clone();
    }

    private record Vec3(float x, float y, float z) {
        Vec3 cross(Vec3 other) {
            return new Vec3(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x);
        }

        float dot(Vec3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        Vec3 normalised() {
            float length = (float) Math.sqrt(dot(this));
            if (length == 0.0f) {
                return new Vec3(0.0f, 0.0f, 0.0f);
            }
            return new Vec3(x / length, y / length, z / length);
        }
    }
}
