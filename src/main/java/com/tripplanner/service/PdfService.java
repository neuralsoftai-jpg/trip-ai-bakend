package com.tripplanner.service;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import com.tripplanner.dto.request.PdfRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * PDF GENERATION SERVICE — OpenPDF (com.github.librepdf)
 *
 * WHY OPENPDF:
 *   OpenPDF is an actively maintained fork of iText 2.x (LGPL license).
 *   iText 7 is AGPL (commercial license required for closed-source apps).
 *   Apache PDFBox is lower-level and requires more code for formatted docs.
 *   OpenPDF = sweet spot: simple API, free, production-ready.
 *
 * WHAT WE GENERATE:
 *   A professional, formatted PDF trip plan containing:
 *   - Cover section with trip overview
 *   - Route & distance info
 *   - Fuel cost breakdown
 *   - Weather forecast summary
 *   - Local vibe & festivals
 *   - Packing list
 *   - Budget estimate
 *   - Emergency contacts
 *   - Carbon footprint
 *   - Optimized route
 *
 * OUTPUT:
 *   Returns byte[] which is streamed by the controller as:
 *   Content-Type: application/pdf
 *   Content-Disposition: attachment; filename="trip_plan.pdf"
 *
 * COMMON MISTAKE:
 *   Calling document.close() before outputStream.toByteArray().
 *   document.close() flushes and closes the PDF writer — you MUST
 *   call toByteArray() AFTER close() to get the complete PDF bytes.
 */
@Slf4j
@Service
public class PdfService {

    // ── Font constants ─────────────────────────────────────────────
    private static final Font TITLE_FONT =
            new Font(Font.HELVETICA, 22, Font.BOLD, new Color(33, 33, 33));

    private static final Font SECTION_FONT =
            new Font(Font.HELVETICA, 14, Font.BOLD, new Color(25, 118, 210));

    private static final Font BODY_FONT =
            new Font(Font.HELVETICA, 11, Font.NORMAL, new Color(66, 66, 66));

    private static final Font LABEL_FONT =
            new Font(Font.HELVETICA, 11, Font.BOLD, new Color(33, 33, 33));

    private static final Font SMALL_FONT =
            new Font(Font.HELVETICA, 9, Font.ITALIC, new Color(120, 120, 120));

    private static final Color HEADER_BG = new Color(25, 118, 210);
    private static final Color ACCENT_BG = new Color(232, 245, 233);
    private static final Color DIVIDER_COLOR = new Color(200, 200, 200);

