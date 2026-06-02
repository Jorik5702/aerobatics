package ch.flighttrack;

import ch.flighttrack.app.AnimationPanel;
import ch.flighttrack.app.TrackMenuState;
import ch.flighttrack.render.TrackRenderer;
import ch.flighttrack.tracks.LoadedTrackData;
import ch.flighttrack.tracks.SensorFileSummary;
import ch.flighttrack.tracks.SensorLoggerTrackLoader;
import ch.flighttrack.tracks.TrackPoint;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowCloseCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glViewport;

public final class FlightTrackApp {
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final String WINDOW_TITLE = "FlightTrack";

    private final SensorLoggerTrackLoader trackLoader = new SensorLoggerTrackLoader();
    private final TrackMenuState menuState = new TrackMenuState();
    private final TrackRenderer trackRenderer = new TrackRenderer();
    private final AnimationPanel animationPanel = new AnimationPanel();

    private Optional<LoadedTrackData> loadedTrack = Optional.empty();
    private float cameraYaw = (float) Math.toRadians(45.0);
    private float cameraPitch = (float) Math.toRadians(25.0);
    private float cameraDistance = 4.4f;
    private float panX;
    private float panY;
    private boolean rotating;
    private boolean panning;
    private double lastMouseX;
    private double lastMouseY;
    private double lastFrameTime;

    private long window;
    private GLFWErrorCallback errorCallback;

    public static void main(String[] args) {
        new FlightTrackApp().run();
    }

    private void run() {
        try {
            init();
            loop();
        } finally {
            cleanup();
            System.exit(0);
        }
    }

    private void init() {
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errorCallback);

        if (!glfwInit()) throw new IllegalStateException("Unable to initialise GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE, MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) throw new IllegalStateException("Unable to create GLFW window");

        glfwSetWindowCloseCallback(window, ignored -> glfwSetWindowShouldClose(window, true));
        installInputCallbacks();

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        trackRenderer.init();
        animationPanel.show();

        System.out.println("OpenGL version: " + GL11.glGetString(GL11.GL_VERSION));
        rescanTracks();
        updateWindowTitle();
        printMenu();
        lastFrameTime = glfwGetTime();
    }

