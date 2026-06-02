# FlightTrack

FlightTrack is a Java 25 / Maven project for reconstructing and visualising the 3D flight path of a glider during aerobatics.

The current application shell provides a minimal LWJGL 3 window with an OpenGL 4.1 core-profile context and a cornflower-blue clear-screen render loop. Flight data parsing, trajectory rendering, camera controls, and aerobatic analysis will be added later.

## Relevant links

- [Sensor Logger on the App Store](https://apps.apple.com/de/app/sensor-logger/id1531582925)
- [Awesome Sensor Logger](https://github.com/tszheichoi/awesome-sensor-logger/)

## Requirements

- JDK 25
- Maven is optional when using the included Maven Wrapper

## Build

```bash
./mvnw compile
```

## Run

```bash
./mvnw exec:java
```

The application opens a resizable `1280x720` window. On macOS, the wrapper starts the LWJGL application with `-XstartOnFirstThread`.

## Track loading menu

Place Sensor Logger exports under `tracks/<track-name>/`, for example:

```text
tracks/2025-10-18_12-30-13/
```

At runtime:

- `L` loads `tracks/2025-10-18_12-30-13` if present, otherwise the first available track directory.
- `R` rescans the `tracks/` directory.
- `Esc` closes the application.

The current step loads and summarises CSV data only. 3D rendering of the flight path will be implemented later.

## Project structure

```text
.
├── .mvn/wrapper/maven-wrapper.properties
├── mvnw
├── mvnw.cmd
├── pom.xml
└── src
    ├── main
    │   ├── java/ch/flighttrack/FlightTrackApp.java
    │   ├── java/ch/flighttrack/app/TrackMenuState.java
    │   ├── java/ch/flighttrack/tracks/
    │   └── resources/application.properties
    └── test
        ├── java/ch/flighttrack/.gitkeep
        └── resources/.gitkeep
```

## Notes

The GLFW window requires a graphical desktop session. Headless CI should run `./mvnw compile` or `./mvnw test`; window-opening tests can be added later with a dedicated headless OpenGL setup.
