package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.rekognition.ImageModerationProperties;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

@Component
public class ModerationImageConverter {

    private static final String ERROR_CODE_EMPTY = "IMAGE_EMPTY";
    private static final String ERROR_CODE_FORMAT = "WEBP_INVALID";
    private static final String ERROR_CODE_ANIMATED = "WEBP_ANIMATED";
    private static final String ERROR_CODE_DIMENSIONS = "WEBP_DIMENSIONS_EXCEEDED";
    private static final String ERROR_CODE_PIXELS = "WEBP_PIXELS_EXCEEDED";
    private static final String ERROR_CODE_SIZE = "WEBP_FILE_TOO_LARGE";

    private final ImageModerationProperties properties;

    public ModerationImageConverter(ImageModerationProperties properties) {
        this.properties = properties;
    }

    /**
     * Prepares an image for Rekognition moderation. Non-WebP images are returned unchanged.
     */
    public byte[] prepareForModeration(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new InvalidModerationImageException(ERROR_CODE_EMPTY, "Image upload is empty");
        }

        if (!isWebp(imageBytes)) {
            throw new InvalidModerationImageException(ERROR_CODE_FORMAT, "Input is not a WebP image");
        }

        ImageModerationProperties.Conversion conversion = properties.getConversion();
        if (conversion.getMaxFileSizeBytes() > 0 && imageBytes.length > conversion.getMaxFileSizeBytes()) {
            throw new InvalidModerationImageException(ERROR_CODE_SIZE,
                    "WebP image is too large (max %d bytes)".formatted(conversion.getMaxFileSizeBytes()));
        }

        ensureNotAnimated(imageBytes);

        ImageReader reader = getWebpReader();
        if (reader == null) {
            throw new InvalidModerationImageException(ERROR_CODE_FORMAT, "WebP reader not available");
        }

        try (ImageInputStream inputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            reader.setInput(inputStream, false, false);

            int imageCount = safeGetNumImages(reader);
            if (imageCount > 1) {
                throw new InvalidModerationImageException(ERROR_CODE_ANIMATED, "Animated WebP images are not supported");
            }

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            validateDimensions(width, height, conversion);

            BufferedImage source = reader.read(0);

            BufferedImage rgbImage = flattenToRgb(source);
            return encodeJpeg(rgbImage, conversion.getJpegQuality());
        } catch (InvalidModerationImageException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidModerationImageException(ERROR_CODE_FORMAT, "Failed to decode WebP image");
        } finally {
            reader.dispose();
        }
    }

    private void validateDimensions(int width, int height, ImageModerationProperties.Conversion conversion) {
        if (width <= 0 || height <= 0) {
            throw new InvalidModerationImageException(ERROR_CODE_FORMAT, "WebP image has invalid dimensions");
        }
        if ((conversion.getMaxWidth() > 0 && width > conversion.getMaxWidth())
                || (conversion.getMaxHeight() > 0 && height > conversion.getMaxHeight())) {
            throw new InvalidModerationImageException(ERROR_CODE_DIMENSIONS,
                    "WebP image exceeds maximum dimensions %dx%d".formatted(
                            conversion.getMaxWidth(), conversion.getMaxHeight()));
        }
        long pixels = (long) width * height;
        if (conversion.getMaxPixels() > 0 && pixels > conversion.getMaxPixels()) {
            throw new InvalidModerationImageException(ERROR_CODE_PIXELS,
                    "WebP image exceeds maximum pixel count %d".formatted(conversion.getMaxPixels()));
        }
    }

    private BufferedImage flattenToRgb(BufferedImage source) {
        BufferedImage rgbImage = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgbImage.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgbImage;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new InvalidModerationImageException(ERROR_CODE_FORMAT, "JPEG encoder not available");
        }

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.0f, Math.min(1.0f, quality)));
            }
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            byte[] bytes = baos.toByteArray();
            if (bytes.length == 0) {
                throw new InvalidModerationImageException(ERROR_CODE_FORMAT, "JPEG encoding produced empty output");
            }
            return bytes;
        } finally {
            writer.dispose();
        }
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P';
    }

    private ImageReader getWebpReader() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("webp");
        if (readers.hasNext()) {
            return readers.next();
        }
        return null;
    }

    private int safeGetNumImages(ImageReader reader) throws IOException {
        try {
            return reader.getNumImages(true);
        } catch (UnsupportedOperationException ex) {
            // Some readers do not support this query; treat as single image.
            return 1;
        }
    }

    private void ensureNotAnimated(byte[] bytes) {
        if (isAnimated(bytes)) {
            throw new InvalidModerationImageException(ERROR_CODE_ANIMATED, "Animated WebP images are not supported");
        }
    }

    private boolean isAnimated(byte[] bytes) {
        // WebP chunk parsing starting after RIFF header (12 bytes)
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String chunkType = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int chunkSize = readUInt32LE(bytes, offset + 4);
            if ("VP8X".equals(chunkType)) {
                if (chunkSize >= 1 && offset + 8 < bytes.length) {
                    int flags = bytes[offset + 8] & 0xFF;
                    if ((flags & 0x02) != 0) {
                        return true;
                    }
                }
            } else if ("ANIM".equals(chunkType)) {
                return true;
            }

            long advanced = 8L + chunkSize + (chunkSize & 1);
            if (advanced <= 0) {
                break;
            }
            offset += advanced;
        }
        return false;
    }

    private int readUInt32LE(byte[] bytes, int index) {
        if (index + 4 > bytes.length) {
            return 0;
        }
        return (bytes[index] & 0xFF)
                | ((bytes[index + 1] & 0xFF) << 8)
                | ((bytes[index + 2] & 0xFF) << 16)
                | ((bytes[index + 3] & 0xFF) << 24);
    }
}
