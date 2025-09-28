module com.gmidi {
    requires java.desktop;
    requires javafx.controls;
    requires javafx.graphics;

    exports com.gmidi;
    exports com.gmidi.midi;
    exports com.gmidi.ui;
    exports com.gmidi.session;
    exports com.gmidi.video;
}
