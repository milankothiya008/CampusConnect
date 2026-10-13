package com.campusconnect.feature.certificate;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.result.Certificate;
import com.campusconnect.domain.result.CertificateKind;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/**
 * The A4 landscape certificate with a verification QR code (utils/Pdf.js certificatePdf): same positions, fonts,
 * sizes, colours and texts. Built-in PDF fonts only, so nothing has to be fetched or installed on the server. The
 * drawing helpers take PDFKit's top-left coordinates and line metrics (top of the line at {@code y}, line advance =
 * font bounding box + {@code lineGap}, kerned widths) so the layout matches the Node output. The QR code is drawn as
 * vector squares, crisp at any zoom.
 */
@Component
public class CertificatePdf {

    private static final int[] NAVY = {0x1b, 0x2a, 0x6b};
    private static final int[] GOLD = {0xc8, 0x92, 0x11};
    private static final int[] INK = {0x1d, 0x23, 0x33};
    private static final int[] MUTED = {0x6b, 0x72, 0x85};
    private static final int[] PAPER = {0xff, 0xfd, 0xf8};
    private static final int[] WHITE = {0xff, 0xff, 0xff};
    private static final float A4_LONG_SIDE = 841.89f;
    private static final float A4_SHORT_SIDE = 595.28f;

    private final AppProperties properties;

    public CertificatePdf(AppProperties properties) {
        this.properties = properties;
    }

    /** Where the QR code and the printed link lead: the public verification page. */
    public String verifyUrl(String code) {
        return properties.getClientUrl().replaceAll("/$", "") + "/verify/" + code;
    }

    static String dateRange(Instant start, Instant end) {
        return CampusTime.formatDate(start).equals(CampusTime.formatDate(end)) ? CampusTime.formatDate(start) : CampusTime.formatDate(start) + " – " + CampusTime.formatDate(end);
    }

    /** The certificate's sentence under the recipient's name. */
    static String body(Certificate certificate) {
        String when = dateRange(certificate.getEventStartAt(), certificate.getEventEndAt());
        String team = certificate.getTeamName() != null && !certificate.getTeamName().isEmpty() ? " as part of team “" + certificate.getTeamName() + "”" : "";
        return certificate.getKind() == CertificateKind.MERIT
                ? "has been awarded " + certificate.getAwardTitle() + team + " at " + certificate.getEventTitle() + ", organised by " + certificate.getClubName() + " on " + when + "."
                : "has successfully participated" + team + " in " + certificate.getEventTitle() + ", organised by " + certificate.getClubName() + " on " + when + ".";
    }

