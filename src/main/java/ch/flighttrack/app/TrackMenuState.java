package ch.flighttrack.app;

import ch.flighttrack.tracks.TrackSummary;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class TrackMenuState {
    private List<Path> availableTracks = List.of();
    private Optional<TrackSummary> loadedTrack = Optional.empty();
    private String statusMessage = "Press L to load track data, R to rescan tracks.";

    public List<Path> availableTracks() {
        return availableTracks;
    }

    public void setAvailableTracks(List<Path> availableTracks) {
        this.availableTracks = List.copyOf(availableTracks);
    }

    public Optional<TrackSummary> loadedTrack() {
        return loadedTrack;
    }

    public void setLoadedTrack(TrackSummary loadedTrack) {
        this.loadedTrack = Optional.of(loadedTrack);
        this.statusMessage = "Loaded " + loadedTrack.displayName()
                + " (" + loadedTrack.sensorFiles().size() + " CSV files, "
                + loadedTrack.totalSamples() + " rows)";
    }

    public String statusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public String windowTitle() {
        return loadedTrack
                .map(track -> "FlightTrack - " + track.displayName() + " loaded")
                .orElse("FlightTrack - Press L to load track data");
    }
}
