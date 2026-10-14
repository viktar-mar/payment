package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.adapter.in.configuration.CustomerEmailOtpProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CustomerSourceAddressResolverTest {
    @Test void untrustedPeerCannotSpoofForwardedAddress() {
        var resolver=new CustomerSourceAddressResolver(properties(List.of("10.0.0.0/8")));
        var request=request("198.51.100.12","203.0.113.90");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.12");
    }

    @Test void trustedProxyChainChoosesNearestUntrustedHopForIpv4AndIpv6() {
        var resolver=new CustomerSourceAddressResolver(properties(List.of("10.0.0.0/8","2001:db8:abcd::/48")));
        var ipv4=request("10.0.0.9","203.0.113.40, 10.1.2.3");
        assertThat(resolver.resolve(ipv4)).isEqualTo("203.0.113.40");
        var ipv6=request("2001:db8:abcd::9","2001:db8:1::44, 2001:db8:abcd::8");
        assertThat(resolver.resolve(ipv6)).isEqualTo("2001:db8:1:0:0:0:0:44");
    }

    @Test void malformedOrAmbiguousForwardingFallsBackToDirectPeer() {
        var resolver=new CustomerSourceAddressResolver(properties(List.of("10.0.0.0/8")));
        assertThat(resolver.resolve(request("10.0.0.9","203.0.113.40, attacker.example"))).isEqualTo("10.0.0.9");
        var duplicate=request("10.0.0.9","203.0.113.40"); duplicate.addHeader("X-Forwarded-For","198.51.100.4");
        assertThat(resolver.resolve(duplicate)).isEqualTo("10.0.0.9");
    }

    @Test void invalidConfiguredCidrFailsStartup() {
        assertThatThrownBy(() -> new CustomerSourceAddressResolver(properties(List.of("192.0.2.0/33"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletRequest request(String peer,String forwarded) {
        var request=new MockHttpServletRequest(); request.setRemoteAddr(peer); request.addHeader("X-Forwarded-For",forwarded); return request;
    }
    private static CustomerEmailOtpProperties properties(List<String> cidrs) {
        return new CustomerEmailOtpProperties(null,null,null,null,cidrs,null,null,null);
    }
}
