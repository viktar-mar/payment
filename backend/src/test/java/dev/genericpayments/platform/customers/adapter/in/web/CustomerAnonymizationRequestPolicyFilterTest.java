package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;

class CustomerAnonymizationRequestPolicyFilterTest {
    private static final String ID="11111111-1111-1111-1111-111111111111";
    private static final String PARTNER="/api/v1/partner/customers/"+ID+"/anonymization";
    private static final String ADMIN="/api/v1/admin/sites/"+ID+"/customers/"+ID+"/anonymization";
    private static final String BODY="{\"confirmation\":\""+ID+"\"}";
    private final CustomerAnonymizationRequestPolicyFilter filter=new CustomerAnonymizationRequestPolicyFilter(JsonMapper.builder().build());
    private MockHttpServletRequest request(String path,String body){
        var request=new MockHttpServletRequest("POST",path);
        request.addHeader("Idempotency-Key","valid-key");request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));return request;
    }
    @Test void canonicalAdminAndPartnerPassOnlyTheBoundedOriginalBody() throws Exception {
        for(String path:List.of(ADMIN,PARTNER)) {
            var request=request(path,BODY);var response=new MockHttpServletResponse();var called=new AtomicBoolean();
            filter.doFilter(request,response,(wrapped,out)->{called.set(true);assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));});
            assertThat(called).isTrue();assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(request.getAttribute(CustomerAuthenticationRequestPolicyFilter.BODY_TRACING_DISABLED)).isEqualTo(true);
        }
    }
    @Test void canonicalBodiesAreBoundedEvenWithUnknownContentLength() throws Exception {
        for(String path:List.of(ADMIN,PARTNER)) {
            var request=new MockHttpServletRequest("POST",path){@Override public long getContentLengthLong(){return -1;}};
            request.addHeader("Idempotency-Key","valid-key");request.setContent(new byte[1025]);
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(in,out)->fail("Oversized body reached MVC"));
            assertThat(response.getStatus()).isEqualTo(413);assertThat(response.getContentAsString()).contains("CUSTOMER_ANONYMIZATION_REQUEST_TOO_LARGE");
        }
    }
    @ParameterizedTest @ValueSource(strings={"%61nonymization","%2561nonymization","%252561nonymization","anonymization/","anonymization;x=y","ANONYMIZATION","./anonymization","child/../anonymization","%2eanonymization/../anonymization"})
    void hostileEncodedAndNormalizedAliasesCannotReachMvc(String suffix) throws Exception {
        for(String base:List.of(ADMIN,PARTNER)) {
            var request=request(base.substring(0,base.lastIndexOf('/')+1)+suffix," ".repeat(2048));var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(in,out)->fail("Alias bypassed mutation policy"));
            assertThat(response.getStatus()).isEqualTo(400);assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }
    @Test void siblingRoutesAndReadMethodsAreNotClaimed() throws Exception {
        for(String base:List.of(ADMIN,PARTNER))for(String suffix:List.of("anonymizations","anonymization-preview","identities","identity-links","payments")) {
            var request=request(base.substring(0,base.lastIndexOf('/')+1)+suffix," ".repeat(2048));var response=new MockHttpServletResponse();var called=new AtomicBoolean();
            filter.doFilter(request,response,(in,out)->called.set(true));assertThat(called).isTrue();assertThat(response.getHeader("Cache-Control")).isNull();
        }
        var request=request(PARTNER," ".repeat(2048));request.setMethod("GET");var called=new AtomicBoolean();
        filter.doFilter(request,new MockHttpServletResponse(),(in,out)->called.set(true));assertThat(called).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "bad\u0001key", "bad\ud800key"})
    void invalidKeysNeverReachMvc(String key) throws Exception {
        var request=request(PARTNER,BODY);request.removeHeader("Idempotency-Key");request.addHeader("Idempotency-Key",key);
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(in,out)->fail("Invalid key reached MVC"));assertThat(response.getStatus()).isEqualTo(400);
    }
    @Test void absentDuplicateOversizedKeysAndNonCanonicalBodiesNeverReachMvc() throws Exception {
        for(String body:List.of("{}","{\"confirmation\":1}",BODY+"{}",BODY.replace("}",",\"confirmation\":\""+ID+"\"}"),BODY.replace("}",",\"email\":\"secret\"}"),BODY.replace(ID,"1-1-1-1-1"))) {
            var response=new MockHttpServletResponse();filter.doFilter(request(PARTNER,body),response,(in,out)->fail("Invalid body reached MVC"));assertThat(response.getStatus()).isEqualTo(400);
        }
        for(int mode:List.of(0,1,2)) {
            var request=request(PARTNER,BODY);request.removeHeader("Idempotency-Key");
            if(mode==1){request.addHeader("Idempotency-Key","one");request.addHeader("Idempotency-Key","two");}
            if(mode==2)request.addHeader("Idempotency-Key","x".repeat(256));
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(in,out)->fail("Invalid key reached MVC"));assertThat(response.getStatus()).isEqualTo(400);
        }
    }
}