    public byte[] render(Certificate certificate, String president, String mentor) {
        String verifyUrl = verifyUrl(certificate.getCode());
        boolean merit = certificate.getKind() == CertificateKind.MERIT;
        // PDFKit's A4 in landscape (a landscape media box, not a portrait page with /Rotate 90).
        Rectangle size = new Rectangle(A4_LONG_SIDE, A4_SHORT_SIDE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(size, 0, 0, 0, 0);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.addTitle(certificate.getRecipientName() + " — " + certificate.getEventTitle());
            document.addCreator("CampusConnect");
            document.addProducer("CampusConnect");
            document.open();
            Canvas canvas = new Canvas(writer.getDirectContent(), size.getWidth(), size.getHeight());
            BaseFont helvetica = font(BaseFont.HELVETICA);
            BaseFont helveticaBold = font(BaseFont.HELVETICA_BOLD);
            BaseFont times = font(BaseFont.TIMES_ROMAN);
            BaseFont timesBold = font(BaseFont.TIMES_BOLD);
            BaseFont timesItalic = font(BaseFont.TIMES_ITALIC);
            float width = canvas.width;
            float height = canvas.height;

            canvas.fillRect(PAPER, 0, 0, width, height);
            canvas.strokeRect(NAVY, 4, 22, 22, width - 44, height - 44);
            canvas.strokeRect(GOLD, 1, 32, 32, width - 64, height - 64);
            // Corner accents.
            for (float[] corner : new float[][] {{32, 32}, {width - 32, 32}, {32, height - 32}, {width - 32, height - 32}}) {
                canvas.fillCircle(GOLD, corner[0], corner[1], 5);
            }

            canvas.text(helveticaBold, 10, MUTED, properties.getUniversity().getName().toUpperCase(Locale.ROOT), 0, 62, width, 0, 2);
            canvas.text(helvetica, 11, NAVY, certificate.getClubName(), 0, 80, width, 0, 0);
            canvas.text(timesBold, 40, NAVY, merit ? "Certificate of Merit" : "Certificate of Participation", 0, 118, width, 0, 0);
            canvas.line(GOLD, 2, width / 2 - 70, 170, width / 2 + 70, 170);
            canvas.text(timesItalic, 16, INK, "This is to certify that", 0, 192, width, 0, 0);
            canvas.text(timesBold, 34, NAVY, certificate.getRecipientName(), 60, 218, width - 120, 0, 0);
            canvas.text(times, 16, INK, body(certificate), 110, 270, width - 220, 4, 0);

            // Signatures.
            float signY = height - 140;
            signature(canvas, helvetica, helveticaBold, 90, signY, president, "President, " + certificate.getClubName());
            signature(canvas, helvetica, helveticaBold, width - 280, signY, mentor, "Faculty mentor");

            // Verification.
            float qrSize = 74;
            canvas.qr(qrMatrix(verifyUrl), width / 2 - qrSize / 2, signY - 46, qrSize);
            canvas.text(helvetica, 8, MUTED, "Verify: " + verifyUrl, 0, signY + 34, width, 0, 0);
            canvas.text(helvetica, 8, MUTED, "Certificate ID " + certificate.getCode() + " · Issued " + CampusTime.formatDate(certificate.getIssuedAt()), 0, signY + 46, width, 0, 0);
            document.close();
        } catch (DocumentException | IOException | WriterException e) {
            throw new IllegalStateException("Could not render certificate " + certificate.getCode(), e);
        }
        return out.toByteArray();
    }

    private static void signature(Canvas canvas, BaseFont regular, BaseFont bold, float x, float signY, String name, String role) {
        canvas.line(MUTED, 0.8f, x, signY, x + 190, signY);
        canvas.text(bold, 11, INK, name == null || name.isEmpty() ? " " : name, x, signY + 8, 190, 0, 0);
        canvas.text(regular, 9, MUTED, role, x, signY + 24, 190, 0, 0);
    }

    private static BaseFont font(String name) throws DocumentException, IOException {
        return BaseFont.createFont(name, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }

    /** The QR modules of the verification link (error correction M and no quiet zone, like the qrcode package). */
    static ByteMatrix qrMatrix(String text) throws WriterException {
        return Encoder.encode(text, ErrorCorrectionLevel.M).getMatrix();
    }

    /** A wrapped line and the width PDFKit centres it with. */
    record Line(String text, float width) {
    }

    /**
     * PDFKit's line wrapper: the text is cut after spaces into words that keep their trailing space; a word is
     * {@code kerned width + characterSpacing × its length} wide; words fill a line while they fit, and a line is
     * centred on the sum of its words (trailing space included). A word wider than the line is split.
     */
    static List<Line> wrap(BaseFont font, float size, String text, float maxWidth, float spacing) {
        List<Line> lines = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        float textWidth = 0;
        float spaceLeft = maxWidth;
        for (String word : words(String.valueOf(text))) {
            float width = width(font, size, word, spacing);
            if (width <= spaceLeft) {
                buffer.append(word);
                textWidth += width;
                spaceLeft -= width;
                continue;
            }
            if (!buffer.isEmpty()) {
                lines.add(new Line(buffer.toString(), textWidth));
            }
            String rest = word;
            while (width(font, size, rest, spacing) > maxWidth && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && width(font, size, rest.substring(0, cut), spacing) > maxWidth) {
                    cut--;
                }
                lines.add(new Line(rest.substring(0, cut), width(font, size, rest.substring(0, cut), spacing)));
                rest = rest.substring(cut);
            }
            buffer.setLength(0);
            buffer.append(rest);
            textWidth = width(font, size, rest, spacing);
            spaceLeft = maxWidth - textWidth;
        }
        lines.add(new Line(buffer.toString(), textWidth));
        return lines;
    }

