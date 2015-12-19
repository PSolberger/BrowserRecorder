package recorder.params;

import org.monte.media.Format;
import org.monte.media.math.Rational;

import java.awt.*;
import java.io.File;
import java.nio.ByteOrder;

import static org.monte.media.AudioFormatKeys.*;
import static org.monte.media.AudioFormatKeys.EncodingKey;
import static org.monte.media.AudioFormatKeys.MIME_AVI;
import static org.monte.media.AudioFormatKeys.MediaType;
import static org.monte.media.AudioFormatKeys.MediaTypeKey;
import static org.monte.media.AudioFormatKeys.MimeTypeKey;
import static org.monte.media.FormatKeys.FrameRateKey;
import static org.monte.media.FormatKeys.KeyFrameIntervalKey;
import static org.monte.media.VideoFormatKeys.*;

/**
 * Copyright (c) Nikolay Soloviev. All rights reserved.
 * @author Nikolay Soloviev <psolberger@gmail.com>
 */
public class DefaultRecorderParams {
    private static Rectangle captureArea = GraphicsEnvironment
            .getLocalGraphicsEnvironment().getDefaultScreenDevice()
            .getDefaultConfiguration()
            .getBounds();
    private static Format fileFormat = new Format(MediaTypeKey, MediaType.FILE,
            MimeTypeKey, MIME_AVI);
    private static Format screenFormat = new Format(MediaTypeKey, MediaType.VIDEO,
            EncodingKey, ENCODING_AVI_TECHSMITH_SCREEN_CAPTURE,
            CompressorNameKey, ENCODING_AVI_TECHSMITH_SCREEN_CAPTURE,
            DepthKey, 24,
            FrameRateKey, Rational.valueOf(15),
            QualityKey, 0.5f,
            KeyFrameIntervalKey, 15 * 60);
    private static Format audioFormat = new Format(MediaTypeKey, MediaType.AUDIO,
            EncodingKey, ENCODING_QUICKTIME_TWOS_PCM,
            FrameRateKey, new Rational(48000, 1),
            SampleSizeInBitsKey, 16,
            ChannelsKey, 2,
            SampleRateKey, new Rational(48000, 1),
            SignedKey, true,
            ByteOrderKey, ByteOrder.BIG_ENDIAN);
    private static File movieFolder = null;

    private DefaultRecorderParams() {

    }

    public static RecorderParams getDefault() {
        return new RecorderParams()
                .setCaptureArea(captureArea)
                .setFileFormat(fileFormat)
                .setScreenFormat(screenFormat)
                .setAudioFormat(audioFormat)
                .setMovieFolder(movieFolder);
    }

    public static RecorderParams getOnlyVideo() {
        return new RecorderParams()
                .setCaptureArea(captureArea)
                .setFileFormat(fileFormat)
                .setScreenFormat(screenFormat)
                .setMovieFolder(movieFolder);
    }
}
