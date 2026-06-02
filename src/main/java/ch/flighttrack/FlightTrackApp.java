package ch.flighttrack;

import ch.flighttrack.app.TrackMenuState;
import ch.flighttrack.tracks.SensorFileSummary;
import ch.flighttrack.tracks.SensorLoggerTrackLoader;
import ch.flighttrack.tracks.TrackSummary;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
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

public final class FlightTrackApp {
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final String WINDOW_TITLE = "FlightTrack";

    private final SensorLoggerTrackLoader trackLoader = new SensorLoggerTrackLoader();
    private final TrackMenuState menuState = new TrackMenuState();

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
        }
    }

    private void init() {
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errorCallback);

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialise GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE, MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new IllegalStateException("Unable to create GLFW window");
        }

        glfwSetWindowCloseCallback(window, ignored -> glfwSetWindowShouldClose(window, true));
        glfwSetKeyCallback(window, (ignoredWindow, key, scancode, action, mods) -> {
            if (action != GLFW_PRESS) {
                return;
            }

            if (key == GLFW_KEY_ESCAPE) {
                glfwSetWindowShouldClose(window, true);
            } else if (key == GLFW_KEY_L) {
                loadDefaultTrack();
            } else if (key == GLFW_KEY_R) {
                rescanTracks();
            }
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        String glVersion = GL11.glGetString(GL11.GL_VERSION);
        System.out.println("OpenGL version: " + glVersion);

        rescanTracks();
        updateWindowTitle();
        printMenu();
    }

    private void loop() {
        glClearColor(0.39f, 0.58f, 0.93f, 1.0f);

        while (!glfwWindowShouldClose(window)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void rescanTracks() {
        try {
            List<Path> tracks = trackLoader.discoverTracks();
            menuState.setAvailableTracks(tracks);
            if (tracks.isEmpty()) {
                menuState.setStatusMessage("No tracks found under ./tracks. Add Sensor Logger exports there and press R.");
            } else {
                menuState.setStatusMessage("Found " + tracks.size() + " track directory/directories. Press L to load the default track.");
            }
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
            TrackSummary summary = trackLoader.load(trackDirectory);
            menuState.setLoadedTrack(summary);
            updateWindowTitle();
            printTrackSummary(summary);
        } catch (IOException exception) {
            menuState.setStatusMessage("Failed to load track: " + exception.getMessage());
            updateWindowTitle();
            printMenu();
        }
    }

    private void updateWindowTitle() {
        if (window != MemoryUtil.NULL) {
            glfwSetWindowTitle(window, menuState.windowTitle());
        }
    }

    private void printMenu() {
        System.out.println();
        System.out.println("FlightTrack menu");
        System.out.println("  L  Load default track from tracks/2025-10-18_12-30-13, or first available track");
        System.out.println("  R  Rescan tracks directory");
        System.out.println("  Esc  Close application");
        System.out.println("Status: " + menuState.statusMessage());
        for (Path track : menuState.availableTracks()) {
            System.out.println("  - " + track);
        }
    }

    private void printTrackSummary(TrackSummary summary) {
        System.out.println();
        System.out.println("Loaded track: " + summary.directory());
        System.out.println("Sensor files: " + summary.sensorFiles().size());
        for (SensorFileSummary sensorFile : summary.sensorFiles()) {
            System.out.printf("  - %s: %d rows, columns=%s%n", sensorFile.name(), sensorFile.rows(), sensorFile.headers());
        }

        summary.locationSummary().ifPresentOrElse(location -> {
            System.out.println("Location samples: " + location.samples());
            System.out.printf("Latitude: %.7f .. %.7f%n", location.minLatitude(), location.maxLatitude());
            System.out.printf("Longitude: %.7f .. %.7f%n", location.minLongitude(), location.maxLongitude());
            if (!Double.isNaN(location.minAltitude())) {
                System.out.printf("Altitude: %.2f .. %.2f m%n", location.minAltitude(), location.maxAltitude());
            }
        }, () -> System.out.println("No Location.csv summary available."));

        if (!summary.metadata().isEmpty()) {
            System.out.println("Metadata:");
            summary.metadata().forEach((key, value) -> System.out.println("  " + key + ": " + value));
        }
    }

    private void cleanup() {
        if (window != MemoryUtil.NULL) {
            glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
            window = MemoryUtil.NULL;
        }

        glfwTerminate();

        GLFWErrorCallback callback = glfwSetErrorCallback(null);
        if (callback != null) {
            callback.free();
        }
    }
}
