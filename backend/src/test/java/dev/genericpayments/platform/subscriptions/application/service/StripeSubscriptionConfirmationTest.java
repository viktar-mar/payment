package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import dev.genericpayments.platform.subscriptions.application.model.*;import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;import dev.genericpayments.platform.customers.domain.*;
import java.time.*;import java.util.*;import org.junit.jupiter.api.*;
class StripeSubscriptionConfirmationTest {
 final SubscriptionInteractionTransaction phases=mock(SubscriptionInteractionTransaction.class);final SubscriptionProviderPort provider=mock(SubscriptionProviderPort.class);
 final Subscription s=SubscriptionFixtures.active(Instant.now(),Instant.now().plusSeconds(1000));
 final PaymentAccount account=PaymentAccount.create(s.paymentAccountId(),"billing",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_test","stripe-sandbox-default");
 final UUID id=UUID.randomUUID();final Instant now=Instant.now();
 final SubscriptionOperation op=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.PROCESSING,"customer:test","key","v1.paid."+"a".repeat(64),"subscription:"+account.id().value()+":"+id+":CREATE",0,1,s.providerReference(),null,now,now,0);
 final NextAction action=new NextAction(ActionKind.PROVIDER_CLIENT,"bga1_"+"a".repeat(43),now.plusSeconds(600));
 final SubscriptionInteractionTransaction.State state=new SubscriptionInteractionTransaction.State(s,account,null,op,false);
 final SubscriptionCommandContext context=new SubscriptionCommandContext(new SubscriptionActor.Customer(new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),s.siteId(),s.customerId(),"https://shop.test",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE),now.plusSeconds(1800))),s.siteId(),s.customerId(),"https://shop.test","confirm","request");
 final ConfirmSubscriptionActionUseCase.Command command=new ConfirmSubscriptionActionUseCase.Command(context,s.id(),id,action.secret());
 final SubscriptionActionService service=new SubscriptionActionService(phases,a->provider);
 @BeforeEach void setup(){when(phases.browserConfirmationAllowed(command,state)).thenReturn(true);when(phases.action(context,s.id(),id)).thenReturn(state);when(provider.nextAction(any())).thenReturn(action);when(phases.claim(command,state,action)).thenReturn(SubscriptionActionStore.ClaimDisposition.NEW);when(provider.confirmAction(any(),anyString())).thenReturn(new Reply(Outcome.ACCEPTED,s.providerReference(),null));when(phases.reply(eq(context),eq(state),any())).thenReturn(new SubscriptionOperationResult(id,s.id(),SubscriptionOperation.Status.PROCESSING,null));}
 @Test void onlyNewCommittedClaimAuthorizesBrowser(){assertThat(service.confirm(command).browserConfirmAllowed()).isTrue();}
 @Test void racedClaimReplayCannotAuthorize(){when(phases.claim(command,state,action)).thenReturn(SubscriptionActionStore.ClaimDisposition.REPLAY);assertThat(service.confirm(command).browserConfirmAllowed()).isFalse();}
 @Test void durableReplayDoesNotReadGraphOrCallProvider(){when(phases.replay(command)).thenReturn(true);assertThat(service.confirm(command).browserConfirmAllowed()).isFalse();verifyNoInteractions(provider);}
 @Test void uncertainLocalReceiptAndLostAckNeverAuthorize(){when(provider.confirmAction(any(),anyString())).thenThrow(new IllegalStateException("lost ack"));assertThat(service.confirm(command).browserConfirmAllowed()).isFalse();when(phases.replay(command)).thenReturn(true);assertThat(service.confirm(command).browserConfirmAllowed()).isFalse();}
 @Test void consumptionDuringActionReadReturnsNonAuthorizingReplay(){when(phases.replay(command)).thenReturn(false,true);when(provider.nextAction(any())).thenReturn(null);assertThatCode(()->assertThat(service.confirm(command).browserConfirmAllowed()).isFalse()).doesNotThrowAnyException();verify(provider,never()).confirmAction(any(),anyString());}
 @Test void finalAuthorityFenceCanRefuseBrowser(){when(phases.browserConfirmationAllowed(command,state)).thenReturn(false);assertThat(service.confirm(command).browserConfirmAllowed()).isFalse();}
 @Test void terminalReplyCannotAuthorize(){when(phases.reply(eq(context),eq(state),any())).thenReturn(new SubscriptionOperationResult(id,s.id(),SubscriptionOperation.Status.SUCCEEDED,null));assertThat(service.confirm(command).browserConfirmAllowed()).isFalse();}
}
