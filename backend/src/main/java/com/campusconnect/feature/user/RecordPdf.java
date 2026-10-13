package com.campusconnect.feature.user;

import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.result.CertificateKind;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.draw.LineSeparator;
import org.springframework.stereotype.Component;

/**
 * The participation record as an A4 PDF (RecordService.recordPdf with utils/Pdf.js), drawn with OpenPDF's built-in
 * fonts so nothing is fetched or installed on the server: the university header, totals, then clubs, awards,
 * events and certificates as tables whose header row repeats on every page.
 */
@Component
public class RecordPdf {

    static final Color NAVY = new Color(0x1b, 0x2a, 0x6b);
    static final Color GOLD = new Color(0xc8, 0x92, 0x11);
    static final Color INK = new Color(0x1d, 0x23, 0x33);
    static final Color MUTED = new Color(0x6b, 0x72, 0x85);
    static final Color BODY = new Color(0x2f, 0x36, 0x47);
    static final Color RULE = new Color(0xd8, 0xdc, 0xe6);
    static final Color ROW_RULE = new Color(0xec, 0xee, 0xf3);
    static final float MARGIN = 48;

    /** One table column: its header, share of the width and whether its cells are bold. */
    record Column(String label, float width, boolean bold) {
    }

    private static Font font(String name, float size, Color color) {
        return FontFactory.getFont(name, size, Font.NORMAL, color);
    }

    static String date(Instant value) {
        return value == null ? "" : CampusTime.formatDate(value);
    }

    static String dateRange(Instant start, Instant end) {
        return date(start).equals(date(end)) ? date(start) : date(start) + " – " + date(end);
    }

    public byte[] render(RecordService.Record record, String universityName, String clientUrl) {
        Map<String, Object> user = record.user() == null ? Map.of() : record.user();
        String name = String.valueOf(user.getOrDefault("name", ""));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, MARGIN, MARGIN, 40, MARGIN);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.addTitle("Participation record — " + name);
        document.addCreator("CampusConnect");
        document.addProducer("CampusConnect");
        document.open();

        PdfContentByte canvas = writer.getDirectContent();
        Rectangle page = document.getPageSize();
        canvas.setColorFill(NAVY);
        canvas.rectangle(0, page.getHeight() - 8, page.getWidth(), 8);
        canvas.fill();
        canvas.setColorFill(GOLD);
        canvas.rectangle(0, page.getHeight() - 10, page.getWidth(), 2);
        canvas.fill();

        Chunk overline = new Chunk(String.valueOf(universityName).toUpperCase(Locale.ROOT) + " · CAMPUSCONNECT", font(FontFactory.HELVETICA_BOLD, 9, MUTED));
        overline.setCharacterSpacing(1.5f);
        document.add(new Paragraph(overline));
        Paragraph title = new Paragraph("Participation record", font(FontFactory.TIMES_BOLD, 26, NAVY));
        title.setSpacingBefore(6);
        document.add(title);
        Paragraph who = new Paragraph(name, font(FontFactory.HELVETICA_BOLD, 13, INK));
        who.setSpacingBefore(4);
        document.add(who);
        List<String> meta = new ArrayList<>();
        addIfPresent(meta, user.get("departmentCode"));
        if (user.get("batchCode") != null && !String.valueOf(user.get("batchCode")).isEmpty()) {
            meta.add("Batch 20" + user.get("batchCode"));
        }
        addIfPresent(meta, user.get("email"));
        document.add(new Paragraph(String.join("  ·  ", meta), font(FontFactory.HELVETICA, 10, MUTED)));

        PdfPTable stats = new PdfPTable(4);
        stats.setWidthPercentage(100);
        stats.setSpacingBefore(10);
        for (Object[] stat : new Object[][] {
            {"Events", record.events().size()}, {"Awards", record.awards().size()}, {"Clubs", record.clubs().size()}, {"Certificates", record.certificates().size()}}) {
            Phrase phrase = new Phrase();
            phrase.add(new Chunk(String.valueOf(stat[1]) + "\n", font(FontFactory.HELVETICA_BOLD, 18, NAVY)));
            phrase.add(new Chunk(String.valueOf(stat[0]), font(FontFactory.HELVETICA, 9, MUTED)));
            PdfPCell cell = new PdfPCell(phrase);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPaddingLeft(0);
            stats.addCell(cell);
        }
        document.add(stats);
        LineSeparator divider = new LineSeparator(0.6f, 100, RULE, Element.ALIGN_CENTER, -6);
        document.add(new Paragraph(new Chunk(divider)));

