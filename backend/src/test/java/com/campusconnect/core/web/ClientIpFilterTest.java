package com.campusconnect.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ClientIpFilterTest {

    final ClientIpFilter filter = new ClientIpFilter("X-Vercel-Forwarded-For");

    String remoteAddressSeenDownstream(String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.setRemoteAddr("13.127.127.107");
        if (headerValue != null) {
            request.addHeader("X-Vercel-Forwarded-For", headerValue);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return ((HttpServletRequest) chain.getRequest()).getRemoteAddr();
    }

    @Test
    void usesTheVisitorAddressTheProxyForwarded() throws Exception {
        assertThat(remoteAddressSeenDownstream("152.59.1.222")).isEqualTo("152.59.1.222");
        assertThat(remoteAddressSeenDownstream("152.59.1.222, 10.0.0.1")).isEqualTo("152.59.1.222");
        assertThat(remoteAddressSeenDownstream("2001:db8::7")).isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void keepsThePeerAddressWithoutAUsableHeader() throws Exception {
        assertThat(remoteAddressSeenDownstream(null)).isEqualTo("13.127.127.107");
        assertThat(remoteAddressSeenDownstream("  ")).isEqualTo("13.127.127.107");
        // Never a host name (no DNS lookups) and never garbage.
        assertThat(remoteAddressSeenDownstream("evil.example.com")).isEqualTo("13.127.127.107");
        assertThat(remoteAddressSeenDownstream("1.2.3.4; DROP")).isEqualTo("13.127.127.107");
    }
}
