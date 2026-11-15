package dev.genericpayments.acceptance.runtime;

import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.provider.adapter.out.persistence.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.service.*;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.support.*;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit genuine producers. Never obtains an isolated proof from ordinary denied application beans. */
final class OwnedBillingFailedRenewalComponents {
    private final Function<Class<?>,Object> beans;private final Clock utc;
    final OwnedFirstRenewalStore renewals;final OwnedFailedRenewalStore failedStore;
    final OwnedFailedRenewalMethodStore methodStore;final SubscriptionTransactionPort transaction;
    final OwnedFailedRenewalService failures;final OwnedFailedRenewalMethodService methods;
    final OwnedFailedGraceAdvanceService grace;final OwnedFailedGraceBoundaryService boundaries;
    final OwnedFailedGraceBillingTime time;final QuerySubscriptionsUseCase query;
    final StripeCredentialResolver credentials;final StripeClientFactory clients;final DigestPort digest;
    final ProviderOperationStore steps;final SubscriptionBillingProofStore proofs;

    OwnedBillingFailedRenewalComponents(Function<Class<?>,Object> beans,Clock utc){
        this.beans=Objects.requireNonNull(beans);this.utc=Objects.requireNonNull(utc);
        var jdbc=bean(JdbcTemplate.class);var emf=bean(EntityManagerFactory.class);
        transaction=bean(SubscriptionTransactionPort.class);
        renewals=new JdbcOwnedFirstRenewalStore(jdbc);failedStore=new JdbcOwnedFailedRenewalStore(jdbc,emf);
        methodStore=new JdbcOwnedFailedRenewalMethodStore(jdbc,emf);
        time=new OwnedFailedGraceBillingTime(jdbc,emf,new OwnedFailedRenewalBillingTime(jdbc,new OwnedFirstRenewalBillingTime(jdbc,new OwnedSubscriptionBillingTime(jdbc))));
        credentials=bean(StripeCredentialResolver.class);clients=bean(StripeClientFactory.class);digest=bean(DigestPort.class);
        steps=bean(ProviderOperationStore.class);proofs=bean(SubscriptionBillingProofStore.class);
        var failureFacts=facts(new OwnedFailedRenewalFactProof(failedStore,transaction,time),SubscriptionIsolatedMethodProofPort.denied());
        var methodFacts=facts(new OwnedFailedRenewalMethodRecoveryFactProof(methodStore,transaction,time),new OwnedFailedRenewalMethodFactProof(methodStore,transaction,time));
        failures=new OwnedFailedRenewalService(failedStore,transaction,bean(SubscriptionOperationStore.class),bean(SubscriptionLifecycleLockPort.class),bean(LockPaymentAccountPort.class),bean(SubscriptionStore.class),bean(BillingRecordStore.class),bean(PrepareSubscriptionReadUseCase.class),bean(SubscriptionObservationPort.class),failureFacts,bean(TrialEligibilityStore.class),time,utc,bean(StripeBillingBindingPort.class));
        methods=new OwnedFailedRenewalMethodService(methodStore,failedStore,transaction,bean(SubscriptionOperationStore.class),bean(SubscriptionLifecycleLockPort.class),bean(LockPaymentAccountPort.class),bean(SubscriptionStore.class),bean(BillingRecordStore.class),bean(PrepareSubscriptionReadUseCase.class),bean(SubscriptionObservationPort.class),methodFacts,bean(TrialEligibilityStore.class),time,utc,bean(StripeBillingBindingPort.class),bean(SubscriptionAuthorityPort.class),digest);
        grace=new OwnedFailedGraceAdvanceService(failures,new JdbcOwnedFailedGraceAdvanceStore(jdbc,emf),transaction,utc);
        boundaries=new OwnedFailedGraceBoundaryService(failures,new JdbcOwnedFailedGraceBoundaryStore(jdbc,emf),transaction,utc);
        query=new SubscriptionQueryService(transaction,bean(SubscriptionAuthorityPort.class),bean(SubscriptionStore.class),bean(SubscriptionReadPort.class),bean(SubscriptionPolicyPort.class),bean(SubscriptionCatalogPort.class),bean(TrialEligibilityStore.class),bean(LoadPaymentAccountPort.class),bean(SubscriptionProviderRegistry.class),utc,time);
    }
    private <T>T bean(Class<T> type){return type.cast(beans.apply(type));}
    private SubscriptionFactService facts(SubscriptionIsolatedRecoveryProofPort recovery,SubscriptionIsolatedMethodProofPort method){
        return new SubscriptionFactService(bean(SubscriptionStore.class),bean(SubscriptionOperationStore.class),bean(TrialEligibilityStore.class),bean(LockSitePort.class),bean(CustomerStore.class),bean(LockPaymentAccountPort.class),bean(BillingRecordStore.class),bean(SubscriptionCashPort.class),bean(SubscriptionEventStore.class),bean(SubscriptionCatalogPort.class),utc,bean(SubscriptionCallbackPort.class),bean(SubscriptionObjectPort.class),bean(BillingMetadataPort.class),bean(SubscriptionPolicyPort.class),bean(SubscriptionObservationPort.class),digest,bean(SubscriptionQuoteStore.class),bean(SubscriptionSweepPort.class),bean(SubscriptionReadPreparation.class),bean(SubscriptionAuthorityPort.class),time,recovery,method,proofs);
    }
    OwnedFirstRenewal.Admission admit(OwnedClockSubscriptionReceipt receipt,String source){
        return new OwnedFirstRenewalAdmissionService(renewals,transaction,bean(SubscriptionOperationStore.class),bean(SubscriptionLifecycleLockPort.class),bean(LockPaymentAccountPort.class),bean(SubscriptionStore.class),utc).admit(receipt,source);
    }
    StripeBillingFirstRenewalAdvanceAdapter advance(){return new StripeBillingFirstRenewalAdvanceAdapter(credentials,clients,bean(LoadPaymentAccountPort.class),steps,renewals,utc);}
    StripeBillingFirstRenewalAdapter boundaryObserver(){return new StripeBillingFirstRenewalAdapter(credentials,clients,renewals,transaction,bean(SubscriptionOperationStore.class),bean(SubscriptionLifecycleLockPort.class),bean(LockPaymentAccountPort.class),bean(SubscriptionStore.class),bean(PrepareSubscriptionReadUseCase.class),bean(SubscriptionObservationPort.class),bean(StripeBillingBindingPort.class),utc);}
    StripeBillingOwnedFailedRenewalObserver failureObserver(){return new StripeBillingOwnedFailedRenewalObserver(failures,credentials,clients,utc);}
    StripeBillingOwnedFailedGraceAdvanceAdapter graceAdapter(){return new StripeBillingOwnedFailedGraceAdvanceAdapter(grace,credentials,clients,utc);}
    StripeBillingOwnedFailedGraceBoundaryAdapter boundaryAdapter(){return new StripeBillingOwnedFailedGraceBoundaryAdapter(boundaries,credentials,clients,utc);}
    StripeBillingOwnedFailedRenewalSetupAdapter setup(){return new StripeBillingOwnedFailedRenewalSetupAdapter(methods,steps,proofs,credentials,clients,digest,utc);}
    OwnedMethodBrowserService browser(OwnedFailedRenewalMethod.Reservation reservation){return new OwnedMethodBrowserService(reservation,methods,methodStore,new StripeOwnedMethodBrowserReader(credentials,clients,utc),new StripeOwnedMethodBrowserTokens(bean(StripeBillingGrantTokens.class)),utc);}
    StripeBillingOwnedFailedRenewalBindingAdapter binding(){return new StripeBillingOwnedFailedRenewalBindingAdapter(methods,credentials,clients,utc);}
    StripeBillingOwnedFailedRenewalMethodAdapter method(){return new StripeBillingOwnedFailedRenewalMethodAdapter(methods,steps,credentials,clients,digest,utc);}
    StripeBillingOwnedFailedRenewalMethodObserver methodObserver(){return new StripeBillingOwnedFailedRenewalMethodObserver(methods,credentials,clients,digest,utc);}
    StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter confirmer(){
        var receipts=new JdbcOwnedRecoveryConfirmStore(bean(JdbcTemplate.class),bean(EntityManagerFactory.class));
        var confirmations=new OwnedRecoveryConfirmService(methods,receipts,transaction,utc);
        var reader=new StripeBillingOwnedFailedRenewalRecoveryReader(methods,proofs,steps,credentials,clients,digest,utc);
        return new StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter(methods,reader,confirmations,credentials,clients,digest,utc);
    }
}
