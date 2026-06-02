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

The application opens a resizable `1280x720` window titled `FlightTrack` and clears the screen to cornflower blue.

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
    │   └── resources/application.properties
    └── test
        ├── java/ch/flighttrack/.gitkeep
        └── resources/.gitkeep
```

## Notes

The GLFW window requires a graphical desktop session. Headless CI should run `./mvnw compile` or `./mvnw test`; window-opening tests can be added later with a dedicated headless OpenGL setup.
