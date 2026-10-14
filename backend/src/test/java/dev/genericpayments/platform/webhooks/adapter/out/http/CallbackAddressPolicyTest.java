package dev.genericpayments.platform.webhooks.adapter.out.http;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CallbackAddressPolicyTest {
    @ParameterizedTest
    @CsvSource({
        "0.0.0.0,true", "0.255.255.255,true", "1.0.0.0,false",
        "9.255.255.255,false", "10.0.0.0,true", "10.255.255.255,true", "11.0.0.0,false",
        "100.63.255.255,false", "100.64.0.0,true", "100.127.255.255,true", "100.128.0.0,false",
        "126.255.255.255,false", "127.0.0.0,true", "127.255.255.255,true", "128.0.0.0,false",
        "169.253.255.255,false", "169.254.0.0,true", "169.254.255.255,true", "169.255.0.0,false",
        "172.15.255.255,false", "172.16.0.0,true", "172.31.255.255,true", "172.32.0.0,false",
        "191.255.255.255,false", "192.0.0.0,true", "192.0.0.255,true", "192.0.1.0,false",
        "192.0.1.255,false", "192.0.2.0,true", "192.0.2.255,true", "192.0.3.0,false",
        "192.88.98.255,false", "192.88.99.0,true", "192.88.99.2,true", "192.88.99.255,true", "192.88.100.0,false",
        "192.167.255.255,false", "192.168.0.0,true", "192.168.255.255,true", "192.169.0.0,false",
        "198.17.255.255,false", "198.18.0.0,true", "198.19.255.255,true", "198.20.0.0,false",
        "198.51.99.255,false", "198.51.100.0,true", "198.51.100.255,true", "198.51.101.0,false",
        "203.0.112.255,false", "203.0.113.0,true", "203.0.113.255,true", "203.0.114.0,false",
        "223.255.255.255,false", "224.0.0.0,true", "239.255.255.255,true", "240.0.0.0,true", "255.255.255.255,true",
        "192.0.78.24,false", "192.2.1.1,false", "198.51.1.1,false", "203.0.1.1,false",
        "192.31.196.1,false", "192.52.193.1,false", "192.175.48.1,false",
        "1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff,true", "2000::,false",
        "2000:ffff:ffff:ffff:ffff:ffff:ffff:ffff,false", "2001::,true", "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff,true", "2001:200::,false",
        "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff,false", "2001:db8::,true", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff,true", "2001:db9::,false",
        "2001:ffff:ffff:ffff:ffff:ffff:ffff:ffff,false", "2002::,true", "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff,true", "2003::,false",
        "3ffe:ffff:ffff:ffff:ffff:ffff:ffff:ffff,false", "3fff::,true", "3fff::1,true", "3fff:fff::1,true", "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff,true", "3fff:1000::,false",
        "3fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff,false", "4000::,true", "64:ff9b::808:808,true", "64:ff9b:1::1,true", "100::1,true", "100:0:0:1::1,true", "5f00::1,true",
        "2620:4f:8000::1,false", "::ffff:192.0.78.24,false", "::ffff:192.0.2.1,true", "::ffff:192.88.99.2,true"
    })
    void exactAddressBoundariesDoNotBlockAdjacentPublicSpace(String literal, boolean forbidden) throws Exception {
        assertThat(CallbackUrlPolicy.forbidden(InetAddress.getByName(literal))).as(literal).isEqualTo(forbidden);
    }

    @Test void realMappedIpv6BytesAndMixedDocumentationAnswersAreRejected() throws Exception {
        byte[] mapped = new byte[16]; mapped[10]=(byte)255; mapped[11]=(byte)255;
        mapped[12]=(byte)192; mapped[13]=88; mapped[14]=99; mapped[15]=2;
        assertThat(CallbackUrlPolicy.forbidden(Inet6Address.getByAddress(null,mapped,-1))).isTrue();
        var policy=new CallbackUrlPolicy(false,host->List.of(InetAddress.getByName("8.8.8.8"),InetAddress.getByName("3fff::1")));
        assertThatThrownBy(()->policy.validate("https://callback.example/")).isInstanceOf(RuntimeException.class);
    }
}
