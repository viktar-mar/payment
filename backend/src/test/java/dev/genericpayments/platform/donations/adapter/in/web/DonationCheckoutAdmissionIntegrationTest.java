package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.servlet.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

@org.springframework.test.context.TestPropertySource(properties="payment.telemetry.site-keys[0]=donation-site")
class DonationCheckoutAdmissionIntegrationTest extends CampaignApiTestSupport {
    static final String URL=PublicDonationCheckoutApiIntegrationTest.URL;
    static final String JSON="{\"amount_minor\":500,\"message\":\"café\"}";
    @Autowired CustomerStore customers;
    @Autowired CustomerSessionService sessions;
    @BeforeEach void activate() throws Exception {changeStatus(create(),"ACTIVE",0,"activate").andExpect(status().isOk());}
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void streamedUnknownOrMisleadingLengthsReadExactlyLimitPlusOne(long length) throws Exception {
        var r=new BodyRequest(URL,"é".repeat(16384),length);
        api.mvc.perform(r).andExpect(status().is(413)).andExpect(jsonPath("$.code").value("DONATION_CHECKOUT_REQUEST_TOO_LARGE"));
        assertThat(r.read.get()).isEqualTo(16385);zero();
    }
    @Test void declaredOversizeDoesNotReadBody() throws Exception {
        var r=new BodyRequest(URL,"private-not-json",16385);
        api.mvc.perform(r).andExpect(status().is(413));assertThat(r.read.get()).isZero();zero();
    }
    @ParameterizedTest @ValueSource(ints={16383,16384,16385})
    void exactByteBoundaryWithMultibyteJson(int size) throws Exception {
        var body=JSON+" ".repeat(size-JSON.getBytes(StandardCharsets.UTF_8).length);
        var r=new BodyRequest(URL,body,-1);
        api.mvc.perform(r).andExpect(status().is(size==16385?413:201));
        assertThat(r.read.get()).isEqualTo(size);
        assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isEqualTo(size==16385?0:1);
    }
    @ParameterizedTest @ValueSource(strings={"api","public","donation-campaigns","donation-site","help-now","checkouts"})
    void decodedMvcAliasesCannotBypassCanonicalAdmission(String segment) throws Exception {
        // Real active Site, exact Origin, existing active Campaign, valid amount: no unrelated rejection can mask a bypass.
        api.mvc.perform(new BodyRequest(URL,JSON,-1)).andExpect(status().isCreated());
        var alias=URL.replace(segment,"%"+Integer.toHexString(segment.charAt(0))+segment.substring(1));
        var mapped=new BodyRequest(alias,JSON,-1).buildRequest(context.getServletContext());
        ServletRequestPathUtils.parseAndCache(mapped);
        var handler=context.getBean("requestMappingHandlerMapping",RequestMappingHandlerMapping.class).getHandler(mapped);
        assertThat(handler).isNotNull();assertThat(((HandlerMethod)handler.getHandler()).getBeanType()).isEqualTo(PublicDonationCheckoutController.class);
        var request=new BodyRequest(alias,"é".repeat(16384),-1);
        api.mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
        assertThat(request.read.get()).isZero();assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isEqualTo(1);
    }
    @Test void invalidAndDuplicateCredentialsWinBeforeReadingOversizeBodyOrMissingKey() throws Exception {
        for(var values:List.of(List.of("Bearer invalid"),List.of("Bearer invalid","Bearer invalid"))) {
            var r=new BodyRequest(URL.replace("help-now","!invalid"),"x".repeat(32768),-1).header("Authorization",values.toArray());
            api.mvc.perform(r).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
            assertThat(r.read.get()).isZero();
        }
        zero();
    }
    @ParameterizedTest @ValueSource(strings={"valid","revoked","anonymized","expired","cross-site","scope","wrong-origin"})
    void realOpaqueSessionAdmission(String state) throws Exception {
        var siteId=new SiteId(UUID.fromString(state.equals("cross-site")?otherSite:site));
        var customer=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),siteId,null,null,INITIAL_TIME));
        if(state.equals("wrong-origin")) jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",siteId.value(),"https://other.test");
        var issued=sessions.issue(siteId,customer.id(),state.equals("wrong-origin")?"https://other.test":"http://localhost:3000");
        switch(state) {
            case "revoked" -> sessions.logout(issued.context());
            case "anonymized" -> customers.save(customer.anonymize(INITIAL_TIME));
            case "expired" -> TestClock.set(INITIAL_TIME.plusSeconds(1800));
            case "scope" -> jdbc.update("update customer_sessions set scopes='[\"CUSTOMER_READ\"]' where id=?",issued.context().sessionId().value());
        }
        var r=new BodyRequest(URL,state.equals("valid")?JSON:"{",-1).header("Authorization","Bearer "+issued.rawToken());
        api.mvc.perform(r).andExpect(status().is(state.equals("valid")?201:401))
            .andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000"));
        if(state.equals("valid")) assertThat(jdbc.queryForObject("select customer_id from payments",UUID.class)).isEqualTo(customer.id().value());
        else {assertThat(r.read.get()).isZero();zero();}
    }
    @ParameterizedTest @ValueSource(strings={"alias","untrusted-origin","duplicate-origin","unknown-site"})
    void credentialErrorsDoNotGrantCorsToUntrustedRouteOrOrigin(String kind) throws Exception {
        String path=kind.equals("alias")?URL.replace("help-now","%68elp-now"):
            kind.equals("unknown-site")?URL.replace("donation-site","missing-site"):URL;
        var r=new BodyRequest(path,"{",-1).header("Authorization","Bearer invalid");
        if(kind.equals("untrusted-origin")) r.with(request->{request.removeHeader("Origin");request.addHeader("Origin","https://untrusted.test");return request;});
        if(kind.equals("duplicate-origin")) r.header("Origin","http://localhost:3000");
        api.mvc.perform(r).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        assertThat(r.read.get()).isZero();zero();
    }
    @Test void duplicateOriginsAndUnknownPreflightHeadersAreDeniedWithoutReading() throws Exception {
        var r=new BodyRequest(URL,JSON,-1).header("Origin","http://localhost:3000");
        api.mvc.perform(r).andExpect(status().isForbidden());assertThat(r.read.get()).isZero();
        api.mvc.perform(options(URL).header("Origin","http://localhost:3000").header("Access-Control-Request-Method","POST")
            .header("Access-Control-Request-Headers","X-Unsafe")).andExpect(status().isForbidden());zero();
    }
    void zero(){assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isZero();}
    static final class BodyRequest extends AbstractMockHttpServletRequestBuilder<BodyRequest> {
        final AtomicInteger read=new AtomicInteger();final long length;
        BodyRequest(String path,String body,long length) {
            super(HttpMethod.POST);this.length=length;
            uri(URI.create(path)).contentType("application/json").content(body.getBytes(StandardCharsets.UTF_8))
                .header("Origin","http://localhost:3000").header("Idempotency-Key","body-intent");
        }
        @Override protected MockHttpServletRequest createServletRequest(ServletContext context) {
            return new MockHttpServletRequest(context) {
                ServletInputStream counted;
                @Override public int getContentLength(){return (int)length;}
                @Override public long getContentLengthLong(){return length;}
                @Override public ServletInputStream getInputStream(){
                    if(counted==null){var delegate=super.getInputStream();counted=new ServletInputStream(){
                        @Override public int read() throws IOException {int value=delegate.read();if(value>=0)read.incrementAndGet();return value;}
                        @Override public boolean isFinished(){return delegate.isFinished();}
                        @Override public boolean isReady(){return delegate.isReady();}
                        @Override public void setReadListener(ReadListener listener){delegate.setReadListener(listener);}
                    };}return counted;
                }
            };
        }
    }
}
