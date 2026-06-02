package ch.flighttrack.app;

import ch.flighttrack.tracks.LoadedTrackData;
import ch.flighttrack.tracks.TrackPoint;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class AnimationPanel {
    private static final int SLIDER_MAX = 10_000;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final Object lock = new Object();
    private final JFrame frame = new JFrame("FlightTrack animation");
    private final JLabel gpsLabel = new JLabel("GPS: -");
    private final JLabel speedLabel = new JLabel("Speed: -");
    private final JLabel heightLabel = new JLabel("Relative height: -");
    private final JLabel absoluteHeightLabel = new JLabel("Barometric height MSL: -");
    private final JLabel absoluteTimeLabel = new JLabel("Absolute time: -");
    private final JLabel relativeTimeLabel = new JLabel("Relative time: -");
    private final JSlider timeSlider = new JSlider(0, SLIDER_MAX, 0);
    private final JButton playButton = new JButton("Start");
    private final JComboBox<Integer> speedFactorBox = new JComboBox<>(new Integer[]{1, 2, 5, 10, 20, 50});

    private LoadedTrackData trackData;
    private double durationSeconds;
    private double currentSeconds;
    private int currentIndex;
    private boolean playing;
    private boolean internalSliderUpdate;

    public AnimationPanel() {
        JPanel values = new JPanel(new GridLayout(0, 1, 4, 4));
        values.add(gpsLabel);
        values.add(speedLabel);
        values.add(heightLabel);
        values.add(absoluteHeightLabel);
        values.add(absoluteTimeLabel);
        values.add(relativeTimeLabel);

        JPanel controls = new JPanel(new BorderLayout(6, 6));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 6));
        buttons.add(playButton);
        buttons.add(speedFactorBox);
        controls.add(timeSlider, BorderLayout.CENTER);
        controls.add(buttons, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.add(values, BorderLayout.CENTER);
        root.add(controls, BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setSize(460, 260);
        frame.setLocationByPlatform(true);
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        playButton.addActionListener(event -> togglePlaying());
        timeSlider.addChangeListener(event -> {
            if (internalSliderUpdate) return;
            synchronized (lock) {
                if (trackData == null || durationSeconds <= 0.0) return;
                currentSeconds = durationSeconds * timeSlider.getValue() / SLIDER_MAX;
                currentIndex = indexAt(currentSeconds);
                playing = false;
            }
            refreshUi();
        });
    }

    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    public void close() {
        SwingUtilities.invokeLater(frame::dispose);
    }

    public void setTrack(LoadedTrackData trackData) {
        synchronized (lock) {
            this.trackData = trackData;
            this.durationSeconds = calculateDuration(trackData.points());
            this.currentSeconds = 0.0;
            this.currentIndex = 0;
            this.playing = false;
        }
        refreshUi();
    }

    public void update(double deltaSeconds) {
        synchronized (lock) {
            if (!playing || trackData == null || durationSeconds <= 0.0) return;
            currentSeconds += deltaSeconds * speedFactor();
            if (currentSeconds >= durationSeconds) {
                currentSeconds = durationSeconds;
                playing = false;
            }
            currentIndex = indexAt(currentSeconds);
        }
        refreshUi();
    }

    public int currentIndex() {
        synchronized (lock) {
            return currentIndex;
        }
    }

    private void togglePlaying() {
        synchronized (lock) {
            if (trackData == null) return;
            if (currentSeconds >= durationSeconds) currentSeconds = 0.0;
            playing = !playing;
        }
        refreshUi();
    }

    private int speedFactor() {
        Object selected = speedFactorBox.getSelectedItem();
        return selected instanceof Integer factor ? factor : 1;
    }

    private int indexAt(double relativeSeconds) {
        if (trackData == null || trackData.points().isEmpty()) return 0;
        List<TrackPoint> points = trackData.points();
        for (int i = 0; i < points.size(); i++) {
            if (relativeSecondsAt(points, i) >= relativeSeconds) return i;
        }
        return points.size() - 1;
    }

    private double calculateDuration(List<TrackPoint> points) {
        if (points.size() < 2) return 0.0;
        return Math.max(0.0, relativeSecondsAt(points, points.size() - 1));
    }

    private double relativeSecondsAt(List<TrackPoint> points, int index) {
        TrackPoint first = points.get(0);
        TrackPoint point = points.get(index);
        if (!Double.isNaN(first.secondsElapsed()) && !Double.isNaN(point.secondsElapsed())) {
            return Math.max(0.0, point.secondsElapsed() - first.secondsElapsed());
        }
        return Math.max(0L, point.timeNanos() - first.timeNanos()) / 1_000_000_000.0;
    }

    private void refreshUi() {
        LoadedTrackData snapshot;
        int index;
        double seconds;
        double duration;
        boolean isPlaying;
        synchronized (lock) {
            snapshot = trackData;
            index = currentIndex;
            seconds = currentSeconds;
            duration = durationSeconds;
            isPlaying = playing;
        }

        SwingUtilities.invokeLater(() -> {
            playButton.setText(isPlaying ? "Stop" : "Start");
            if (snapshot == null || snapshot.points().isEmpty()) {
                gpsLabel.setText("GPS: -");
                speedLabel.setText("Speed: -");
                heightLabel.setText("Relative height: -");
                absoluteHeightLabel.setText("Barometric height MSL: -");
                absoluteTimeLabel.setText("Absolute time: -");
                relativeTimeLabel.setText("Relative time: -");
                return;
            }

            TrackPoint point = snapshot.points().get(Math.max(0, Math.min(index, snapshot.points().size() - 1)));
            gpsLabel.setText(String.format("GPS: %.7f, %.7f", point.latitude(), point.longitude()));
            speedLabel.setText(String.format("Speed: %.2f m/s", point.speedMetersPerSecond()));
            heightLabel.setText(String.format("Relative height: %.2f m", point.zMeters()));
            absoluteHeightLabel.setText(String.format("Barometric height MSL: %.2f m", point.barometricAltitudeMeters()));
            absoluteTimeLabel.setText("Absolute time: " + absoluteTime(point));
            relativeTimeLabel.setText(String.format("Relative time: %.2f / %.2f s", seconds, duration));
            internalSliderUpdate = true;
            timeSlider.setValue(duration <= 0.0 ? 0 : (int) Math.round(SLIDER_MAX * seconds / duration));
            internalSliderUpdate = false;
        });
    }

    private String absoluteTime(TrackPoint point) {
        if (point.timeNanos() <= 0) return "-";
        try {
            return TIME_FORMATTER.format(Instant.ofEpochSecond(0, point.timeNanos()));
        } catch (RuntimeException exception) {
            return Long.toString(point.timeNanos());
        }
    }
}