    private void installInputCallbacks() {
        glfwSetKeyCallback(window, (ignoredWindow, key, scancode, action, mods) -> {
            if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
            if (key == GLFW_KEY_ESCAPE) {
                glfwSetWindowShouldClose(window, true);
            } else if (key == GLFW_KEY_L && action == GLFW_PRESS) {
                loadDefaultTrack();
            } else if (key == GLFW_KEY_R && action == GLFW_PRESS) {
                rescanTracks();
            } else if (key == GLFW_KEY_LEFT) {
                cameraYaw -= 0.08f;
            } else if (key == GLFW_KEY_RIGHT) {
                cameraYaw += 0.08f;
            } else if (key == GLFW_KEY_UP) {
                cameraPitch = clamp(cameraPitch + 0.06f, (float) Math.toRadians(-85.0), (float) Math.toRadians(85.0));
            } else if (key == GLFW_KEY_DOWN) {
                cameraPitch = clamp(cameraPitch - 0.06f, (float) Math.toRadians(-85.0), (float) Math.toRadians(85.0));
            } else if (key == GLFW_KEY_EQUAL || key == GLFW_KEY_KP_ADD) {
                zoom(0.90f);
            } else if (key == GLFW_KEY_MINUS || key == GLFW_KEY_KP_SUBTRACT) {
                zoom(1.10f);
            }
        });

        glfwSetMouseButtonCallback(window, (ignoredWindow, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) rotating = action == GLFW_PRESS;
            if (button == GLFW_MOUSE_BUTTON_RIGHT || button == GLFW_MOUSE_BUTTON_MIDDLE) panning = action == GLFW_PRESS;
            if (action == GLFW_RELEASE) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) rotating = false;
                if (button == GLFW_MOUSE_BUTTON_RIGHT || button == GLFW_MOUSE_BUTTON_MIDDLE) panning = false;
            }
        });

        glfwSetCursorPosCallback(window, (ignoredWindow, x, y) -> {
            double dx = x - lastMouseX;
            double dy = y - lastMouseY;
            lastMouseX = x;
            lastMouseY = y;
            if (rotating) {
                cameraYaw += (float) dx * 0.006f;
                cameraPitch = clamp(cameraPitch + (float) dy * 0.006f, (float) Math.toRadians(-85.0), (float) Math.toRadians(85.0));
            } else if (panning) {
                float panSpeed = cameraDistance * 0.0015f;
                panX -= (float) dx * panSpeed;
                panY += (float) dy * panSpeed;
            }
        });

        glfwSetScrollCallback(window, (ignoredWindow, xOffset, yOffset) -> zoom((float) Math.pow(0.88, yOffset)));
    }

    private void loop() {
        glClearColor(0.04f, 0.06f, 0.09f, 1.0f);
        int[] width = new int[1];
        int[] height = new int[1];

        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            double deltaSeconds = now - lastFrameTime;
            lastFrameTime = now;
            animationPanel.update(deltaSeconds);

            int currentIndex = animationPanel.currentIndex();
            loadedTrack.ifPresent(track -> trackRenderer.uploadPlaneForIndex(track.points(), currentIndex));

            glfwGetFramebufferSize(window, width, height);
            glViewport(0, 0, width[0], height[0]);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            trackRenderer.render(width[0], height[0], cameraYaw, cameraPitch, cameraDistance, panX, panY, currentIndex);
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void rescanTracks() {
        try {
            List<Path> tracks = trackLoader.discoverTracks();
            menuState.setAvailableTracks(tracks);
            menuState.setStatusMessage(tracks.isEmpty()
                    ? "No tracks found under ./tracks. Add Sensor Logger exports there and press R."
                    : "Found " + tracks.size() + " track directory/directories. Press L to load the default track.");
        } catch (IOException exception) {
            menuState.setStatusMessage("Failed to scan tracks: " + exception.getMessage());
        }
        updateWindowTitle();
        printMenu();
    }

    private void loadDefaultTrack() {
        try {
            Path trackDirectory = trackLoader.defaultTrackDirectory()
                    .orElseThrow(() -> new IOException("No track directories found under ./tracks"));
            LoadedTrackData detailedTrack = trackLoader.loadDetailedTrack(trackDirectory);
            loadedTrack = Optional.of(detailedTrack);
            menuState.setLoadedTrack(detailedTrack.summary());
            trackRenderer.uploadTrack(detailedTrack.points());
            animationPanel.setTrack(detailedTrack);
            fitCameraToTrack();
            updateWindowTitle();
            printTrackDetails(detailedTrack);
        } catch (IOException exception) {
            menuState.setStatusMessage("Failed to load track: " + exception.getMessage());
            updateWindowTitle();
            printMenu();
        }
    }

    private void fitCameraToTrack() {
        panX = 0.0f;
        panY = 0.0f;
        cameraDistance = clamp(trackRenderer.modelRadius() * 2.1f, 2.2f, 30.0f);
    }

    private void zoom(float factor) {
        cameraDistance = clamp(cameraDistance * factor, 0.7f, 80.0f);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateWindowTitle() {
        if (window != MemoryUtil.NULL) glfwSetWindowTitle(window, menuState.windowTitle());
    }

    private void printMenu() {
        System.out.println();
        System.out.println("FlightTrack menu");
        System.out.println("  L             Load default track from tracks/2025-10-18_12-30-13, or first available track");
        System.out.println("  R             Rescan tracks directory");
        System.out.println("  Left drag     Rotate camera");
        System.out.println("  Right/middle drag  Pan camera");
        System.out.println("  Mouse wheel   Zoom camera");
        System.out.println("  Arrows, +/-   Keyboard camera controls");
        System.out.println("  Esc           Close application");
        System.out.println("Status: " + menuState.statusMessage());
        for (Path track : menuState.availableTracks()) System.out.println("  - " + track);
    }

    private void printTrackDetails(LoadedTrackData trackData) {
        System.out.println();
        System.out.println("Loaded track: " + trackData.summary().directory());
        System.out.println("Ground reference:");
        System.out.printf("  latitude %.8f%n", trackData.groundReference().latitude());
        System.out.printf("  longitude %.8f%n", trackData.groundReference().longitude());
        System.out.printf("  reference altitude %.2f m (lowest track altitude)%n", trackData.groundReference().barometricAltitudeMeters());
        System.out.println("Track points after stable-start trim: " + trackData.points().size());
        System.out.printf("Calculated track time: %.2f s%n", calculatedTrackSeconds(trackData));
        System.out.println("Moving points: " + trackData.movingPointCount());
        System.out.println("Sensor files: " + trackData.summary().sensorFiles().size());
        for (SensorFileSummary sensorFile : trackData.summary().sensorFiles()) {
            System.out.printf("  - %s: %d rows, columns=%s%n", sensorFile.name(), sensorFile.rows(), sensorFile.headers());
        }
        if (!trackData.metadata().isEmpty()) {
            System.out.println("Metadata:");
            trackData.metadata().forEach((key, value) -> System.out.println("  " + key + ": " + value));
        }
    }

    private double calculatedTrackSeconds(LoadedTrackData trackData) {
        List<TrackPoint> points = trackData.points();
        if (points.size() < 2) return 0.0;
        TrackPoint first = points.get(0);
        TrackPoint last = points.get(points.size() - 1);
        if (!Double.isNaN(first.secondsElapsed()) && !Double.isNaN(last.secondsElapsed())) {
            return Math.max(0.0, last.secondsElapsed() - first.secondsElapsed());
        }
        return Math.max(0L, last.timeNanos() - first.timeNanos()) / 1_000_000_000.0;
    }

    private void cleanup() {
        animationPanel.close();
        trackRenderer.cleanup();
        if (window != MemoryUtil.NULL) {
            glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
            window = MemoryUtil.NULL;
        }
        glfwTerminate();
        GLFWErrorCallback callback = glfwSetErrorCallback(null);
        if (callback != null) callback.free();
    }
}
