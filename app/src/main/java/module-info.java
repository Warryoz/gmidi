module com.gmidi {
    requires java.desktop;
    requires javafx.controls;
    requires javafx.graphics;

    exports com.gmidi;
    exports com.gmidi.cli;
    exports com.gmidi.cli.interaction;
    exports com.gmidi.midi;
    exports com.gmidi.recorder;

    opens com.gmidi.ui to javafx.graphics;
}
