package recorder.params;

import org.monte.media.Format;

import java.awt.*;
import java.io.File;

/**
 * Copyright (c) Nikolay Soloviev. All rights reserved.
 * @author Nikolay Soloviev <psolberger@gmail.com>
 */
public class RecorderParams {
    /**
     * Defines the area of the screen that shall be captured.
     */
    protected Rectangle captureArea = null;
    /**
     * The file format "AVI" or "QuickTime".
     */
    protected Format fileFormat = null;
    /**
     * The video format for screen capture.
     */
    protected Format screenFormat = null;
    /**
     * The audio format for audio capture. Specify null if
     * you don't want audio capture.
     */
    protected Format audioFormat = null;
    /**
     * Where to store the movie
     */
    protected File movieFolder = null;

    public RecorderParams() {

    }

    public Rectangle getCaptureArea() {
        return captureArea;
    }

    public RecorderParams setCaptureArea(Rectangle captureArea) {
        this.captureArea = captureArea;
        return this;
    }

    public Format getFileFormat() {
        return fileFormat;
    }

    public RecorderParams setFileFormat(Format fileFormat) {
        this.fileFormat = fileFormat;
        return this;
    }

    public Format getScreenFormat() {
        return screenFormat;
    }

    public RecorderParams setScreenFormat(Format screenFormat) {
        this.screenFormat = screenFormat;
        return this;
    }

    public Format getAudioFormat() {
        return audioFormat;
    }

    public RecorderParams setAudioFormat(Format audioFormat) {
        this.audioFormat = audioFormat;
        return this;
    }

    public File getMovieFolder() {
        return movieFolder;
    }

    public RecorderParams setMovieFolder(File movieFolder) {
        this.movieFolder = movieFolder;
        return this;
    }
}
