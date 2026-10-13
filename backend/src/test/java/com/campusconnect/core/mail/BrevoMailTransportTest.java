package com.campusconnect.core.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Port of tests/unit/mailDelivery.test.js. */
class BrevoMailTransportTest {

    @Test
    void senderNamesAndAddressesAreReadFromMailFrom() {
        assertThat(BrevoMailTransport.parseAddress("CampusConnect <team@gmail.com>")).isEqualTo(Map.of("name", "CampusConnect", "email", "team@gmail.com"));
        assertThat(BrevoMailTransport.parseAddress("\"Campus Connect\" <a@b.co>")).isEqualTo(Map.of("name", "Campus Connect", "email", "a@b.co"));
        assertThat(BrevoMailTransport.parseAddress("plain@b.co")).isEqualTo(Map.of("email", "plain@b.co"));
    }

    @Test
    void messagesGoToBrevosHttpsApiWithTheKeySenderAndUnsubscribeHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "xkeysib-test"))
                .andExpect(content().json("""
                        {"sender":{"name":"CampusConnect","email":"team@gmail.com"},"to":[{"email":"24ceuog001@ddu.ac.in"}],
                         "subject":"123456 is your code","htmlContent":"<p>hi</p>","textContent":"hi","headers":{"List-Unsubscribe":"<https://x/u>"}}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"messageId\":\"m1\"}", MediaType.APPLICATION_JSON));

        new BrevoMailTransport(builder.build(), "xkeysib-test", "CampusConnect <team@gmail.com>")
                .send(new MailMessage("24ceuog001@ddu.ac.in", "123456 is your code", "<p>hi</p>", "hi", null, Map.of("List-Unsubscribe", "<https://x/u>")));
        server.verify();
    }

    @Test
    void aRefusedMessageThrowsSoTheEmailQueueRetriesIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"invalid_parameter\",\"message\":\"sender not valid\"}"));

        BrevoMailTransport transport = new BrevoMailTransport(builder.build(), "k", "a@b.co");
        assertThatThrownBy(() -> transport.send(new MailMessage("a@b.co", "s", "h", "t", null, null)))
                .hasMessage("Brevo refused the email (400 invalid_parameter sender not valid)");
    }
}
