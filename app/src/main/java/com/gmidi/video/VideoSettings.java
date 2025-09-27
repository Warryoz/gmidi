package com.gmidi.video;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Mutable settings for the ffmpeg encoder. The controller updates this bean from the settings
 * dialog, and the {@link VideoRecorder} reads it when starting a new capture.
 */
public class VideoSettings {

    private Path outputDirectory = Paths.get("recordings");
    private int width = 1280;
    private int height = 720;
    private int fps = 60;
    private int crf = 20;
    private String preset = "veryfast";
    private Path ffmpegExecutable;

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getFps() {
        return fps;
    }

    public void setFps(int fps) {
        this.fps = fps;
    }

    public int getCrf() {
        return crf;
    }

    public void setCrf(int crf) {
        this.crf = crf;
    }

    public String getPreset() {
        return preset;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    public Path getFfmpegExecutable() {
        return ffmpegExecutable;
    }

    public void setFfmpegExecutable(Path ffmpegExecutable) {
        this.ffmpegExecutable = ffmpegExecutable;
    }
}
