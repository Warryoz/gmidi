module com.gmidi {
    requires java.desktop;
    requires javafx.controls;
    requires javafx.graphics;
    requires jdk.synthesizer;

    exports com.gmidi;
    exports com.gmidi.midi;
    exports com.gmidi.ui;
    exports com.gmidi.session;
    exports com.gmidi.video;
}
