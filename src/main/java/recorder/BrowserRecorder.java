package recorder;

/*
 * @(#)ScreenRecorder.java  1.0  2011-03-19
 *
 * Copyright (c) 2011 Werner Randelshofer, Immensee, Switzerland.
 * All rights reserved.
 *
 * You may not use, copy or modify this file, except in compliance with the
 * license agreement you entered into with Werner Randelshofer.
 * For details see accompanying license terms.
 */

/**
 * {@code ScreenRecorder}.
 *
 * @author Werner Randelshofer
 * @version 1.0 2011-03-19 Created.
 */

/**
* Copyright (c) Nikolay Soloviev. All rights reserved.
* @author Nikolay Soloviev <psolberger@gmail.com>
*/

import org.monte.media.*;
import org.monte.media.FormatKeys.MediaType;
import org.monte.media.avi.AVIWriter;
import org.monte.media.beans.AbstractStateModel;
import org.monte.media.color.Colors;
import org.monte.media.converter.CodecChain;
import org.monte.media.converter.ScaleImageCodec;
import org.monte.media.math.Rational;
import org.monte.media.quicktime.QuickTimeWriter;
import recorder.params.RecorderParams;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.*;

import static java.lang.Math.max;
import static org.monte.media.AudioFormatKeys.EncodingKey;
import static org.monte.media.AudioFormatKeys.FrameRateKey;
import static org.monte.media.AudioFormatKeys.MediaTypeKey;
import static org.monte.media.AudioFormatKeys.MimeTypeKey;
import static org.monte.media.AudioFormatKeys.*;
import static org.monte.media.BufferFlag.SAME_DATA;
import static org.monte.media.VideoFormatKeys.*;

public class BrowserRecorder extends AbstractStateModel {

    public enum State {
        DONE, FAILED, RECORDING
    }
    private State state = State.DONE;
    private String stateMessage = null;
    /**
     * The file format. "AVI" or "QuickTime"
     */
    private Format fileFormat;
    /**
     * The input video format for screen capture.
     */
    private Format screenFormat;
    /**
     * The input and output format for audio capture.
     */
    private Format audioFormat;
    /**
     * The bounds of the graphics device that we capture.
     */
    private Rectangle captureArea;
    /**
     * The writer for the movie file.
     */
    private MovieWriter w;
    /**
     * The start time of the recording.
     */
    protected long recordingStartTime;
    /**
     * The stop time of the recording.
     */
    protected volatile long recordingStopTime;
    /**
     * The start time of the current movie file.
     */
    private long fileStartTime;
    /**
     * Timer for screen captures.
     */
    private ScheduledThreadPoolExecutor screenCaptureTimer;
    /**
     * Thread for audio capture.
     */
    private ScheduledThreadPoolExecutor audioCaptureTimer;
    /**
     * Thread for file writing.
     */
    private volatile Thread writerThread;
    /**
     * Object for thread synchronization.
     */
    private ArrayBlockingQueue<Buffer> writerQueue;
    /**
     * This codec encodes a video frame.
     */
    private Codec frameEncoder;
    /**
     * outputTime and ffrDuration are needed for conversion of the video stream
     * from variable frame rate to fixed frame rate.
     */
    // TODO FIXME - Do this with a CodecChain.
    private Rational outputTime;
    private Rational ffrDuration;
    private ArrayList<File> recordedFiles;
    /**
     * Id of the video track.
     */
    protected int videoTrack = 0;
    /**
     * Id of the audio track.
     */
    protected int audioTrack = 1;
    /**
     * The device from which screen captures are generated.
     */
    private AudioGrabber audioGrabber;
    private BrowserGrabber browserGrabber;
    private ScheduledFuture audioFuture;
    private ScheduledFuture screenFuture;
    private int browserProcessID;
    /**
     * Where to store the movie.
     */
    protected File movieFolder;
    private long maxRecordingTime = 60 * 60 * 1000;
    private long maxFileSize = Long.MAX_VALUE;
    /**
     * Audio mixer used for audio input. Set to null for default audio input.
     */
    private Mixer mixer;

