package com.campusconnect.core.media;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Detects a file's real type from its first bytes ("magic numbers") instead of trusting the client's
 * Content-Type or file name (StorageService.detectImage, MediaStore.detectVideo/Audio/Document).
 */
public final class MediaTypes {

    public record Detected(String ext, String mime) {
    }

    private static final List<String> ZIP_DOCS = List.of("docx", "pptx", "xlsx");
    private static final List<String> OLE_DOCS = List.of("doc", "ppt", "xls");
    private static final List<String> TEXT_DOCS = List.of("txt", "csv");
    private static final byte[] OLE = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};

    private MediaTypes() {
    }

    public static Detected detectImage(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return new Detected("jpg", "image/jpeg");
        }
        if ((b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47) {
            return new Detected("png", "image/png");
        }
        if ("RIFF".equals(ascii(b, 0, 4)) && "WEBP".equals(ascii(b, 8, 12))) {
            return new Detected("webp", "image/webp");
        }
        return null;
    }

    public static Detected detectVideo(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if ("ftyp".equals(ascii(b, 4, 8))) {
            return "qt  ".equals(ascii(b, 8, 12)) ? new Detected("mov", "video/quicktime") : new Detected("mp4", "video/mp4");
        }
        if (isEbml(b)) {
            return new Detected("webm", "video/webm");
        }
        return null;
    }

    /** Voice notes: WebM/Opus (Chrome, Android), MP4/AAC (Safari, iPhone), Ogg, MP3 or WAV. */
    public static Detected detectAudio(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if (isEbml(b)) {
            return new Detected("webm", "audio/webm");
        }
        if ("OggS".equals(ascii(b, 0, 4))) {
            return new Detected("ogg", "audio/ogg");
        }
        if ("ftyp".equals(ascii(b, 4, 8))) {
            return new Detected("m4a", "audio/mp4");
        }
        if ("ID3".equals(ascii(b, 0, 3)) || ((b[0] & 0xff) == 0xff && (b[1] & 0xe0) == 0xe0)) {
            return new Detected("mp3", "audio/mpeg");
        }
        if ("RIFF".equals(ascii(b, 0, 4)) && "WAVE".equals(ascii(b, 8, 12))) {
            return new Detected("wav", "audio/wav");
        }
        return null;
    }

    /** What a document really is; the extension only picks between look-alikes (any ZIP vs an Office file). */
    public static Detected detectDocument(byte[] b, String ext) {
        if (b == null || b.length < 5) {
            return null;
        }
        if ("%PDF-".equals(ascii(b, 0, 5))) {
            return new Detected("pdf", "application/pdf");
        }
        String wanted = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (ZIP_DOCS.contains(wanted) && b.length >= 4 && b[0] == 0x50 && b[1] == 0x4b && b[2] == 0x03 && b[3] == 0x04) {
            return new Detected(wanted, "application/zip");
        }
        if (OLE_DOCS.contains(wanted) && b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8), OLE)) {
            return new Detected(wanted, "application/octet-stream");
        }
        if (TEXT_DOCS.contains(wanted)) {
            int end = Math.min(b.length, 4096);
            for (int i = 0; i < end; i++) {
                if (b[i] == 0) {
                    return null;
                }
            }
            return new Detected(wanted, "text/plain");
        }
        return null;
    }

    private static boolean isEbml(byte[] b) {
        return b[0] == 0x1a && b[1] == 0x45 && (b[2] & 0xff) == 0xdf && (b[3] & 0xff) == 0xa3;
    }

    private static String ascii(byte[] b, int from, int to) {
        return b.length < to ? "" : new String(b, from, to - from, StandardCharsets.US_ASCII);
    }
}
