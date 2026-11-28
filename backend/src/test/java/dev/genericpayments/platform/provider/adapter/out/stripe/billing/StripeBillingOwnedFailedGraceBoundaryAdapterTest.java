package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceBoundary.*;
import dev.genericpayments.platform.provider.application.service.OwnedFailedGraceBoundaryInertFixture;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class StripeBillingOwnedFailedGraceBoundaryAdapterTest {
    @ParameterizedTest @EnumSource(Phase.class)
    void pinnedGetOneExactPostAndIndependentGetUseOnlyTheNominatedEdge(Phase phase){
        var f=new Fixture(phase);long target=phase==Phase.AT?260300:260301;
        f.reply(target-1,"ready");f.reply(target,"ready");f.reply(target,"ready");
        var ready=f.advance();
        assertThat(ready.observation().frozenTime()).isEqualTo(target);
        assertThat(f.sdk.http.calls.stream().map(r->r.method()+" "+r.url().getPath())).containsExactly(
            "GET /v1/test_helpers/test_clocks/clock_owned","POST /v1/test_helpers/test_clocks/clock_owned/advance","GET /v1/test_helpers/test_clocks/clock_owned");
        var post=f.sdk.http.calls.get(1);
        assertThat(post.params()).containsExactlyInAnyOrderEntriesOf(Map.of("frozen_time",target));
        assertThat(post.options().getIdempotencyKey()).isEqualTo("billing-failed-grace-boundary-v1:00000000-0000-0000-0000-000000000047:"+(phase==Phase.AT?"at":"after"));
        assertThat(f.sdk.http.calls).allSatisfy(r->{
            assertThat(r.options().getMaxNetworkRetries()).isZero();
            assertThat(r.options().getConnectTimeout()).isBetween(1,2000);assertThat(r.options().getReadTimeout()).isBetween(1,2000);
            assertThat(r.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");
        });
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);
        assertThat(f.clients).isEqualTo(1);assertThat(f.sdk.http.calls).hasSize(3);assertThat(f.local.cleanups).isZero();
    }
    @Test void afterRejectsOldGMinusOneBeforePost(){
        var f=new Fixture(Phase.AFTER);f.reply(260299,"ready");
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);
        assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.local.row(Phase.AFTER).stopped()).isTrue();
        assertThat(f.local.row(Phase.AT).stopped()).isFalse();
    }
    @Test void strictRawPreflightAndPollFailureConsumeOnlyOwnedEvidence(){
        for(String input:List.of("duplicate","trailing","malformed")){
            var f=new Fixture(Phase.AT);String body=f.clock(260299,"ready");
            f.sdk.http.reply(switch(input){case "duplicate"->body.replace("\"livemode\":false","\"livemode\":true,\"livemode\":false");case "trailing"->body+" {}";default->body.replace("260299","\"260299\"");});
            assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);
            assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.local.row(Phase.AT).ack()).isNull();assertThat(f.local.row(Phase.AT).stopped()).isTrue();
        }
        var f=new Fixture(Phase.AT);f.reply(260299,"ready");f.reply(260300,"ready");f.sdk.http.disconnect();
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);
        assertThat(f.local.row(Phase.AT).ack()).isNotNull();assertThat(f.local.row(Phase.AT).ready()).isNull();assertThat(f.local.row(Phase.AT).stopped()).isTrue();
    }
    @Test void twelvePollLimitAndLateCommittedReadyUseOriginalOwnerBudget(){
        var f=new Fixture(Phase.AT);f.reply(260299,"ready");f.reply(260299,"advancing");
        for(int i=0;i<12;i++)f.reply(260299,"advancing");
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);
        assertThat(f.waits).hasSize(11).allSatisfy(delay->assertThat(delay).isGreaterThanOrEqualTo(Duration.ofMillis(250)));
        assertThat(f.waits.getFirst()).isEqualTo(Duration.ofMillis(250));
        assertThat(f.local.base.nanos()).isBetween(Duration.ofSeconds(20).toNanos(),Duration.ofSeconds(28).toNanos());
        assertThat(f.sdk.http.calls).hasSize(14);
        assertThat(f.local.row(Phase.AT).stopped()).isTrue();
        var late=new Fixture(Phase.AT);late.reply(260299,"ready");late.reply(260300,"ready");late.reply(260300,"ready");
        late.local.afterCommit=()->{var r=late.local.row(Phase.AT);if(r!=null&&r.ready()!=null)late.local.base.elapsed(31_000_000_000L);};
        assertThat(late.advance()).isEqualTo(late.local.row(Phase.AT).ready());assertThat(late.local.cleanups).isZero();
    }
    @ParameterizedTest @EnumSource(Phase.class)
    void unreturnedFirstClaimAndAmbiguousAckOrReadyCannotBorrowCleanupAuthority(Phase phase){
        for(String at:List.of("claim","ack","ready")){
            var f=new Fixture(phase);long target=phase==Phase.AT?260300:260301;
            f.reply(target-1,"ready");f.reply(target,"ready");f.reply(target,"ready");
            f.local.afterCommit=()->{
                var r=f.local.row(phase);
                if(r!=null&&(at.equals("claim")||at.equals("ack")&&r.ack()!=null||at.equals("ready")&&r.ready()!=null))
                    throw new IllegalStateException("ambiguous committed return");
            };
            assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);
            var r=f.local.row(phase);
            assertThat(f.sdk.http.calls).hasSize(at.equals("claim")?0:at.equals("ack")?2:3);
            assertThat(f.local.cleanups).isEqualTo(at.equals("claim")?0:1);
            assertThat(r.stopped()).isEqualTo(at.equals("ack"));
            assertThat(r.ack()!=null).isEqualTo(!at.equals("claim"));
            assertThat(r.ready()!=null).isEqualTo(at.equals("ready"));
        }
    }

    @Test void exclusiveUtcAndMonotonicWholeCallBoundsApplyAtBoundary(){
        for(boolean utc:List.of(true,false)){
            var f=new Fixture(Phase.AT);f.reply(260299,"ready");f.reply(260300,"ready");
            f.afterResponse=()->{if(utc)f.local.base.at(f.local.base.now().plusSeconds(2));else f.local.base.elapsed(2_000_000_000L);};
            assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);
            assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.local.row(Phase.AT).ack()).isNull();
            assertThat(f.local.row(Phase.AT).stopped()).isTrue();
        }
    }

    static final class Fixture {
        final OwnedFailedGraceBoundaryInertFixture local=new OwnedFailedGraceBoundaryInertFixture();
        final StripeBillingInertSdkFixture sdk=new StripeBillingInertSdkFixture();
        final List<Duration> waits=new ArrayList<>();final Phase phase;int clients;Runnable afterResponse=()->{};
        Fixture(Phase phase){this.phase=phase;if(phase==Phase.AFTER)local.complete(Phase.AT);}
        String clock(long second,String status){
            var c=local.base.ready();
            return "{\"id\":\""+c.reference()+"\",\"object\":\"test_helpers.test_clock\",\"livemode\":false,\"name\":\""+c.name()+"\",\"created\":"+c.created()+",\"deletes_after\":"+c.deletesAfter()+",\"frozen_time\":"+second+",\"status\":\""+status+"\"}";
        }
        void reply(long second,String status){sdk.http.reply(clock(second,status));}
        BoundaryReady advance(){
            var caller=Thread.currentThread();
            var transport=new HttpClient(){public StripeResponse request(StripeRequest request)throws StripeException{
                assertThat(Thread.currentThread()).isNotSameAs(caller);assertThat(local.base.active()).isFalse();
                if(sdk.http.calls.size()>=2)assertThat(local.row(phase).ack()).isNotNull();
                var response=sdk.http.request(request);afterResponse.run();return response;
            }};
            var clientsFactory=new StripeClientFactory(){public StripeClient create(StripeCredentials credentials){
                clients++;return StripeClient.builder().setApiKey("sk_test_INERT").setMaxNetworkRetries(0).setHttpClient(transport).build();
            }};
            var adapter=new StripeBillingOwnedFailedGraceBoundaryAdapter(local.service,sdk.credentials,clientsFactory,local.base.clock(),
                local.base::nanos,d->{waits.add(d);local.base.elapsed(local.base.nanos()+d.toNanos());local.base.at(local.base.now().plus(d));});
            return adapter.advance(local.base.context.admission(),local.base.context.preparation(),phase);
        }
    }
}