    public byte[] generateTripPdf(PdfRequest req) {
        log.info("Generating PDF for trip: {} → {}", req.getSource(), req.getDestination());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 50, 50, 70, 50);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, outputStream);
            writer.setPageEvent(new HeaderFooterPageEvent(req));
            document.open();

            // ── Cover Section ──────────────────────────────────────
            addCoverSection(document, req);

            // ── Trip Overview Table ────────────────────────────────
            addSectionTitle(document, "📍 Trip Overview");
            addTwoColumnTable(document, new String[][]{
                    {"From", req.getSource()},
                    {"To", req.getDestination()},
                    {"Duration", req.getDays() + " days"},
                    {"Group Size", req.getGroupSize() + " people"},
                    {"Vehicle", formatVehicleType(req.getVehicleType())},
                    {"Total Distance", req.getDistanceKm() != null ? req.getDistanceKm() + " km" : "N/A"},
                    {"Travel Time", req.getTravelTime() != null ? req.getTravelTime() : "N/A"}
            });

            // ── Fuel Cost ──────────────────────────────────────────
            if (req.getTotalFuelCost() != null) {
                addSectionTitle(document, "⛽ Fuel Cost Breakdown");
                addTwoColumnTable(document, new String[][]{
                        {"Total Fuel Cost", "₹ " + req.getTotalFuelCost()},
                        {"Per Person Contribution", "₹ " + req.getPerPersonCost()}
                });
            }

            // ── Weather ────────────────────────────────────────────
            if (req.getWeatherSummary() != null) {
                addSectionTitle(document, "🌤 Weather Forecast Summary");
                addBodyParagraph(document, req.getWeatherSummary());
            }

            // ── Local Vibe ─────────────────────────────────────────
            if (req.getLocalVibe() != null) {
                addSectionTitle(document, "✨ Local Vibe & Culture");
                addBodyParagraph(document, req.getLocalVibe());
            }

            if (req.getFestivals() != null) {
                addSectionTitle(document, "🎉 Ongoing Festivals & Events");
                addBodyParagraph(document, req.getFestivals());
            }

            // ── Optimized Route ────────────────────────────────────
            if (req.getOptimizedRoute() != null) {
                addSectionTitle(document, "🗺 Optimized Visit Sequence");
                addBodyParagraph(document, req.getOptimizedRoute());
            }

            // ── Packing List ───────────────────────────────────────
            if (req.getPackingList() != null) {
                addSectionTitle(document, "🎒 Smart Packing List");
                addBodyParagraph(document, req.getPackingList());
            }

            // ── Budget ─────────────────────────────────────────────
            if (req.getBudgetSummary() != null) {
                addSectionTitle(document, "💰 Budget Estimate");
                addBodyParagraph(document, req.getBudgetSummary());
            }

            // ── Carbon Footprint ───────────────────────────────────
            if (req.getCarbonFootprint() != null) {
                addSectionTitle(document, "🌿 Carbon Footprint");
                addBodyParagraph(document, req.getCarbonFootprint());
            }

            // ── Emergency Contacts ─────────────────────────────────
            if (req.getEmergencyContacts() != null) {
                addSectionTitle(document, "🚨 Emergency Contacts");
                addBodyParagraph(document, req.getEmergencyContacts());
            }

            // ── Disclaimer ─────────────────────────────────────────
            document.add(new Chunk("\n"));
            Paragraph disclaimer = new Paragraph(
                "This trip plan was generated by AI Trip Planner. " +
                "All AI-generated content (vibe, budget, packing) is for guidance only. " +
                "Verify emergency contacts and weather locally before travel.",
                SMALL_FONT);
            disclaimer.setAlignment(Element.ALIGN_CENTER);
            document.add(disclaimer);

        } catch (Exception e) {
            log.error("PDF generation failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate PDF: " + e.getMessage(), e);
        } finally {
            // IMPORTANT: Always close document. Closing flushes PDF bytes to outputStream.
            if (document.isOpen()) document.close();
        }

        // IMPORTANT: Call toByteArray() AFTER document.close()
        byte[] pdfBytes = outputStream.toByteArray();
        log.info("PDF generated successfully. Size: {} bytes", pdfBytes.length);
        return pdfBytes;
    }

    // ─────────────────────────────────────────────────────────────────
    // PDF BUILDER HELPERS
    // ─────────────────────────────────────────────────────────────────

    private void addCoverSection(Document doc, PdfRequest req) throws DocumentException {
        // Blue header bar — use PdfPTable with colored cell
        // (Paragraph does NOT support setBackground/setPadding in OpenPDF)
        PdfPTable headerTable = new PdfPTable(1);
        headerTable.setWidthPercentage(100);
        headerTable.setSpacingAfter(10f);
        PdfPCell headerCell = new PdfPCell(new Phrase("\n  🌍 AI TRIP PLANNER  \n",
                new Font(Font.HELVETICA, 22, Font.BOLD, Color.WHITE)));
        headerCell.setBackgroundColor(HEADER_BG);
        headerCell.setPadding(15f);
        headerCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        headerCell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        headerTable.addCell(headerCell);
        doc.add(headerTable);

        doc.add(new Chunk("\n"));

        Paragraph tripTitle = new Paragraph(
                req.getSource() + "  →  " + req.getDestination(),
                new Font(Font.HELVETICA, 18, Font.BOLD, new Color(25, 118, 210)));
        tripTitle.setAlignment(Element.ALIGN_CENTER);
        doc.add(tripTitle);

        Paragraph subTitle = new Paragraph(
                req.getDays() + " Days · " + req.getGroupSize() + " People · " +
                        formatVehicleType(req.getVehicleType()),
                new Font(Font.HELVETICA, 12, Font.NORMAL, new Color(100, 100, 100)));
        subTitle.setAlignment(Element.ALIGN_CENTER);
        doc.add(subTitle);

        Paragraph genDate = new Paragraph(
                "Generated on: " + LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")),
                SMALL_FONT);
        genDate.setAlignment(Element.ALIGN_CENTER);
        doc.add(genDate);

        doc.add(new Chunk("\n\n"));
    }

    private void addSectionTitle(Document doc, String title) throws DocumentException {
        doc.add(new Chunk("\n"));
        Paragraph p = new Paragraph(title, SECTION_FONT);
        p.setSpacingBefore(10f);
        p.setSpacingAfter(5f);

        // Draw a colored underline
        PdfPTable line = new PdfPTable(1);
        line.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(new Color(25, 118, 210));
        cell.setFixedHeight(2f);
        cell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        line.addCell(cell);

        doc.add(p);
        doc.add(line);
        doc.add(new Chunk("\n"));
    }

    private void addBodyParagraph(Document doc, String text) throws DocumentException {
        Paragraph p = new Paragraph(text, BODY_FONT);
        p.setLeading(16f);
        p.setSpacingAfter(8f);
        doc.add(p);
    }

    private void addTwoColumnTable(Document doc, String[][] rows) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1f, 2f});
        table.setSpacingAfter(10f);

        for (String[] row : rows) {
            PdfPCell labelCell = new PdfPCell(new Phrase(row[0], LABEL_FONT));
            labelCell.setBackgroundColor(new Color(245, 245, 245));
            labelCell.setPadding(6f);
            labelCell.setBorderColor(DIVIDER_COLOR);
            table.addCell(labelCell);

            PdfPCell valueCell = new PdfPCell(new Phrase(row[1], BODY_FONT));
            valueCell.setPadding(6f);
            valueCell.setBorderColor(DIVIDER_COLOR);
            table.addCell(valueCell);
        }
        doc.add(table);
    }

    private String formatVehicleType(String vehicleType) {
        if (vehicleType == null) return "N/A";
        return vehicleType.replace("_", " ").substring(0, 1).toUpperCase()
                + vehicleType.replace("_", " ").substring(1);
    }

    /**
     * PDF Header/Footer event handler.
     * Called by OpenPDF on every page start/end.
     */
    static class HeaderFooterPageEvent extends PdfPageEventHelper {
        private final PdfRequest req;

        HeaderFooterPageEvent(PdfRequest req) {
            this.req = req;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Phrase footer = new Phrase(
                    "AI Trip Planner | " + req.getSource() + " → " + req.getDestination() +
                    " | Page " + writer.getPageNumber(),
                    new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(150, 150, 150)));
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, footer,
                    (document.left() + document.right()) / 2, document.bottom() - 15, 0);
        }
    }
}
