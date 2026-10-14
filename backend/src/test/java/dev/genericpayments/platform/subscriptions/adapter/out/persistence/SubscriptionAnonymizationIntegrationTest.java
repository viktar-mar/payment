package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.application.service.CustomerAnonymizationService;
import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.adapter.out.integration.CustomerBillingAnonymizationGuardAdapter;
import dev.genericpayments.platform.subscriptions.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.concurrent.*;
import java.time.Clock;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class SubscriptionAnonymizationIntegrationTest extends SubscriptionLedgerFixture {
    @Autowired AnonymizeCustomerUseCase anonymizer;
    @Autowired SubscriptionLifecycleLockPort lifecycle;
    @Autowired CustomerIdentityStore identities;
    @Autowired LockSitePort sites;
    @Autowired CustomerIdempotencyStore idempotency;
    @Autowired DigestPort digests;
    @Autowired CustomerAnonymizationAuditPort audits;
    @Autowired CustomerTransactionPort transactions;
    @Autowired Clock clock;
    @Autowired EncryptedSecretPort encryption;
    @org.junit.jupiter.api.BeforeEach void attachPrivateEmail() {
        customer=customers.save(customer.attachEmail(CustomerStore.emailReference(customer.id()),"v1:abc",INITIAL_TIME),encryption.encrypt("subscription-owner@example.test"));
    }
    AnonymizeCustomerUseCase.Command command(){return new AnonymizeCustomerUseCase.Command(site,customer.id(),customer.id().value(),"erase-key","ADMIN","00000000-0000-0000-0000-000000000001","erase-request","127.0.0.1");}
    @Test void guardRejectsAnonymizationWithoutCancellingAnything() {
        var mocked=mock(SubscriptionStore.class);var guard=new CustomerBillingAnonymizationGuardAdapter(mocked);
        when(mocked.hasBlockingCustomerSubscriptions(site,customer.id())).thenReturn(true);
        assertThatThrownBy(()->guard.requireAnonymizable(site,customer.id())).isInstanceOf(PaymentApplicationException.class).hasMessage("Finish subscriptions before anonymization");
        verify(mocked).hasBlockingCustomerSubscriptions(site,customer.id());verifyNoMoreInteractions(mocked);
    }
    @Test void guardRunsInsideTransactionAfterCustomerLockBeforeAccessRevocation() {
        var guard=mock(CustomerBillingAnonymizationGuardPort.class);var access=mock(CustomerAccessRevocationPort.class);var redact=mock(CustomerDataRedactionPort.class);
        doAnswer(invocation->{assertThat(tx.active()).isTrue();throw new PaymentApplicationException("CUSTOMER_BILLING_ACTIVE",409,"Finish subscriptions before anonymization");}).when(guard).requireAnonymizable(site,customer.id());
        var service=new CustomerAnonymizationService(customers,identities,sites,idempotency,digests,access,redact,audits,transactions,clock,guard);
        assertThatThrownBy(()->service.anonymize(command())).isInstanceOf(PaymentApplicationException.class);
        verify(guard).requireAnonymizable(site,customer.id());verifyNoInteractions(access,redact);
        assertThat(customers.findById(site,customer.id()).orElseThrow().status()).isEqualTo(CustomerStatus.ACTIVE);
    }
    @Test void liveAndUnboundUnknownCreateBlockButTerminalSubscriptionAllowsAnonymization() {
        var s=store.save(subscription(),-1);
        assertThatThrownBy(()->anonymizer.anonymize(command())).hasMessage("Finish subscriptions before anonymization");
        store.save(s.cancel("CONFIRMED",INITIAL_TIME),0);
        var pending=operation(null,SubscriptionOperation.Kind.CREATE,"unbound");tx.required(()->operations.claim(pending));
        assertThatThrownBy(()->anonymizer.anonymize(command())).hasMessage("Finish subscriptions before anonymization");
        operations.save(new SubscriptionOperation(pending.id(),site,customer.id(),null,pending.kind(),SubscriptionOperation.Status.FAILED,pending.authorityScope(),pending.keyDigest(),pending.fingerprint(),pending.providerOperationKey(),0,0,null,"DECLINED",INITIAL_TIME,INITIAL_TIME,1),0);
        assertThat(anonymizer.anonymize(command()).status()).isEqualTo(CustomerStatus.ANONYMIZED);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void createAndAnonymizeSerializeInBothOrders(boolean createWins) throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->tx.required(()->{
                lifecycle.lockActive(site,customer.id());locked.countDown();await(release);
                if(createWins)store.save(subscription(),-1);else anonymizer.anonymize(command());
                return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{if(createWins)return anonymizer.anonymize(command());return tx.required(()->{lifecycle.lockActive(site,customer.id());store.save(subscription(),-1);return null;});});
            release.countDown();first.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->second.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(PaymentApplicationException.class);
            assertThat(customers.findById(site,customer.id()).orElseThrow().status()).isEqualTo(createWins?CustomerStatus.ACTIVE:CustomerStatus.ANONYMIZED);
            var email=customers.findEncryptedEmail(site,customer.id());
            if(createWins)assertThat(encryption.decrypt(email.orElseThrow())).isEqualTo("subscription-owner@example.test");
            else assertThat(email).isEmpty();
            assertThat(store.hasBlockingCustomerSubscriptions(site,customer.id())).isEqualTo(createWins);
        } finally {release.countDown();}
    }
    static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
