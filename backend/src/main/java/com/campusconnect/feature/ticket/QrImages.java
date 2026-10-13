package com.campusconnect.feature.ticket;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** QR code images of ticket tokens (the qrcode package's options: error correction M, a two-module margin). */
public final class QrImages {

    private static final Map<EncodeHintType, Object> HINTS = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN, 2,
            EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());

    private QrImages() {
    }

    /** A square PNG of about {@code width} pixels. */
    public static byte[] png(String content, int width) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, width, HINTS);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot draw a QR code for this content", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code QRCode.toDataURL}: the PNG as a data: URL for the in-app ticket. */
    public static String dataUrl(String content, int width) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png(content, width));
    }
}
