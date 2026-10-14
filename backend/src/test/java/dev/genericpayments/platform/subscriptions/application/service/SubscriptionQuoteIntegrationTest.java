package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.catalog.domain.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class SubscriptionQuoteIntegrationTest extends SubscriptionRecoveryFixture {
    @Test void rereadKeepsOriginalImmediateAnchorReferenceAndExpiryAfterClockAdvances(){
        var result=paid();var s=sub(result.subscriptionId());var current=s.billingPrice();
        var target=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"target",1,s.productId(),Money.of(4000,"EUR"),current.interval(),0,current.trialPolicy(),current.taxMode());
        var o=new SubscriptionOperation(UUID.randomUUID(),site,customer.id(),s.id(),SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Status.RESERVED,"test","test","test","preview-"+UUID.randomUUID(),s.revision(),policies.load(site).version(),s.providerReference(),null,INITIAL_TIME,INITIAL_TIME,0);
        var provider=providers.forAccount(account);
        var first=provider.preview(new QuoteRequest(new Command(account,o,current,target,s.providerReference(),null,null,false,false),target,ChangeTiming.IMMEDIATE));
        TestClock.set(INITIAL_TIME.plusSeconds(30));
        var reread=restartedProvider().preview(new QuoteRequest(new Command(account,o,current,target,s.providerReference(),null,first.reference(),false,false),target,ChangeTiming.IMMEDIATE));
        assertThat(reread).isEqualTo(first);
        assertThat(count("fake_subscription_quotes")).isEqualTo(1);
        assertThatThrownBy(()->restartedProvider().preview(new QuoteRequest(new Command(account,o,current,target,s.providerReference(),null,first.reference(),false,false),target,ChangeTiming.NEXT_PERIOD))).isInstanceOf(RuntimeException.class);
        var otherTarget=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"other",1,s.productId(),Money.of(5000,"EUR"),current.interval(),0,current.trialPolicy(),current.taxMode());
        assertThatThrownBy(()->restartedProvider().preview(new QuoteRequest(new Command(account,o,current,otherTarget,s.providerReference(),null,first.reference(),false,false),otherTarget,ChangeTiming.IMMEDIATE))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->restartedProvider().preview(new QuoteRequest(new Command(account,o,current,target,"wrong-subscription",null,first.reference(),false,false),target,ChangeTiming.IMMEDIATE))).isInstanceOf(RuntimeException.class);
        TestClock.set(first.expiresAt());assertThatThrownBy(()->restartedProvider().preview(new QuoteRequest(new Command(account,o,current,target,s.providerReference(),null,first.reference(),false,false),target,ChangeTiming.IMMEDIATE))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_subscription_quotes")).isEqualTo(1);
    }
}
