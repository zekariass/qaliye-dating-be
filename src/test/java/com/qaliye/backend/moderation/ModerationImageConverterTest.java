package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.rekognition.ImageModerationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModerationImageConverterTest {

    private ModerationImageConverter converter;
    private ImageModerationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ImageModerationProperties();
        converter = new ModerationImageConverter(properties);
    }

    @Test
    void converts_valid_webp_to_jpeg() throws IOException {
        assumeWebpWriterAvailable();
        byte[] webp = createWebp(100, 80, Color.RED, false);

        byte[] jpeg = converter.prepareForModeration(webp);

        assertThat(jpeg).isNotEmpty();
        assertThat(isJpeg(jpeg)).isTrue();
    }

    @Test
    void flattens_transparency_onto_white_background() throws IOException {
        assumeWebpWriterAvailable();

        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, 20, 20);
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(0, 0, 255, 128));
            g.fillRect(0, 0, 20, 20);
        } finally {
            g.dispose();
        }

        byte[] webp = encodeToWebp(image);
        byte[] jpeg = converter.prepareForModeration(webp);

        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        int rgb = decoded.getRGB(10, 10);
        Color color = new Color(rgb);
        // Semi-transparent blue flattened on white should produce light blue close to white.
        assertThat(color.getRed()).isGreaterThan(200);
        assertThat(color.getGreen()).isGreaterThan(200);
        assertThat(color.getBlue()).isGreaterThan(200);
    }

    @Test
    void rejects_animated_webp() {
        byte[] animated = createAnimatedWebpStub();

        assertThatThrownBy(() -> converter.prepareForModeration(animated))
                .isInstanceOf(InvalidModerationImageException.class)
                .hasMessageContaining("Animated WebP");
    }

    @Test
    void rejects_oversized_dimensions() throws IOException {
        properties.getConversion().setMaxWidth(50);
        properties.getConversion().setMaxHeight(50);

        assumeWebpWriterAvailable();

        byte[] large = createWebp(120, 100, Color.GREEN, false);

        assertThatThrownBy(() -> converter.prepareForModeration(large))
                .isInstanceOf(InvalidModerationImageException.class)
                .hasMessageContaining("maximum dimensions");
    }

    @Test
    void rejects_invalid_webp_bytes() {
        byte[] invalid = new byte[]{0x0, 0x1, 0x2};

        assertThatThrownBy(() -> converter.prepareForModeration(invalid))
                .isInstanceOf(InvalidModerationImageException.class)
                .hasMessageContaining("Invalid");
    }

    private byte[] createWebp(int width, int height, Color color, boolean withAlpha) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                withAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return encodeToWebp(image);
    }

    private byte[] encodeToWebp(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("webp");
        if (!writers.hasNext()) {
            throw new IllegalStateException("WebP writer not available");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private byte[] createAnimatedWebpStub() {
        byte[] bytes = new byte[20];
        System.arraycopy("RIFF".getBytes(), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, bytes, 8, 4);
        System.arraycopy("ANIM".getBytes(), 0, bytes, 12, 4);
        return bytes;
    }

    private void assumeWebpWriterAvailable() {
        boolean available = ImageIO.getImageWritersByFormatName("webp").hasNext();
        org.junit.jupiter.api.Assumptions.assumeTrue(available, "WebP writer not available on this JRE");
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length > 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[bytes.length - 2] & 0xFF) == 0xFF
                && (bytes[bytes.length - 1] & 0xFF) == 0xD9;
    }
}
