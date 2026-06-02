package ch.flighttrack.tracks;

final class PhoneMount {
    static final String FORWARD_AXIS = "device -X";
    static final double FORWARD_X = -1.0;
    static final double FORWARD_Y = 0.0;
    static final double FORWARD_Z = 0.0;

    static final String RIGHT_AXIS = "device +Y";
    static final double RIGHT_X = 0.0;
    static final double RIGHT_Y = 1.0;
    static final double RIGHT_Z = 0.0;

    private PhoneMount() {
    }
}