    /**
     * Creates a browser recorder.
     *
     * @param recorderParams - instance of RecorderParams class
     */
    public BrowserRecorder(RecorderParams recorderParams) throws IOException, AWTException {
        this.fileFormat = recorderParams.getFileFormat();
        this.screenFormat = recorderParams.getScreenFormat();
        this.audioFormat = recorderParams.getAudioFormat();
        this.recordedFiles = new ArrayList<File>();
        this.captureArea = recorderParams.getCaptureArea();
        setMovieFolder(recorderParams.getMovieFolder());
    }

    /**
     * Set processID of windows, that needs to be recording.
     * Recording will go for all visible windows of that process.
     * @param processID - processID
     */
    public void setCaptureWindowProcessID(int processID) {
        this.browserProcessID = processID;
        if(browserGrabber != null) {
            browserGrabber.setWindowProcessID();
        }
    }

    public void setMovieFolder(File movieFolder) {
        this.movieFolder = movieFolder;
        if (this.movieFolder == null) {
            if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
                this.movieFolder = new File(System.getProperty("user.home") + File.separator + "Videos");
            } else {
                this.movieFolder = new File(System.getProperty("user.home") + File.separator + "Movies");
            }
        }
    }

    protected MovieWriter createMovieWriter() throws IOException {
        File f = createMovieFile(fileFormat);
        recordedFiles.add(f);

        MovieWriter mw = w = Registry.getInstance().getWriter(fileFormat, f);

        // Create the video encoder
        Rational videoRate = screenFormat.get(FrameRateKey);
        ffrDuration = videoRate.inverse();
        Format videoInputFormat = screenFormat.prepend(MediaTypeKey, MediaType.VIDEO,
                EncodingKey, ENCODING_BUFFERED_IMAGE,
                WidthKey, captureArea.width,
                HeightKey, captureArea.height,
                FrameRateKey, videoRate);
        Format videoOutputFormat = screenFormat.prepend(
                FrameRateKey, videoRate,
                MimeTypeKey, fileFormat.get(MimeTypeKey),
                WidthKey, captureArea.width,
                HeightKey, captureArea.height);

        videoTrack = w.addTrack(videoOutputFormat);
        if (audioFormat != null) {
            audioTrack = w.addTrack(audioFormat);
        }

        Codec encoder = Registry.getInstance().getEncoder(w.getFormat(videoTrack));
        if (encoder == null) {
            throw new IOException("No encoder for format " + w.getFormat(videoTrack));
        }
        frameEncoder = encoder;
        frameEncoder.setInputFormat(videoInputFormat);
        frameEncoder.setOutputFormat(videoOutputFormat);
        if (frameEncoder.getOutputFormat() == null) {
            throw new IOException("Unable to encode video frames in this output format:\n" + videoOutputFormat);
        }

        // If the capture area does not have the same dimensions as the
        // video format, create a codec chain which scales the image before
        // performing the frame encoding.
        if (!videoInputFormat.intersectKeys(WidthKey, HeightKey).matches(
                videoOutputFormat.intersectKeys(WidthKey, HeightKey))) {
            ScaleImageCodec sic = new ScaleImageCodec();
            sic.setInputFormat(videoInputFormat);
            sic.setOutputFormat(videoOutputFormat.intersectKeys(WidthKey, HeightKey).append(videoInputFormat));
            frameEncoder = new CodecChain(sic, frameEncoder);
        }

        // TODO FIXME - There should be no need for format-specific code.
        if (screenFormat.get(DepthKey) == 8) {
            if (w instanceof AVIWriter) {
                AVIWriter aviw = (AVIWriter) w;
                aviw.setPalette(videoTrack, Colors.createMacColors());
            } else if (w instanceof QuickTimeWriter) {
                QuickTimeWriter qtw = (QuickTimeWriter) w;
                qtw.setVideoColorTable(videoTrack, Colors.createMacColors());
            }
        }

        fileStartTime = System.currentTimeMillis();
        return mw;
    }

    /**
     * Returns a list of all files that the browser recorder created.
     */
    public java.util.List<File> getCreatedMovieFiles() {
        return Collections.unmodifiableList(recordedFiles);
    }

    /**
     * Creates a file for recording the movie. <p> This implementation creates a
     * file in the users "Video" folder on Windows, or in the users "Movies"
     * folders on Mac OS X. <p> You can override this method, if you would like
     * to create a movie file at a different location.
     *
     * @param fileFormat - format of file
     * @return the file
     * @throws IOException
     */
    protected File createMovieFile(Format fileFormat) throws IOException {
        if (!movieFolder.exists()) {
            if(!movieFolder.mkdirs()) {
                throw new IOException("Can't create directory " + movieFolder.getAbsolutePath());
            }
        } else if (!movieFolder.isDirectory()) {
            throw new IOException("\"" + movieFolder + "\" is not a directory.");
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_'at'_HH.mm.ss");

        return new File(movieFolder,//
                "BrowserRecording_" + dateFormat.format(new Date()) + "." + Registry.getInstance().getExtension(fileFormat));
    }

    /**
     * Returns the state of the recorder.
     */
    public State getState() {
        return state;
    }

    /**
     * Returns the state of the recorder.
     */
    public String getStateMessage() {
        return stateMessage;
    }

    /**
     * Sets the state of the recorder and fires a ChangeEvent.
     */
    private void setState(State newValue, String msg) {
        state = newValue;
        stateMessage = msg;
        fireStateChanged();
    }

    public long getStartTime() {
        return recordingStartTime;
    }

    /**
     * Starts the browser recorder.
     */
    public void start() throws IOException {
        stop();
        recordedFiles.clear();
        createMovieWriter();
        try {
            recordingStartTime = System.currentTimeMillis();
            recordingStopTime = Long.MAX_VALUE;

            outputTime = new Rational(0, 0);
            startWriter();
            try {
                startScreenCapture();
            } catch (AWTException e) {
                IOException ioe = new IOException("Start screen capture failed");
                ioe.initCause(e);
                stop();
                throw ioe;
            } catch (IOException ioe) {
                stop();
                throw ioe;
            }
            if (audioFormat != null) {
                try {
                    startAudioCapture();
                } catch (LineUnavailableException e) {
                    IOException ioe = new IOException("Start audio capture failed");
                    ioe.initCause(e);
                    stop();
                    throw ioe;
                }
            }
            setState(State.RECORDING, null);
        } catch (IOException e) {
            stop();
            throw e;
        }
    }

    /**
     * Starts screen capture.
     */
    private void startScreenCapture() throws AWTException, IOException {
        screenCaptureTimer = new ScheduledThreadPoolExecutor(1);
        int delay = max(1, (int) (1000 / screenFormat.get(FrameRateKey).doubleValue()));
        browserGrabber = new BrowserGrabber(this, recordingStartTime);
        screenFuture = screenCaptureTimer.scheduleAtFixedRate(browserGrabber, delay, delay, TimeUnit.MILLISECONDS);
        browserGrabber.setFuture(screenFuture);
        browserGrabber.setWindowProcessID();
    }

    private static class BrowserGrabber implements Runnable {

        /**
         * Holds the screen capture.
         */
        private BufferedImage screenCapture;
        private BrowserRecorder recorder;
        private Rectangle captureArea;
        /**
         *
         */
        private CaptureWindow captureWindow;
        private int windowProcessID;
        /**
         * Holds the composed image (screen capture and super-imposed mouse
         * cursor). This is the image that is written into the video track of
         * the file.
         */
        private BufferedImage videoImg;
        /**
         * Graphics object for drawing into {@code videoImg}.
         */
        private Graphics2D videoGraphics;
        /**
         * The time the previous screen frame was captured.
         */
        private Rational prevScreenCaptureTime;
        private int videoTrack;
        private long startTime;
        private volatile long stopTime = Long.MAX_VALUE;
        private ScheduledFuture future;
        private long sequenceNumber;

        public void setFuture(ScheduledFuture future) {
            this.future = future;
        }

        public synchronized void setStopTime(long newValue) {
            this.stopTime = newValue;
        }

        public synchronized long getStopTime() {
            return this.stopTime;
        }

        public BrowserGrabber(BrowserRecorder recorder, long startTime) throws AWTException, IOException {
            this.recorder = recorder;
            this.captureArea = recorder.captureArea;
            this.captureWindow = new CaptureWindow();
            this.videoTrack = recorder.videoTrack;
            this.prevScreenCaptureTime = new Rational(startTime, 1000);
            this.startTime = startTime;

            Format screenFormat = recorder.screenFormat;
            if (screenFormat.get(DepthKey, 24) == 24) {
                videoImg = new BufferedImage(this.captureArea.width, this.captureArea.height, BufferedImage.TYPE_INT_RGB);
            } else if (screenFormat.get(DepthKey) == 16) {
                videoImg = new BufferedImage(this.captureArea.width, this.captureArea.height, BufferedImage.TYPE_USHORT_555_RGB);
            } else if (screenFormat.get(DepthKey) == 8) {
                videoImg = new BufferedImage(this.captureArea.width, this.captureArea.height, BufferedImage.TYPE_BYTE_INDEXED, Colors.createMacColors());
            } else {
                throw new IOException("Unsupported color depth " + screenFormat.get(DepthKey));
            }
            videoGraphics = videoImg.createGraphics();
            videoGraphics.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE);
            videoGraphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED);
            videoGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        }

        public void setWindowProcessID() {
            this.windowProcessID = recorder.browserProcessID;
        }

        public void run() {
            try {
                grabWindow();
            } catch (Throwable ex) {
                ex.printStackTrace();
                recorder.recordingFailed(ex.getMessage());
            }
        }

        /**
         * Grabs a screen, generates video images with pending mouse captures
         * and writes them into the movie file.
         */
        private void grabWindow() throws IOException, InterruptedException {
            // Capture the screen
            BufferedImage previousScreenCapture = screenCapture;
            long timeBeforeCapture = System.currentTimeMillis();
            try {
                screenCapture = captureWindow.capture(InterfaceOperations.getHWNDsByPID(windowProcessID));
            } catch (IllegalMonitorStateException e) {
                //IOException ioe= new IOException("Could not grab screen");
                //ioe.initCause(e);
                //throw ioe;
                // Screen capture failed due to a synchronization error
                return;
            }
            long timeAfterCapture = System.currentTimeMillis();
            if (previousScreenCapture == null) {
                previousScreenCapture = screenCapture;
            }
            videoGraphics.drawImage(previousScreenCapture, 0, 0, null);

            Buffer buf = new Buffer();
            buf.format = new Format(MediaTypeKey, MediaType.VIDEO, EncodingKey, ENCODING_BUFFERED_IMAGE);

            if (prevScreenCaptureTime.compareTo(new Rational(getStopTime(), 1000)) < 0) {
                buf.data = videoImg;
                buf.sampleDuration = new Rational(timeAfterCapture, 1000).subtract(prevScreenCaptureTime);
                buf.timeStamp = prevScreenCaptureTime.subtract(new Rational(startTime, 1000));
                buf.track = videoTrack;
                buf.sequenceNumber = sequenceNumber++;
                buf.header = null; // no mouse position has been recorded for this frame
                recorder.write(buf);
                prevScreenCaptureTime = new Rational(timeAfterCapture, 1000);
            }

            if (timeBeforeCapture > getStopTime()) {
                future.cancel(false);
            }
        }

        public void close() {
            videoGraphics.dispose();
            videoImg.flush();
        }
    }

    /**
     * Starts audio capture.
     */
    private void startAudioCapture() throws LineUnavailableException {
        audioCaptureTimer = new ScheduledThreadPoolExecutor(1);
        audioGrabber = new AudioGrabber(mixer, audioFormat, audioTrack, recordingStartTime, writerQueue);
        audioFuture = audioCaptureTimer.scheduleWithFixedDelay(audioGrabber, 0, 10, TimeUnit.MILLISECONDS);
        audioGrabber.setFuture(audioFuture);
    }

    /**
     * Returns the audio level of the left channel or of the mono channel.
     *
     * @return A value in the range [0.0,1.0] or AudioSystem.NOT_SPECIFIED.
     */
    public float getAudioLevelLeft() {
        AudioGrabber ag = audioGrabber;
        if (ag != null) {
            return ag.getAudioLevelLeft();
        }
        return AudioSystem.NOT_SPECIFIED;
    }

    /**
     * Returns the audio level of the right channel.
     *
     * @return A value in the range [0.0,1.0] or AudioSystem.NOT_SPECIFIED.
     */
    public float getAudioLevelRight() {
        AudioGrabber ag = audioGrabber;
        if (ag != null) {
            return ag.getAudioLevelRight();
        }
        return AudioSystem.NOT_SPECIFIED;
    }

    /**
     * This runnable grabs audio samples and enqueues them into the specified
     * BlockingQueue. This runnable must be called twice a second.
     */
    private static class AudioGrabber implements Runnable {

        final private TargetDataLine line;
        final private BlockingQueue<Buffer> queue;
        final private int audioTrack;
        final private long startTime;
        private volatile long stopTime = Long.MAX_VALUE;
        private long totalSampleCount;
        private ScheduledFuture future;
        private long sequenceNumber;
        private float audioLevelLeft = AudioSystem.NOT_SPECIFIED;
        private float audioLevelRight = AudioSystem.NOT_SPECIFIED;

        public AudioGrabber(Mixer mixer, Format audioFormat, int audioTrack, long startTime, BlockingQueue<Buffer> queue)
                throws LineUnavailableException {
            this.audioTrack = audioTrack;
            this.queue = queue;
            this.startTime = startTime;
            DataLine.Info lineInfo = new DataLine.Info(
                    TargetDataLine.class, AudioFormatKeys.toAudioFormat(audioFormat));

            if (mixer != null) {
                line = (TargetDataLine) mixer.getLine(lineInfo);
            } else {

                line = (TargetDataLine) AudioSystem.getLine(lineInfo);
            }

            // Make sure the line is not muted
            try {
                BooleanControl ctrl = (BooleanControl) line.getControl(BooleanControl.Type.MUTE);
                ctrl.setValue(false);
            } catch (IllegalArgumentException e) {
                // We can't unmute the line from Java
            }
            // Make sure the volume of the line is bigger than 0.2
            try {
                FloatControl ctrl = (FloatControl) line.getControl(FloatControl.Type.VOLUME);
                ctrl.setValue(Math.max(ctrl.getValue(), 0.2f));
            } catch (IllegalArgumentException e) {
                // We can't change the volume from Java
            }
            line.open();
            line.start();
        }

        public void setFuture(ScheduledFuture future) {
            this.future = future;
        }

        public void close() {
            line.close();
        }

        public synchronized void setStopTime(long newValue) {
            this.stopTime = newValue;
        }

        public synchronized long getStopTime() {
            return this.stopTime;
        }

        public void run() {
            Buffer buf = new Buffer();
            AudioFormat lineFormat = line.getFormat();
            buf.format = fromAudioFormat(lineFormat).append(SilenceBugKey, true);

            // For even sample rates, we select a buffer size that can
            // hold half a second of audio. This allows audio/video interlave
            // twice a second, as recommended for AVI and QuickTime movies.
            // For odd sample rates, we have to select a buffer size that can hold
            // one second of audio.
            int bufferSize = lineFormat.getFrameSize() * (int) lineFormat.getSampleRate();
            if (((int) lineFormat.getSampleRate() & 1) == 0) {
                bufferSize /= 2;
            }

            byte bdat[] = new byte[bufferSize];
            buf.data = bdat;
            Rational sampleRate = Rational.valueOf(lineFormat.getSampleRate());
            Rational frameRate = Rational.valueOf(lineFormat.getFrameRate());
            int count = line.read(bdat, 0, bdat.length);
            if (count > 0) {
                computeAudioLevel(bdat, count, lineFormat);
                buf.sampleCount = count / (lineFormat.getSampleSizeInBits() / 8 * lineFormat.getChannels());
                buf.sampleDuration = sampleRate.inverse();
                buf.offset = 0;
                buf.sequenceNumber = sequenceNumber++;
                buf.length = count;
                buf.track = audioTrack;
                buf.timeStamp = new Rational(totalSampleCount, 1).divide(frameRate);

                // Check if recording should be stopped
                Rational stopTS = new Rational(getStopTime() - startTime, 1000);
                if (buf.timeStamp.add(buf.sampleDuration.multiply(buf.sampleCount)).compareTo(stopTS) > 0) {
                    // we recorderd too much => truncate the buffer
                    buf.sampleCount = Math.max(0, (int) Math.ceil(stopTS.subtract(buf.timeStamp).divide(buf.sampleDuration).floatValue()));
                    buf.length = buf.sampleCount * (lineFormat.getSampleSizeInBits() / 8 * lineFormat.getChannels());

                    future.cancel(false);
                }
                if (buf.sampleCount > 0) {
                    try {
                        queue.put(buf);
                    } catch (InterruptedException ex) {
                        // nothing to do
                    }
                }
                totalSampleCount += buf.sampleCount;
            }
        }

        /**
         * Calculates the root-mean-square average of continuous samples. For
         * four samples, the formula looks like this:
         * <pre>
         * rms = sqrt( (x0^2 + x1^2 + x2^2 + x3^2) / 4)
         * </pre> Resources:
         * http://www.jsresources.org/faq_audio.html#calculate_power
         *
         * @param data - byte array with data
         * @param length - array lenght
         * @param format - audio format
         */
        private void computeAudioLevel(byte[] data, int length, AudioFormat format) {
            audioLevelLeft = audioLevelRight = AudioSystem.NOT_SPECIFIED;
            if (format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)) {
                switch (format.getSampleSizeInBits()) {
                    case 8:
                        switch (format.getChannels()) {
                            case 1:
                                audioLevelLeft = computeAudioLevelSigned8(data, 0, length, format.getFrameSize());
                                break;
                            case 2:
                                audioLevelLeft = computeAudioLevelSigned8(data, 0, length, format.getFrameSize());
                                audioLevelRight = computeAudioLevelSigned8(data, 1, length, format.getFrameSize());
                                break;
                        }
                        break;
                    case 16:
                        if (format.isBigEndian()) {
                            switch (format.getChannels()) {
                                case 1:
                                    audioLevelLeft = computeAudioLevelSigned16BE(data, 0, length, format.getFrameSize());
                                    break;
                                case 2:
                                    audioLevelLeft = computeAudioLevelSigned16BE(data, 0, length, format.getFrameSize());
                                    audioLevelRight = computeAudioLevelSigned16BE(data, 2, length, format.getFrameSize());
                                    break;
                            }
                        } else {
                            switch (format.getChannels()) {
                                case 1:
                                    break;
                                case 2:
                                    break;
                            }
                        }
                        break;
                }
            }
        }

        private float computeAudioLevelSigned16BE(byte[] data, int offset, int length, int stride) {
            double sum = 0;
            for (int i = offset; i < length; i += stride) {
                int value = ((data[i]) << 8) | (data[i + 1] & 0xff);
                sum += value * value;
            }
            double rms = Math.sqrt(sum / ((length - offset) / stride));
            return (float) (rms / 32768);
        }

        private float computeAudioLevelSigned8(byte[] data, int offset, int length, int stride) {
            double sum = 0;
            for (int i = offset; i < length; i += stride) {
                int value = data[i];

                // TODO FIXME - The java audio system records silence as -128 instead of 0.
                if (value!=-128) sum += value * value;
            }
            double rms = Math.sqrt(sum / ((length) / stride));
            return (float) (rms / 128);
        }

        public float getAudioLevelLeft() {
            return audioLevelLeft;
        }

        public float getAudioLevelRight() {
            return audioLevelRight;
        }
    }

    /**
     * Starts file writing.
     */
    private void startWriter() {
        writerQueue = new ArrayBlockingQueue<Buffer>(screenFormat.get(FrameRateKey).intValue() + 1);
        writerThread = new Thread() {
            @Override
            public void run() {
                try {
                    while (writerThread == this || !writerQueue.isEmpty()) {
                        try {
                            Buffer buf = writerQueue.take();
                            doWrite(buf);
                        } catch (InterruptedException ex) {
                            // We have been interrupted, terminate
                            break;
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                    recordingFailed(e.getMessage()==null?e.toString():e.getMessage());
                }
            }
        };
        writerThread.start();
    }

    private void recordingFailed(final String msg) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    stop();
                    setState(State.FAILED, msg);
                } catch (IOException ex2) {
                    ex2.printStackTrace();
                }
            }
        });
    }

    /**
     * Stops the browser recorder. <p> Stopping the browser recorder may take
     * several seconds, because audio capture uses a large capture buffer. Also,
     * the MovieWriter has to finish up a movie file which may take some time
     * depending on the amount of meta-data that needs to be written.
     */
    public void stop() throws IOException {
        if (state == State.RECORDING) {
            recordingStopTime = System.currentTimeMillis();
            if (screenCaptureTimer != null) {
                browserGrabber.setStopTime(recordingStopTime);
            }
            if (audioCaptureTimer != null) {
                audioGrabber.setStopTime(recordingStopTime);
            }
            try {
                if (screenCaptureTimer != null) {
                    try {
                        screenFuture.get();
                    } catch (InterruptedException ignore) {
                    } catch (CancellationException ignore) {
                    } catch (ExecutionException ignore) {
                    }
                    screenCaptureTimer.shutdown();
                    screenCaptureTimer.awaitTermination(5000, TimeUnit.MILLISECONDS);
                    screenCaptureTimer = null;
                    browserGrabber.close();
                    browserGrabber = null;
                }
                if (audioCaptureTimer != null) {
                    try {
                        audioFuture.get();
                    } catch (InterruptedException ignore) {
                    } catch (CancellationException ignore) {
                    } catch (ExecutionException ignore) {
                    }
                    audioCaptureTimer.shutdown();
                    audioCaptureTimer.awaitTermination(5000, TimeUnit.MILLISECONDS);
                    audioCaptureTimer = null;
                    audioGrabber.close();
                    audioGrabber = null;
                }
            } catch (InterruptedException ex) {
                // nothing to do
            }
            stopWriter();
            setState(State.DONE, null);
        }
    }

    private void stopWriter() throws IOException {
        Thread pendingWriterThread = writerThread;
        writerThread = null;

        try {
            if (pendingWriterThread != null) {
                pendingWriterThread.interrupt();
                pendingWriterThread.join();
            }
        } catch (InterruptedException ex) {
            // nothing to do
            ex.printStackTrace();
        }
        if (w != null) {
            w.close();
            w = null;
        }
    }

    /**
     * Writes a buffer into the movie. Since the file system may not be
     * immediately available at all times, we do this asynchronously. <p> The
     * buffer is copied and passed to the writer queue, which is consumed by the
     * writer thread. See method startWriter(). <p> AVI does not support a
     * variable frame rate for the video track. Since we can not capture frames
     * at a fixed frame rate we have to resend the same captured screen multiple
     * times to the writer. <p> This method is called asynchronously from
     * different threads. <p> You can override this method if you wish to
     * process the media data.
     *
     *
     * @param buf A buffer with un-encoded media data. If
     * {@code buf.track==videoTrack}, then the buffer contains a
     * {@code BufferedImage} in {@code buffer.data} and a {@code Point} in
     * {@code buffer.header} with the recorded mouse location. The header is
     * null if the mouse is outside the capture area, or mouse recording has not
     * been enabled.
     *
     * @throws IOException
     */
    protected void write(Buffer buf) throws IOException, InterruptedException {
        MovieWriter writer = this.w;
        if (writer == null) {
            return;
        }
        if (buf.track == videoTrack) {
            if (!writer.getFormat(videoTrack).get(FixedFrameRateKey, false)) {
                // variable frame rate is supported => easy
                Buffer wbuf = new Buffer();
                frameEncoder.process(buf, wbuf);
                writerQueue.put(wbuf);
            } else {// variable frame rate not supported => convert to fixed frame rate

                // TODO FIXME - Use CodecChain for this

                Rational inputTime = buf.timeStamp.add(buf.sampleDuration);
                boolean isFirst = true;
                while (outputTime.compareTo(inputTime) < 0) {
                    buf.timeStamp = outputTime;
                    buf.sampleDuration = ffrDuration;
                    if (isFirst) {
                        isFirst = false;
                    } else {
                        buf.setFlag(SAME_DATA);
                    }
                    Buffer wbuf = new Buffer();
                    if (frameEncoder.process(buf, wbuf) != Codec.CODEC_OK) {
                        throw new IOException("Codec failed or could not process frame in a single step.");
                    }
                    writerQueue.put(wbuf);
                    outputTime = outputTime.add(ffrDuration);
                }
            }
        } else {
            Buffer wbuf = new Buffer();
            wbuf.setMetaTo(buf);
            wbuf.data = ((byte[]) buf.data).clone();
            wbuf.length = buf.length;
            wbuf.offset = buf.offset;
            writerQueue.put(wbuf);
        }
    }

    /**
     * The actual writing of the buffer happens here. <p> This method is called
     * exclusively from the writer thread in startWriter().
     *
     * @param buf - buffer
     * @throws IOException
     */
    private void doWrite(Buffer buf) throws IOException {
        MovieWriter mw = w;
        // Close file on a separate thread if file is full or an hour
        // has passed.
        // The if-statement must ensure that we only start a new video file
        // at a key-frame.
        // TODO FIXME - this assumes that all audio frames are key-frames
        // TODO FIXME - this does not guarantee that audio and video track have
        // TODO FIXME - the same duration
        long now = System.currentTimeMillis();
        if (buf.track == videoTrack && buf.isFlag(BufferFlag.KEYFRAME)
                && (mw.isDataLimitReached() || now - fileStartTime > maxRecordingTime)) {
            final MovieWriter closingWriter = mw;
            new Thread() {
                @Override
                public void run() {
                    try {
                        closingWriter.close();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }

                }
            }.start();
            mw = createMovieWriter();

        }
        mw.write(buf.track, buf);
    }

    /**
     * Maximal recording time in milliseconds. If this time is exceeded, the
     * recorder creates a new file.
     */
    public long getMaxRecordingTime() {
        return maxRecordingTime;
    }

    /**
     * Maximal recording time in milliseconds.
     */
    public void setMaxRecordingTime(long maxRecordingTime) {
        this.maxRecordingTime = maxRecordingTime;
    }

    /**
     * Maximal file size. If this size is exceeded, the recorder creates a new
     * file.
     */
    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    /**
     * Gets the audio mixer used for sound input. Returns null, if the default
     * mixer is used.
     */
    public Mixer getAudioMixer() {
        return mixer;
    }

    /**
     * Sets the audio mixer for sound input. Set to null for the default audio
     * mixer.
     */
    public void setAudioMixer(Mixer mixer) {
        this.mixer = mixer;
    }
}