    /** Break opportunities after spaces, each word keeping the spaces that follow it. */
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ' ' && (i + 1 == text.length() || text.charAt(i + 1) != ' ')) {
                words.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length() || words.isEmpty()) {
            words.add(text.substring(start));
        }
        return words;
    }

    static float width(BaseFont font, float size, String text, float spacing) {
        return font.getWidthPointKerned(text, size) + spacing * text.length();
    }

    /** PDF drawing in PDFKit's coordinates (origin top-left, y down). */
    private static final class Canvas {

        private final PdfContentByte content;
        private final float width;
        private final float height;

        Canvas(PdfContentByte content, float width, float height) {
            this.content = content;
            this.width = width;
            this.height = height;
        }

        private void fillColor(int[] rgb) {
            content.setRGBColorFill(rgb[0], rgb[1], rgb[2]);
        }

        void fillRect(int[] rgb, float x, float y, float w, float h) {
            fillColor(rgb);
            content.rectangle(x, height - y - h, w, h);
            content.fill();
        }

        void strokeRect(int[] rgb, float lineWidth, float x, float y, float w, float h) {
            content.setLineWidth(lineWidth);
            content.setRGBColorStroke(rgb[0], rgb[1], rgb[2]);
            content.rectangle(x, height - y - h, w, h);
            content.stroke();
        }

        void fillCircle(int[] rgb, float x, float y, float radius) {
            fillColor(rgb);
            content.circle(x, height - y, radius);
            content.fill();
        }

        void line(int[] rgb, float lineWidth, float x1, float y1, float x2, float y2) {
            content.setLineWidth(lineWidth);
            content.setRGBColorStroke(rgb[0], rgb[1], rgb[2]);
            content.moveTo(x1, height - y1);
            content.lineTo(x2, height - y2);
            content.stroke();
        }

        /** {@code doc.text(text, x, y, { width, align: "center", lineGap, characterSpacing })}. */
        void text(BaseFont font, float size, int[] rgb, String text, float x, float y, float boxWidth, float lineGap, float spacing) {
            float ascent = font.getFontDescriptor(BaseFont.ASCENT, size);
            float lineHeight = font.getFontDescriptor(BaseFont.BBOXURY, size) - font.getFontDescriptor(BaseFont.BBOXLLY, size);
            fillColor(rgb);
            float top = y;
            for (Line line : wrap(font, size, text, boxWidth, spacing)) {
                float left = x + boxWidth / 2 - line.width() / 2;
                content.beginText();
                content.setFontAndSize(font, size);
                content.setCharacterSpacing(spacing);
                content.setTextMatrix(left, height - top - ascent);
                content.showTextKerned(line.text());
                content.endText();
                top += lineHeight + lineGap;
            }
        }

        /** The QR code on white, one filled path for all dark modules (no seams between them). */
        void qr(ByteMatrix matrix, float x, float y, float size) {
            fillRect(WHITE, x, y, size, size);
            int modules = matrix.getWidth();
            float cell = size / modules;
            fillColor(NAVY);
            for (int row = 0; row < modules; row++) {
                int column = 0;
                while (column < modules) {
                    if (matrix.get(column, row) != 1) {
                        column++;
                        continue;
                    }
                    int start = column;
                    while (column < modules && matrix.get(column, row) == 1) {
                        column++;
                    }
                    content.rectangle(x + start * cell, height - y - (row + 1) * cell, (column - start) * cell, cell);
                }
            }
            content.fill();
        }
    }
}
