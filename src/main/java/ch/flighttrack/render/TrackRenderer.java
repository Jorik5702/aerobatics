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
    private int originVao;
    private int originVbo;
    private int pointCount;
    private float trackScale = 1.0f;
    private float centerX;
    private float centerY;
    private float centerZ;

    public void init() {
        glEnable(GL_DEPTH_TEST);
        program = createProgram();
        mvpUniform = glGetUniformLocation(program, "uMvp");
        colorUniform = glGetUniformLocation(program, "uColor");
        trackVao = glGenVertexArrays();
        trackVbo = glGenBuffers();
        axesVao = glGenVertexArrays();
        axesVbo = glGenBuffers();
        originVao = glGenVertexArrays();
        originVbo = glGenBuffers();
        uploadAxes();
        uploadOrigin();
    }

    public void uploadTrack(List<TrackPoint> points) {
        pointCount = points.size();
        if (points.isEmpty()) {
            return;
        }

        Bounds bounds = Bounds.from(points);
        centerX = (bounds.minX + bounds.maxX) * 0.5f;
        centerY = (bounds.minY + bounds.maxY) * 0.5f;
        centerZ = (bounds.minZ + bounds.maxZ) * 0.5f;
        float maxSpan = Math.max(Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY), bounds.maxZ - bounds.minZ);
        trackScale = maxSpan <= 0.0f ? 1.0f : 2.5f / maxSpan;

        FloatBuffer vertices = BufferUtils.createFloatBuffer(points.size() * 3);
        for (TrackPoint point : points) {
            vertices.put((float) ((point.xMeters() - centerX) * trackScale));
            vertices.put((float) ((point.zMeters() - centerZ) * trackScale));
            vertices.put((float) (-(point.yMeters() - centerY) * trackScale));
        }
        vertices.flip();

        glBindVertexArray(trackVao);
        glBindBuffer(GL_ARRAY_BUFFER, trackVbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    public void render(int width, int height, float yaw, float pitch, float distance) {
        float aspect = height == 0 ? 1.0f : (float) width / (float) height;
        Mat4f projection = Mat4f.perspective((float) Math.toRadians(55.0), aspect, 0.01f, 100.0f);
        float cosPitch = (float) Math.cos(pitch);
        float eyeX = (float) (Math.sin(yaw) * cosPitch * distance);
        float eyeY = (float) (Math.sin(pitch) * distance);
        float eyeZ = (float) (Math.cos(yaw) * cosPitch * distance);
        Mat4f view = Mat4f.lookAt(eyeX, eyeY, eyeZ, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        float[] mvp = projection.multiply(view).values();

        glUseProgram(program);
        glUniformMatrix4fv(mvpUniform, false, mvp);

        glBindVertexArray(axesVao);
        glLineWidth(2.0f);
        glUniform3f(colorUniform, 0.7f, 0.7f, 0.7f);
        glDrawArrays(GL_LINES, 0, 6);

        glBindVertexArray(originVao);
        glPointSize(8.0f);
        glUniform3f(colorUniform, 1.0f, 0.95f, 0.2f);
        glDrawArrays(GL_POINTS, 0, 1);

        if (pointCount > 1) {
            glBindVertexArray(trackVao);
            glLineWidth(3.0f);
            glUniform3f(colorUniform, 1.0f, 0.35f, 0.08f);
            glDrawArrays(GL_LINE_STRIP, 0, pointCount);
        }

        glBindVertexArray(0);
        glUseProgram(0);
    }

    public void cleanup() {
        if (trackVbo != 0) {
            glDeleteBuffers(trackVbo);
        }
        if (axesVbo != 0) {
            glDeleteBuffers(axesVbo);
        }
        if (originVbo != 0) {
            glDeleteBuffers(originVbo);
        }
        if (trackVao != 0) {
            glDeleteVertexArrays(trackVao);
        }
        if (axesVao != 0) {
            glDeleteVertexArrays(axesVao);
        }
        if (originVao != 0) {
            glDeleteVertexArrays(originVao);
        }
        if (program != 0) {
            glDeleteProgram(program);
        }
    }

    private void uploadAxes() {
        float[] axes = {
                -2.0f, 0.0f, 0.0f, 2.0f, 0.0f, 0.0f,
                0.0f, -2.0f, 0.0f, 0.0f, 2.0f, 0.0f,
                0.0f, 0.0f, -2.0f, 0.0f, 0.0f, 2.0f
        };
        FloatBuffer vertices = BufferUtils.createFloatBuffer(axes.length);
        vertices.put(axes).flip();
        glBindVertexArray(axesVao);
        glBindBuffer(GL_ARRAY_BUFFER, axesVbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    private void uploadOrigin() {
        FloatBuffer vertices = BufferUtils.createFloatBuffer(3);
        vertices.put(0.0f).put(0.0f).put(0.0f).flip();
        glBindVertexArray(originVao);
        glBindBuffer(GL_ARRAY_BUFFER, originVbo);
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
            float minX = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
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
