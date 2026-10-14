package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

class DonationMessageAdmissionIntegrationTest extends DonationQueryTestSupport {
    @ParameterizedTest @ValueSource(longs={-1,0,1,1025}) void oversizedUtf8IsRejectedBeforeParsingWithZeroEffects(long length) throws Exception {
        var p=donation(null,MESSAGE); var before=facts();
        var request=authenticated(path(p.donationId().value()),"é".repeat(1024),length);
        api.mvc.perform(request).andExpect(status().is(413)).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.code").value("DONATION_MESSAGE_REQUEST_TOO_LARGE"));
        assertThat(request.read.get()).isEqualTo(length>1024?0:1025);assertThat(facts()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(ints={1023,1024,1025}) void exactByteBoundary(int size) throws Exception {
        var p=donation(null,MESSAGE);var id=p.donationId().value();String json="{\"confirmation\":\""+id+"\"}";
        var before=facts();var request=authenticated(path(id),json+" ".repeat(size-json.length()),-1);
        api.mvc.perform(request).andExpect(status().is(size>1024?413:200));
        if(size>1024)assertThat(facts()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"literal","id","context"}) void decodedMvcPathAndContextCannotBypassAdmission(String shape) throws Exception {
        var p=donation(null,MESSAGE);var id=p.donationId().value();String target=path(id);
        if(shape.equals("literal"))target=target.replace("message-redactions","message-%72edactions");
        if(shape.equals("id"))target=target.replace(id.toString(),"%"+Integer.toHexString(id.toString().charAt(0))+id.toString().substring(1));
        if(shape.equals("context"))target="/payments"+target;
        var valid=authenticated(target,"{\"confirmation\":\""+id+"\"}",-1);if(shape.equals("context"))valid.contextPath("/payments");
        api.mvc.perform(valid).andExpect(status().isOk());var before=facts();
        var bad=authenticated(target,"x".repeat(1025),-1);if(shape.equals("context"))bad.contextPath("/payments");
        api.mvc.perform(bad).andExpect(status().is(413));assertThat(facts()).isEqualTo(before);
    }
    @Test void authorizationAndCsrfPrecedeReading() throws Exception {
        var p=donation(null,MESSAGE);var before=facts();
        var anonymous=new BodyRequest(path(p.donationId().value()),"x".repeat(1025),-1);
        api.mvc.perform(anonymous).andExpect(status().isUnauthorized());assertThat(anonymous.read.get()).isZero();
        var noCsrf=new BodyRequest(path(p.donationId().value()),"x".repeat(1025),-1).cookie(api.session);
        api.mvc.perform(noCsrf).andExpect(status().isForbidden());assertThat(noCsrf.read.get()).isZero();assertThat(facts()).isEqualTo(before);
    }
    private String path(UUID id){return "/api/v1/admin/donations/"+id+"/message-redactions";}
    private BodyRequest authenticated(String path,String body,long length){return new BodyRequest(path,body,length).cookie(api.session,api.xsrf).header("X-XSRF-TOKEN",api.xsrf.getValue());}
    private List<List<String>> facts(){return List.of("donations","idempotency_records","audit_log").stream().map(t->jdbc.queryForList("select row_to_json(t)::text from "+t+" t order by id",String.class)).toList();}
    private static final class BodyRequest extends AbstractMockHttpServletRequestBuilder<BodyRequest> {
        final AtomicInteger read=new AtomicInteger();final long length;
        BodyRequest(String path,String body,long length){super(HttpMethod.POST);this.length=length;uri(URI.create(path)).contentType("application/json").content(body.getBytes(StandardCharsets.UTF_8)).header("Idempotency-Key","admission");}
        @Override protected MockHttpServletRequest createServletRequest(ServletContext context){return new MockHttpServletRequest(context){
            private ServletInputStream input;
            @Override public int getContentLength(){return (int)length;} @Override public long getContentLengthLong(){return length;}
            @Override public ServletInputStream getInputStream(){if(input==null){var delegate=super.getInputStream();input=new ServletInputStream(){
                @Override public int read() throws IOException {int b=delegate.read();if(b>=0)read.incrementAndGet();return b;}
                @Override public boolean isFinished(){return delegate.isFinished();}@Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){delegate.setReadListener(listener);}
            };}return input;}
        };}
    }
}