        section(document, "Clubs and roles");
        table(document, List.of(new Column("Club", 0.5f, true), new Column("Role", 0.3f, false), new Column("Since", 0.2f, false)),
                record.clubs().stream().map(row -> List.of(nonNull(row.club()), nonNull(row.role()), date(row.since()))).toList(),
                "Not a member of any club yet.");

        section(document, "Awards and achievements");
        table(document, List.of(new Column("Award", 0.3f, true), new Column("Event", 0.4f, false), new Column("Club", 0.3f, false)),
                record.awards().stream().map(row -> List.of(
                        nonNull(row.title()) + (row.team() != null && !row.team().isEmpty() ? " (team " + row.team() + ")" : ""),
                        nonNull(row.event()) + (row.date() != null ? ", " + date(row.date()) : ""),
                        nonNull(row.club()))).toList(),
                "No awards yet.");

        section(document, "Events");
        table(document, List.of(new Column("Date", 0.2f, false), new Column("Event", 0.38f, true), new Column("Club", 0.27f, false), new Column("Status", 0.15f, false)),
                record.events().stream().map(row -> List.of(
                        dateRange(row.startAt(), row.endAt()),
                        nonNull(row.title()) + (row.team() != null && !row.team().isEmpty() ? "\nTeam " + row.team() : ""),
                        nonNull(row.club()),
                        nonNull(row.attendance()))).toList(),
                "No events yet.");

        section(document, "Certificates");
        table(document, List.of(new Column("Certificate", 0.32f, true), new Column("Event", 0.4f, false), new Column("ID", 0.28f, false)),
                record.certificates().stream().map(row -> List.of(
                        row.getKind() == CertificateKind.MERIT ? "Merit — " + nonNull(row.getAwardTitle()) : "Participation",
                        nonNull(row.getEventTitle()),
                        nonNull(row.getCode()))).toList(),
                "No certificates yet.");

        Paragraph footnote = new Paragraph("Generated on " + CampusTime.formatDate(Instant.now()) + " from CampusConnect records. Certificates can be verified at "
                + String.valueOf(clientUrl).replaceAll("/$", "") + "/verify/<ID>. \"Attended\" means checked in at the event; \"Registered\" means the club didn't record attendance.",
                font(FontFactory.HELVETICA, 8.5f, MUTED));
        footnote.setSpacingBefore(6);
        document.add(footnote);
        document.close();
        return out.toByteArray();
    }

    private static void addIfPresent(List<String> parts, Object value) {
        if (value != null && !String.valueOf(value).isEmpty()) {
            parts.add(String.valueOf(value));
        }
    }

    private static String nonNull(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    private static void section(Document document, String title) {
        Paragraph heading = new Paragraph(title, font(FontFactory.HELVETICA_BOLD, 12, NAVY));
        heading.setSpacingBefore(12);
        heading.setSpacingAfter(6);
        document.add(heading);
    }

    private static void table(Document document, List<Column> columns, List<List<String>> rows, String empty) {
        if (rows.isEmpty()) {
            document.add(new Paragraph(empty, font(FontFactory.HELVETICA_OBLIQUE, 10, MUTED)));
            return;
        }
        float[] widths = new float[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).width();
        }
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (Column column : columns) {
            Chunk label = new Chunk(column.label().toUpperCase(Locale.ROOT), font(FontFactory.HELVETICA_BOLD, 8.5f, MUTED));
            label.setCharacterSpacing(0.5f);
            PdfPCell cell = new PdfPCell(new Phrase(label));
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(RULE);
            cell.setBorderWidth(0.6f);
            cell.setPaddingBottom(5);
            table.addCell(cell);
        }
        for (List<String> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                Column column = columns.get(i);
                Color color = i == 0 ? INK : BODY;
                PdfPCell cell = new PdfPCell(new Phrase(row.get(i), font(column.bold() ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, 9.5f, color)));
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(ROW_RULE);
                cell.setBorderWidth(0.4f);
                cell.setPaddingTop(4);
                cell.setPaddingBottom(6);
                table.addCell(cell);
            }
        }
        document.add(table);
    }
}
