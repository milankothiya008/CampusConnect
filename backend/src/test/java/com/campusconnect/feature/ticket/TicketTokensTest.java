package com.campusconnect.feature.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.tokens.TicketTokens;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/ticketToken.test.js ("ticket codes and tokens") against core.tokens.TicketTokens. */
class TicketTokensTest {

    TicketTokens tokens;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getJwt().setAccessSecret("test-access-secret-that-is-long-enough");
        tokens = new TicketTokens(properties);
    }

    @Test
    void codesAreShortReadableAndAvoidLookAlikeCharacters() {
        for (int i = 0; i < 200; i++) {
            String code = TicketTokens.generateCode();
            assertThat(code).matches(TicketTokens.CODE_PATTERN);
            assertThat(code).doesNotContainPattern("[01OILU]");
        }
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            codes.add(TicketTokens.generateCode());
        }
        assertThat(codes).hasSize(500);
    }

    @Test
    void typedCodesAreNormalisedHoweverStudentsWriteThem() {
        assertThat(TicketTokens.normalizeCode("cc-7k3m 9qwa")).isEqualTo("CC-7K3M9QWA");
        assertThat(TicketTokens.normalizeCode("CC7K3M9QWA")).isEqualTo("CC-7K3M9QWA");
        assertThat(TicketTokens.normalizeCode(" 7k3m9qwa ")).isEqualTo("CC-7K3M9QWA");
        // A code body that itself starts with CC keeps it.
        assertThat(TicketTokens.normalizeCode("CCAB2345")).isEqualTo("CC-CCAB2345");
        assertThat(TicketTokens.normalizeCode("7K3M9QW")).isNull();
        assertThat(TicketTokens.normalizeCode("CC-7K3M9QW0")).isNull();
        assertThat(TicketTokens.normalizeCode("")).isNull();
        assertThat(TicketTokens.normalizeCode(null)).isNull();
    }

    @Test
    void tokensRoundTripAndCarryASignatureOverTheRegistrationAndItsCode() {
        String registrationId = "64b000000000000000000001";
        String ticketCode = TicketTokens.generateCode();
        String token = tokens.createToken(registrationId, ticketCode);

        assertThat(token).matches("^[a-f0-9]{24}\\.CC-[A-Z2-9]{8}\\.[A-Za-z0-9_-]{32}$");
        assertThat(tokens.read(token)).isEqualTo(new TicketTokens.Ticket(registrationId, ticketCode));
    }

    @Test
    void tamperedOrMalformedTokensAreRejected() {
        String registrationId = "64b000000000000000000001";
        String ticketCode = TicketTokens.generateCode();
        String token = tokens.createToken(registrationId, ticketCode);
        String[] parts = token.split("\\.");

        assertThat(tokens.read(parts[0] + "." + TicketTokens.generateCode() + "." + parts[2])).isNull();
        assertThat(tokens.read("64b000000000000000000002." + parts[1] + "." + parts[2])).isNull();
        String flipped = parts[2].substring(0, 31) + (parts[2].endsWith("A") ? "B" : "A");
        assertThat(tokens.read(parts[0] + "." + parts[1] + "." + flipped)).isNull();
        assertThat(tokens.read(parts[0] + "." + parts[1])).isNull();
        assertThat(tokens.read("not-a-token")).isNull();
        assertThat(tokens.read("")).isNull();
        assertThat(tokens.read(null)).isNull();
    }
}
