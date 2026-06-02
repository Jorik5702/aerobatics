package ch.flighttrack.render;

import ch.flighttrack.tracks.TrackPoint;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_LINE_STRIP;
import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glLineWidth;
import static org.lwjgl.opengl.GL11.glPointSize;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glDeleteShader;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderi;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUniform3f;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

public final class TrackRenderer {
    private int program;
    private int mvpUniform;
    private int colorUniform;
    private int trackVao;
    private int trackVbo;
    private int axesVao;
    private int axesVbo;
    private int groundVao;
    private int groundVbo;
    private int originVao;
    private int originVbo;
    private int planeVao;
    private int planeVbo;
    private int pointCount;
    private int groundPointCount;
    private float trackScale = 1.0f;
    private float centerX;
    private float centerZ;
    private float modelRadius = 2.0f;

    public void init() {
        glEnable(GL_DEPTH_TEST);
        program = createProgram();
        mvpUniform = glGetUniformLocation(program, "uMvp");
        colorUniform = glGetUniformLocation(program, "uColor");
        trackVao = glGenVertexArrays();
        trackVbo = glGenBuffers();
        axesVao = glGenVertexArrays();
        axesVbo = glGenBuffers();
        groundVao = glGenVertexArrays();
        groundVbo = glGenBuffers();
        originVao = glGenVertexArrays();
        originVbo = glGenBuffers();
        planeVao = glGenVertexArrays();
        planeVbo = glGenBuffers();
        uploadAxes();
        uploadGround(2.2f);
        uploadOrigin();
        uploadPlane(0.0f, 0.0f, 0.0f, 0.0f);
    }

    public float modelRadius() {
        return modelRadius;
    }

    public void uploadTrack(List<TrackPoint> points) {
        pointCount = points.size();
        if (points.isEmpty()) return;

        Bounds bounds = Bounds.from(points);
        centerX = (bounds.minX + bounds.maxX) * 0.5f;
        centerZ = (bounds.minY + bounds.maxY) * 0.5f;
        float spanX = bounds.maxX - bounds.minX;
        float spanY = bounds.maxY - bounds.minY;
        float spanZ = bounds.maxZ - bounds.minZ;
        float maxSpan = Math.max(Math.max(spanX, spanY), spanZ);
        trackScale = maxSpan <= 0.0f ? 1.0f : 3.6f / maxSpan;
        modelRadius = Math.max(1.8f, Math.max(Math.max(spanX, spanY), spanZ) * trackScale * 0.65f);

        FloatBuffer vertices = BufferUtils.createFloatBuffer(points.size() * 3);
        for (TrackPoint point : points) {
            putTrackVertex(vertices, point);
        }
        vertices.flip();
        uploadBuffer(trackVao, trackVbo, vertices);
        uploadPlaneForIndex(points, 0);
    }

    public void uploadPlaneForIndex(List<TrackPoint> points, int currentIndex) {
        if (points.isEmpty()) return;
        int index = Math.max(0, Math.min(currentIndex, points.size() - 1));
        TrackPoint current = points.get(index);
        TrackPoint next = points.get(Math.min(index + 1, points.size() - 1));
        TrackPoint previous = points.get(Math.max(index - 1, 0));
        float x = transformX(current);
        float y = transformY(current);
        float z = transformZ(current);
        float dx = transformX(next) - transformX(previous);
        float dz = transformZ(next) - transformZ(previous);
        float heading = (float) Math.atan2(dx, -dz);
        uploadPlane(x, y, z, heading);
    }

    public void render(int width, int height, float yaw, float pitch, float distance, float panX, float panY, int currentIndex) {
        float aspect = height == 0 ? 1.0f : (float) width / (float) height;
        Mat4f projection = Mat4f.perspective((float) Math.toRadians(42.0), aspect, 0.01f, 100.0f);
        float cosPitch = (float) Math.cos(pitch);
        float eyeX = (float) (Math.sin(yaw) * cosPitch * distance) + panX;
        float eyeY = (float) (Math.sin(pitch) * distance) + panY;
        float eyeZ = (float) (Math.cos(yaw) * cosPitch * distance);
        Mat4f view = Mat4f.lookAt(eyeX, eyeY, eyeZ, panX, panY, 0.0f, 0.0f, 1.0f, 0.0f);
        FloatBuffer mvp = BufferUtils.createFloatBuffer(16);
        mvp.put(projection.multiply(view).values()).flip();

        glUseProgram(program);
        glUniformMatrix4fv(mvpUniform, false, mvp);

        glBindVertexArray(groundVao);
        glPointSize(2.0f);
        glUniform3f(colorUniform, 0.28f, 0.32f, 0.36f);
        glDrawArrays(GL_POINTS, 0, groundPointCount);

        glBindVertexArray(axesVao);
        glLineWidth(2.0f);
        glUniform3f(colorUniform, 0.45f, 0.48f, 0.52f);
        glDrawArrays(GL_LINES, 0, 6);

        glBindVertexArray(originVao);
        glPointSize(9.0f);
        glUniform3f(colorUniform, 1.0f, 0.95f, 0.2f);
        glDrawArrays(GL_POINTS, 0, 1);

        if (pointCount > 1) {
            int splitIndex = Math.max(0, Math.min(currentIndex, pointCount - 1));
            glBindVertexArray(trackVao);
            glLineWidth(3.0f);
            if (splitIndex > 0) {
                glUniform3f(colorUniform, 1.0f, 0.9f, 0.05f);
                glDrawArrays(GL_LINE_STRIP, 0, splitIndex + 1);
            }
            if (splitIndex < pointCount - 1) {
                glUniform3f(colorUniform, 0.45f, 0.45f, 0.45f);
                glDrawArrays(GL_LINE_STRIP, splitIndex, pointCount - splitIndex);
            }
        }

        glBindVertexArray(planeVao);
        glUniform3f(colorUniform, 0.1f, 0.75f, 1.0f);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        glBindVertexArray(0);
        glUseProgram(0);
    }

