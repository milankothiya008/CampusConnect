package com.campusconnect.feature.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.domain.result.Certificate;
import com.campusconnect.domain.result.CertificateKind;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.Decoder;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/** The certificate's QR code, wording and layout without the web layer. */
class CertificatePdfTest {

    static Certificate certificate(CertificateKind kind, String team) {
        Certificate certificate = new Certificate();
        certificate.setCode("CERT-ABCDEFGHJK");
        certificate.setKind(kind);
        certificate.setRecipientName("Asha Patel");
        certificate.setEventTitle("HackNight");
        certificate.setClubName("Coding Club");
        certificate.setEventStartAt(Instant.parse("2026-09-06T12:30:00Z"));
        certificate.setEventEndAt(Instant.parse("2026-09-07T00:30:00Z"));
        certificate.setAwardTitle(kind == CertificateKind.MERIT ? "Winner" : null);
        certificate.setTeamName(team);
        certificate.setIssuedAt(Instant.parse("2026-09-08T06:00:00Z"));
        return certificate;
    }

    static CertificatePdf renderer() {
        AppProperties properties = new AppProperties();
        properties.setClientUrl("https://campus.example.edu/");
        return new CertificatePdf(properties);
    }

    @Test
    void theQrCodeLeadsToThePublicVerificationPage() throws Exception {
        CertificatePdf pdf = renderer();
        String url = pdf.verifyUrl("CERT-ABCDEFGHJK");
        assertThat(url).isEqualTo("https://campus.example.edu/verify/CERT-ABCDEFGHJK");

        ByteMatrix modules = CertificatePdf.qrMatrix(url);
        BitMatrix bits = new BitMatrix(modules.getWidth(), modules.getHeight());
        for (int y = 0; y < modules.getHeight(); y++) {
            for (int x = 0; x < modules.getWidth(); x++) {
                if (modules.get(x, y) == 1) {
                    bits.set(x, y);
                }
            }
        }
        assertThat(new Decoder().decode(bits).getText()).isEqualTo(url);
    }

    @Test
    void theWordingFollowsTheKindTheTeamAndTheDates() throws Exception {
        assertThat(CertificatePdf.body(certificate(CertificateKind.MERIT, "Alpha")))
                .isEqualTo("has been awarded Winner as part of team “Alpha” at HackNight, organised by Coding Club on Sun, 6 Sept 2026 – Mon, 7 Sept 2026.");
        assertThat(CertificatePdf.body(certificate(CertificateKind.PARTICIPATION, null)))
                .isEqualTo("has successfully participated in HackNight, organised by Coding Club on Sun, 6 Sept 2026 – Mon, 7 Sept 2026.");
        assertThat(CertificatePdf.dateRange(Instant.parse("2026-10-06T04:30:00Z"), Instant.parse("2026-10-06T10:00:00Z"))).isEqualTo("Tue, 6 Oct 2026");

        byte[] bytes = renderer().render(certificate(CertificateKind.MERIT, "Alpha"), "Priya President", null);
        PdfReader reader = new PdfReader(bytes);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("DHARMSINH DESAI UNIVERSITY", "Certificate of Merit", "Asha Patel", "Priya President", "President, Coding Club", "Faculty mentor",
                "Certificate ID CERT-ABCDEFGHJK · Issued Tue, 8 Sept 2026");
        assertThat(reader.getInfo()).containsEntry("Creator", "CampusConnect");
        assertThat(reader.getPageSize(1).getWidth()).isGreaterThan(reader.getPageSize(1).getHeight());
        reader.close();
    }

    @Test
    void codesAreDrawnFromAnAlphabetWithoutLookalikeCharacters() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String code = CertificateService.newCode();
            assertThat(code).matches("^CERT-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$");
            codes.add(code);
        }
        assertThat(codes).hasSize(2_000);
    }
}