    public void cleanup() {
        if (trackVbo != 0) glDeleteBuffers(trackVbo);
        if (axesVbo != 0) glDeleteBuffers(axesVbo);
        if (groundVbo != 0) glDeleteBuffers(groundVbo);
        if (originVbo != 0) glDeleteBuffers(originVbo);
        if (planeVbo != 0) glDeleteBuffers(planeVbo);
        if (trackVao != 0) glDeleteVertexArrays(trackVao);
        if (axesVao != 0) glDeleteVertexArrays(axesVao);
        if (groundVao != 0) glDeleteVertexArrays(groundVao);
        if (originVao != 0) glDeleteVertexArrays(originVao);
        if (planeVao != 0) glDeleteVertexArrays(planeVao);
        if (program != 0) glDeleteProgram(program);
    }

    private void putTrackVertex(FloatBuffer vertices, TrackPoint point) {
        vertices.put(transformX(point));
        vertices.put(transformY(point));
        vertices.put(transformZ(point));
    }

    private float transformX(TrackPoint point) {
        return (float) ((point.xMeters() - centerX) * trackScale);
    }

    private float transformY(TrackPoint point) {
        return (float) (point.zMeters() * trackScale);
    }

    private float transformZ(TrackPoint point) {
        return (float) (-(point.yMeters() - centerZ) * trackScale);
    }

    private void uploadAxes() {
        float[] axes = {
                -2.2f, 0.0f, 0.0f, 2.2f, 0.0f, 0.0f,
                0.0f, 0.0f, -2.2f, 0.0f, 0.0f, 2.2f,
                0.0f, 0.0f, 0.0f, 0.0f, 2.2f, 0.0f
        };
        FloatBuffer vertices = BufferUtils.createFloatBuffer(axes.length);
        vertices.put(axes).flip();
        uploadBuffer(axesVao, axesVbo, vertices);
    }

    private void uploadGround(float halfSize) {
        float step = 0.22f;
        int pointsPerSide = (int) (halfSize * 2.0f / step) + 1;
        groundPointCount = pointsPerSide * pointsPerSide;
        FloatBuffer vertices = BufferUtils.createFloatBuffer(groundPointCount * 3);
        for (int xIndex = 0; xIndex < pointsPerSide; xIndex++) {
            float x = -halfSize + xIndex * step;
            for (int zIndex = 0; zIndex < pointsPerSide; zIndex++) {
                float z = -halfSize + zIndex * step;
                vertices.put(x).put(0.0f).put(z);
            }
        }
        vertices.flip();
        uploadBuffer(groundVao, groundVbo, vertices);
    }

    private void uploadOrigin() {
        FloatBuffer vertices = BufferUtils.createFloatBuffer(3);
        vertices.put(0.0f).put(0.0f).put(0.0f).flip();
        uploadBuffer(originVao, originVbo, vertices);
    }

    private void uploadPlane(float x, float y, float z, float heading) {
        float length = 0.12f;
        float width = 0.08f;
        float sin = (float) Math.sin(heading);
        float cos = (float) Math.cos(heading);
        float noseX = x + sin * length;
        float noseZ = z - cos * length;
        float leftX = x - cos * width - sin * length * 0.45f;
        float leftZ = z - sin * width + cos * length * 0.45f;
        float rightX = x + cos * width - sin * length * 0.45f;
        float rightZ = z + sin * width + cos * length * 0.45f;
        FloatBuffer vertices = BufferUtils.createFloatBuffer(9);
        vertices.put(noseX).put(y).put(noseZ);
        vertices.put(leftX).put(y).put(leftZ);
        vertices.put(rightX).put(y).put(rightZ);
        vertices.flip();
        uploadBuffer(planeVao, planeVbo, vertices);
    }

    private void uploadBuffer(int vao, int vbo, FloatBuffer vertices) {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    private int createProgram() {
        int vertexShader = compileShader(GL_VERTEX_SHADER, """
                #version 410 core
                layout (location = 0) in vec3 aPosition;
                uniform mat4 uMvp;
                void main() {
                    gl_Position = uMvp * vec4(aPosition, 1.0);
                }
                """);
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, """
                #version 410 core
                out vec4 fragColor;
                uniform vec3 uColor;
                void main() {
                    fragColor = vec4(uColor, 1.0);
                }
                """);
        int createdProgram = glCreateProgram();
        glAttachShader(createdProgram, vertexShader);
        glAttachShader(createdProgram, fragmentShader);
        glLinkProgram(createdProgram);
        if (glGetProgrami(createdProgram, GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Unable to link shader program: " + glGetProgramInfoLog(createdProgram));
        }
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return createdProgram;
    }

    private int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("Unable to compile shader: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private record Bounds(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
        static Bounds from(List<TrackPoint> points) {
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (TrackPoint point : points) {
                minX = Math.min(minX, (float) point.xMeters());
                maxX = Math.max(maxX, (float) point.xMeters());
                minY = Math.min(minY, (float) point.yMeters());
                maxY = Math.max(maxY, (float) point.yMeters());
                minZ = Math.min(minZ, (float) point.zMeters());
                maxZ = Math.max(maxZ, (float) point.zMeters());
            }
            return new Bounds(minX, maxX, minY, maxY, minZ, maxZ);
        }
    }
}
