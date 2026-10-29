package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.param.InvoiceCreatePreviewParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.InvoiceLineItemListParams;
import com.stripe.param.SubscriptionScheduleCreateParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCancellationProofStore;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership.*;

/** Stripe plan preview and original-operation mutations; HTTP never belongs in a DB transaction. */
public final class StripeBillingMutationAdapter {
    private final StripeBillingOwnership ownership;private final StripeBillingBindingPort bindings;
    private final SubscriptionPlanProofStore proofs;private final StripeCredentialResolver credentials;private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;private final Clock clock;
    public StripeBillingMutationAdapter(StripeBillingOwnership ownership,StripeBillingBindingPort bindings,SubscriptionPlanProofStore proofs,
        StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,Clock clock){
        this.ownership=ownership;this.bindings=bindings;this.proofs=proofs;this.credentials=credentials;this.clients=clients;this.verification=verification;this.clock=clock;
    }
    public ProviderQuote preview(QuoteRequest request){
        var c=request.command();var s=ownership.owned(c.account(),c.operation().subscriptionId());ownership.prices(c.account(),s);
        if(!Objects.equals(c.providerSubscriptionReference(),s.providerReference())||!s.billingPrice().equals(c.current())||!request.target().equals(c.target())||!s.billingPrice().equals(s.entitledPrice())||s.debt()!=null||s.cancellation()!=null||s.billingStatus()!=dev.genericpayments.platform.subscriptions.domain.Subscription.Status.ACTIVE||s.currentPeriod()==null||!s.currentPeriod().end().equals(s.paidThrough()))throw mismatch();
        var target=ownership.price(c.account(),s,request.target());var source=ownership.price(c.account(),s,s.billingPrice());
        if(!s.productId().equals(request.target().productId())||!s.billingPrice().regularAmount().currency().equals(request.target().regularAmount().currency()))throw mismatch();
        var customer=bindings.findCustomer(c.account().id(),s.siteId(),s.customerId()).orElseThrow(StripeBillingOwnership::mismatch);requireBinding(c.account(),s,customer);
        Quote saved=null;if(c.providerQuoteReference()!=null){
            saved=proofs.quote(c.account().id(),quoteId(c.providerQuoteReference())).orElseThrow(StripeBillingOwnership::mismatch);
            sameQuote(saved,s,c,request);
        }else if(s.pendingChange()!=null)throw mismatch();
        var anchor=saved==null?clock.instant().truncatedTo(ChronoUnit.SECONDS):saved.anchor();
        var expires=saved==null?anchor.plusSeconds(300):saved.expiresAt();
        if(!clock.instant().isBefore(expires)||anchor.isBefore(s.currentPeriod().start())||!anchor.isBefore(s.paidThrough()))throw mismatch();
        verification.verify(c.account());var client=clients.create(credentials.resolve(c.account()));
        try{
            var remoteCustomer=client.v1().customers().retrieve(customer.providerReference());
            if(!customer.providerReference().equals(remoteCustomer.getId())||!"customer".equals(remoteCustomer.getObject())||!Boolean.FALSE.equals(remoteCustomer.getLivemode())||Boolean.TRUE.equals(remoteCustomer.getDeleted()))throw mismatch();
            var remote=client.v1().subscriptions().retrieve(s.providerReference());requireCurrentRemote(remote,s,customer,source);
            if(!"active".equals(remote.getStatus())||remote.getSchedule()!=null||remote.getPendingUpdate()!=null||remote.getCancelAt()!=null||Boolean.TRUE.equals(remote.getCancelAtPeriodEnd()))throw mismatch();
            var item=remote.getItems().getData().getFirst();var period=new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(Instant.ofEpochSecond(item.getCurrentPeriodStart()),Instant.ofEpochSecond(item.getCurrentPeriodEnd()));
            if(!period.equals(s.currentPeriod())||saved!=null&&!saved.item().equals(item.getId()))throw mismatch();
            List<Line> lines=List.of();long due=0;
            if(request.timing()==ChangeTiming.IMMEDIATE){
                if(s.billingPrice().interval()!=request.target().interval())throw mismatch();
                var invoice=client.v1().invoices().createPreview(InvoiceCreatePreviewParams.builder().setCustomer(customer.providerReference()).setSubscription(s.providerReference())
                    .setSubscriptionDetails(InvoiceCreatePreviewParams.SubscriptionDetails.builder().setProrationDate(anchor.getEpochSecond())
                        .setProrationBehavior(InvoiceCreatePreviewParams.SubscriptionDetails.ProrationBehavior.ALWAYS_INVOICE)
                        .addItem(InvoiceCreatePreviewParams.SubscriptionDetails.Item.builder().setId(item.getId()).setPrice(target.providerPriceReference()).setQuantity(1L).build()).build()).build());
                if(!customer.providerReference().equals(invoice.getCustomer())||!Boolean.FALSE.equals(invoice.getLivemode())||!"invoice".equals(invoice.getObject())||invoice.getLines()==null||!Boolean.FALSE.equals(invoice.getLines().getHasMore()))throw mismatch();
                lines=prorationLines(invoice.getLines().getData(),s.providerReference(),item.getId(),source.providerPriceReference(),target.providerPriceReference(),target.providerProductReference(),new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(anchor,s.paidThrough()),s.billingPrice().regularAmount().currency(),false);
                due=Math.addExact(lines.get(0).amount(),lines.get(1).amount());
                if(due<0||!Objects.equals(invoice.getAmountDue(),due)||!Objects.equals(invoice.getTotal(),due)||!Objects.equals(invoice.getSubtotal(),due)||!noAdjustments(invoice))throw mismatch();
            }
            var quote=new Quote(saved==null?UUID.randomUUID():saved.id(),c.account().id(),s.siteId(),s.customerId(),s.id(),s.providerReference(),customer.providerReference(),s.billingPrice(),s.entitledPrice(),request.target(),source.providerPriceReference(),target.providerPriceReference(),target.providerProductReference(),item.getId(),period,s.paidThrough(),saved==null?s.revision():saved.revision(),c.operation().policyVersion(),request.timing(),anchor,expires,due,lines);
            StripeBillingSnapshotMapper.requireCreditedFunding(client,new StripeBillingSnapshotMapper.Ownership(c.account(),s,customer,ownership.prices(c.account(),s),null,null,ownership.planHistory(c.account(),s,proofs)),remote,quote,clock.instant());
            if(saved!=null){if(!saved.equals(quote))throw mismatch();}else proofs.saveQuote(quote);
            return new ProviderQuote(quote.reference(),due,request.target().regularAmount().minorUnits(),request.target().regularAmount().currency(),request.timing()==ChangeTiming.IMMEDIATE?anchor:period.end(),expires,true);
        }catch(StripeException|IllegalArgumentException unsafe){throw mismatch();}
    }
    public Reply change(Command c,ProviderOperationStore steps,Clock securityClock){
        var s=ownership.owned(c.account(),c.operation().subscriptionId());
        var o=ownership.lookupOperation(new Lookup(c.account(),s.id(),s.providerReference(),c.operation().providerOperationKey()));
        if(!o.equals(c.operation())||o.kind()!=SubscriptionOperation.Kind.CHANGE||!o.fingerprint().matches("v1\\.plan\\.[a-f0-9]{64}")||c.startTrial()||c.providerInvoiceReference()!=null||c.target()==null)throw mismatch();
        var q=proofs.quote(c.account().id(),quoteId(c.providerQuoteReference())).orElseThrow(StripeBillingOwnership::mismatch);
        sameQuote(q,s,c,new QuoteRequest(c,c.target(),q.timing()));ownership.prices(c.account(),s);
        if(s.pendingChange()==null||!s.pendingChange().operationId().equals(o.id())||!s.pendingChange().target().equals(q.target())||!s.pendingChange().effectiveAt().equals(q.timing()==ChangeTiming.IMMEDIATE?q.anchor():q.period().end())||s.billingStatus()!=dev.genericpayments.platform.subscriptions.domain.Subscription.Status.ACTIVE||s.debt()!=null||s.cancellation()!=null)throw mismatch();
        if(q.timing()==ChangeTiming.NEXT_PERIOD)return schedule(c,s,o,q,steps,securityClock);
        String fp=changeFingerprint(c.account(),o,q);
        var reserved=steps.reserve(c.account(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE,o.id(),o.providerOperationKey(),fp);
        if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED){var result=proofs.result(c.account().id(),o.id()).orElseThrow(StripeBillingOwnership::mismatch);if(!result.step().equals(reserved.id())||!result.quote().equals(q.id())||!result.reference().equals(s.providerReference()))throw mismatch();return new Reply(Outcome.ACCEPTED,s.providerReference(),null);}
        if(o.status()==SubscriptionOperation.Status.FAILED||o.status()==SubscriptionOperation.Status.SUCCEEDED)throw mismatch();
        boolean first=reserved.firstDispatchedAt()==null;if(first&&!clock.instant().isBefore(q.expiresAt()))throw mismatch();
        String owner=UUID.randomUUID().toString();var claim=steps.claim(reserved.id(),owner,securityClock.instant(),Duration.ofSeconds(60));if(claim.isEmpty())return new Reply(Outcome.UNKNOWN,null,null);var step=claim.get();
        verification.verify(c.account());var client=clients.create(credentials.resolve(c.account()));
        try{
            var customer=bindings.findCustomer(c.account().id(),s.siteId(),s.customerId()).orElseThrow(StripeBillingOwnership::mismatch);requireBinding(c.account(),s,customer);
            var remoteCustomer=client.v1().customers().retrieve(customer.providerReference());if(!customer.providerReference().equals(remoteCustomer.getId())||!"customer".equals(remoteCustomer.getObject())||!Boolean.FALSE.equals(remoteCustomer.getLivemode())||Boolean.TRUE.equals(remoteCustomer.getDeleted()))throw mismatch();
            var remote=client.v1().subscriptions().retrieve(s.providerReference());requireCurrentRemote(remote,s,customer,ownership.price(c.account(),s,q.source()));
            if(remote.getSchedule()!=null||!"active".equals(remote.getStatus())||remote.getCancelAt()!=null||Boolean.TRUE.equals(remote.getCancelAtPeriodEnd())||!q.item().equals(remote.getItems().getData().getFirst().getId())||!q.period().equals(itemPeriod(remote)))throw mismatch();
            if(remote.getPendingUpdate()!=null)throw mismatch();
            StripeBillingSnapshotMapper.requireCreditedFunding(client,new StripeBillingSnapshotMapper.Ownership(c.account(),s,customer,ownership.prices(c.account(),s),null,null,ownership.planHistory(c.account(),s,proofs)),remote,q,clock.instant());
            proofs.authorize(s,o,q);var key=new ProviderOperationGuard().requireDispatch(step,c.account(),fp,securityClock.instant());
            var response=client.v1().subscriptions().update(s.providerReference(),SubscriptionUpdateParams.builder().setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.PENDING_IF_INCOMPLETE)
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE).setProrationDate(q.anchor().getEpochSecond())
                .addItem(SubscriptionUpdateParams.Item.builder().setId(q.item()).setPrice(q.targetPrice()).setQuantity(1L).build()).build(),RequestOptions.builder().setIdempotencyKey(key).build());
            if(!s.providerReference().equals(response.getId())||!Boolean.FALSE.equals(response.getLivemode())||!q.customerReference().equals(response.getCustomer())||!q.period().equals(itemPeriod(response)))throw mismatch();
            String invoice=response.getLatestInvoice();if(invoice==null||!invoice.matches("in_[A-Za-z0-9]{1,252}"))throw mismatch();
            Long expiry=null;var pending=response.getPendingUpdate();if(pending!=null){if(pending.getExpiresAt()==null||pending.getExpiresAt()<=step.firstDispatchedAt().getEpochSecond()||pending.getSubscriptionItems()==null||pending.getSubscriptionItems().size()!=1)throw mismatch();var item=pending.getSubscriptionItems().getFirst();if(!q.item().equals(item.getId())||!Objects.equals(1L,item.getQuantity())||item.getPrice()==null||!q.targetPrice().equals(item.getPrice().getId()))throw mismatch();expiry=pending.getExpiresAt();}
            var actual=client.v1().invoices().retrieve(invoice);requireInvoice(actual,q,invoice);
            if(!StripeBillingOwnership.inWindow(actual.getCreated(),step))throw mismatch();
            var page=client.v1().invoices().lineItems().list(invoice,InvoiceLineItemListParams.builder().setLimit(10L).build());
            if(page==null||!"list".equals(page.getObject())||!Boolean.FALSE.equals(page.getHasMore())||page.getData()==null||page.getData().stream().anyMatch(l->!invoice.equals(l.getInvoice())||!s.providerReference().equals(l.getSubscription())))throw mismatch();
            if(!q.lines().equals(prorationLines(page.getData(),q.subscriptionReference(),q.item(),q.sourcePrice(),q.targetPrice(),q.product(),new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(q.anchor(),q.paidThrough()),q.target().regularAmount().currency(),true)))throw mismatch();
            var fresh=ownership.lookupOperation(new Lookup(c.account(),s.id(),s.providerReference(),o.providerOperationKey()));if(!fresh.equals(o))throw mismatch();
            var credit=page.getData().stream().filter(l->q.sourcePrice().equals(l.getPricing().getPriceDetails().getPrice())).findFirst().orElseThrow();
            var debit=page.getData().stream().filter(l->q.targetPrice().equals(l.getPricing().getPriceDetails().getPrice())).findFirst().orElseThrow();
            proofs.recordResult(s,o,q,step,owner,invoice,expiry,credit.getId(),debit.getId());return new Reply(Outcome.ACCEPTED,s.providerReference(),null);
        }catch(StripeException|RuntimeException uncertain){steps.unknown(step.id(),owner,step.version());return new Reply(Outcome.UNKNOWN,null,null);}
    }
    private Reply schedule(Command c,dev.genericpayments.platform.subscriptions.domain.Subscription s,SubscriptionOperation o,Quote q,ProviderOperationStore steps,Clock securityClock){
        var binding=proofs.schedule(q.account(),o.id()).orElse(null);
        boolean createdNow=binding==null;
        if(binding!=null&&binding.updateStep()!=null){
            requireScheduleSteps(c,s,o,q,binding,steps);return new Reply(Outcome.ACCEPTED,s.providerReference(),null);
        }
        var client=clients.create(credentials.resolve(c.account()));
        if(binding==null){
            var reserved=steps.reserve(c.account(),ProviderOperation.Kind.SCHEDULE_CREATE,o.id(),o.providerOperationKey()+":SCHEDULE_CREATE",scheduleFingerprint(c.account(),o,q,null,ProviderOperation.Kind.SCHEDULE_CREATE));
            if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED)throw mismatch();
            boolean first=reserved.firstDispatchedAt()==null;if(first&&!clock.instant().isBefore(q.expiresAt()))throw mismatch();
            String owner=UUID.randomUUID().toString();var claim=steps.claim(reserved.id(),owner,securityClock.instant(),Duration.ofSeconds(60));if(claim.isEmpty())return new Reply(Outcome.UNKNOWN,null,null);var step=claim.get();
            try{
                readScheduleSubscription(c,s,q,client,first?null:"original-key-replay");proofs.authorize(s,o,q);
                String key=new ProviderOperationGuard().requireDispatch(step,c.account(),step.fingerprint(),securityClock.instant());
                var created=client.v1().subscriptionSchedules().create(SubscriptionScheduleCreateParams.builder().setFromSubscription(q.subscriptionReference()).build(),RequestOptions.builder().setIdempotencyKey(key).build());
                requireSchedule(created,q,null,false);
                binding=proofs.recordSchedule(s,o,q,step,owner,created.getId(),created.getCurrentPhase().getStartDate(),sourcePhase(created.getPhases().getFirst()));
            }catch(StripeException|RuntimeException unknown){steps.unknown(step.id(),owner,step.version());return new Reply(Outcome.UNKNOWN,null,null);}
        }
        String fp=scheduleFingerprint(c.account(),o,q,binding,ProviderOperation.Kind.SCHEDULE_UPDATE);
        var reserved=steps.reserve(c.account(),ProviderOperation.Kind.SCHEDULE_UPDATE,o.id(),o.providerOperationKey()+":SCHEDULE_UPDATE",fp);
        if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED)throw mismatch();
        String owner=UUID.randomUUID().toString();var claim=steps.claim(reserved.id(),owner,securityClock.instant(),Duration.ofSeconds(60));if(claim.isEmpty())return new Reply(Outcome.UNKNOWN,null,null);var step=claim.get();
        try{
            // The initial create already freshly read the Subscription. Existing bindings must do so too.
            if(!createdNow)readScheduleSubscription(c,s,q,client,binding.reference());
            if(!binding.creationStep().equals(steps.find(q.account(),o.providerOperationKey()+":SCHEDULE_CREATE").orElseThrow(StripeBillingOwnership::mismatch).id()))throw mismatch();
            var current=client.v1().subscriptionSchedules().retrieve(binding.reference());
            requireSchedule(current,q,binding,current.getPhases()!=null&&current.getPhases().size()==2);
            if(reserved.firstDispatchedAt()==null&&current.getPhases().size()!=1)throw mismatch();
            proofs.authorize(s,o,q);String key=new ProviderOperationGuard().requireDispatch(step,c.account(),fp,securityClock.instant());
            var phase=binding.sourcePhase();var preserve=SubscriptionScheduleUpdateParams.Phase.builder().setStartDate(binding.phaseStart()).setEndDate(q.period().end().getEpochSecond())
                .setProrationBehavior(SubscriptionScheduleUpdateParams.Phase.ProrationBehavior.valueOf(phase.prorationBehavior().toUpperCase(Locale.ROOT)))
                .setCollectionMethod(SubscriptionScheduleUpdateParams.Phase.CollectionMethod.valueOf(phase.collectionMethod().toUpperCase(Locale.ROOT)))
                .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder().setPrice(q.sourcePrice()).setQuantity(1L).build());
            if(phase.defaultPaymentMethod()!=null)preserve.setDefaultPaymentMethod(phase.defaultPaymentMethod());
            var target=SubscriptionScheduleUpdateParams.Phase.builder().setStartDate(q.period().end().getEpochSecond())
                .setProrationBehavior(SubscriptionScheduleUpdateParams.Phase.ProrationBehavior.NONE)
                .setDuration(SubscriptionScheduleUpdateParams.Phase.Duration.builder().setInterval(SubscriptionScheduleUpdateParams.Phase.Duration.Interval.valueOf(q.target().interval().name())).setIntervalCount(1L).build())
                .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder().setPrice(q.targetPrice()).setQuantity(1L).build());
            if(phase.defaultPaymentMethod()!=null)target.setDefaultPaymentMethod(phase.defaultPaymentMethod());
            var response=client.v1().subscriptionSchedules().update(binding.reference(),SubscriptionScheduleUpdateParams.builder().setEndBehavior(SubscriptionScheduleUpdateParams.EndBehavior.RELEASE)
                .setProrationBehavior(SubscriptionScheduleUpdateParams.ProrationBehavior.NONE).addPhase(preserve.build()).addPhase(target.build()).build(),RequestOptions.builder().setIdempotencyKey(key).build());
            requireSchedule(response,q,binding,true);var fresh=client.v1().subscriptionSchedules().retrieve(binding.reference());requireSchedule(fresh,q,binding,true);
            if(!response.getPhases().equals(fresh.getPhases())||!response.getCurrentPhase().equals(fresh.getCurrentPhase()))throw mismatch();
            proofs.recordScheduleUpdate(s,o,binding,step,owner,fresh.getPhases().getLast().getEndDate());return new Reply(Outcome.ACCEPTED,s.providerReference(),null);
        }catch(StripeException|RuntimeException unknown){steps.unknown(step.id(),owner,step.version());return new Reply(Outcome.UNKNOWN,null,null);}
    }
    public Reply clear(Command c,ProviderOperationStore steps,Clock securityClock){
        var s=ownership.owned(c.account(),c.operation().subscriptionId());var o=ownership.lookupOperation(new Lookup(c.account(),s.id(),s.providerReference(),c.operation().providerOperationKey()));
        if(!o.equals(c.operation())||o.kind()!=SubscriptionOperation.Kind.CLEAR_CHANGE||c.target()!=null||c.providerQuoteReference()!=null||c.providerInvoiceReference()!=null||c.startTrial()||s.pendingChange()==null||s.pendingChange().state()!=dev.genericpayments.platform.subscriptions.domain.Subscription.ChangeState.SCHEDULED||!clock.instant().isBefore(s.pendingChange().effectiveAt()))throw mismatch();
        var binding=proofs.schedule(c.account().id(),s.pendingChange().operationId()).orElseThrow(StripeBillingOwnership::mismatch);var q=proofs.quote(c.account().id(),binding.quote()).orElseThrow(StripeBillingOwnership::mismatch);
        if(binding.updateStep()==null||!binding.subscription().equals(s.id())||!q.source().equals(s.billingPrice())||!q.entitled().equals(s.entitledPrice())||!q.target().equals(s.pendingChange().target())||!q.period().end().equals(s.pendingChange().effectiveAt())||!q.period().equals(s.currentPeriod()))throw mismatch();
        var original=ownership.lookupOperation(new Lookup(c.account(),s.id(),s.providerReference(),"subscription:"+c.account().id().value()+":"+binding.operation()+":CHANGE"));requireScheduleSteps(c,s,original,q,binding,steps);
        String fp=scheduleFingerprint(c.account(),o,q,binding,ProviderOperation.Kind.SCHEDULE_RELEASE);var reserved=steps.reserve(c.account(),ProviderOperation.Kind.SCHEDULE_RELEASE,o.id(),o.providerOperationKey()+":SCHEDULE_RELEASE",fp);
        if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED){if(!Objects.equals(binding.clearOperation(),o.id())||!Objects.equals(binding.releaseStep(),reserved.id())||!reserved.providerReference().equals(binding.reference()))throw mismatch();return new Reply(Outcome.ACCEPTED,s.providerReference(),null);}
        String owner=UUID.randomUUID().toString();var claim=steps.claim(reserved.id(),owner,securityClock.instant(),Duration.ofSeconds(60));if(claim.isEmpty())return new Reply(Outcome.UNKNOWN,null,null);var step=claim.get();var client=clients.create(credentials.resolve(c.account()));
        try{
            boolean replay=reserved.firstDispatchedAt()!=null;
            readScheduleSubscription(c,s,q,client,binding.reference(),replay);var current=client.v1().subscriptionSchedules().retrieve(binding.reference());
            if(replay&&"released".equals(current.getStatus()))requireReleased(current,q,binding,step,securityClock.instant());else requireSchedule(current,q,binding,true);
            proofs.authorize(s,o,q);String key=new ProviderOperationGuard().requireDispatch(step,c.account(),fp,securityClock.instant());
            var response=client.v1().subscriptionSchedules().release(binding.reference(),RequestOptions.builder().setIdempotencyKey(key).build());
            requireReleased(response,q,binding,step,securityClock.instant());proofs.recordRelease(s,o,binding,step,owner);return new Reply(Outcome.ACCEPTED,s.providerReference(),null);
        }catch(StripeException|RuntimeException unknown){steps.unknown(step.id(),owner,step.version());return new Reply(Outcome.UNKNOWN,null,null);}
    }
    /** Only explicit original CANCEL/WITHDRAW commands enter here; reads never call this method. */
    public Reply cancel(Command c,boolean withdraw,ProviderOperationStore steps,
                        SubscriptionCancellationProofStore cancellations,Clock securityClock){
        var s=ownership.owned(c.account(),c.operation().subscriptionId());
        var o=ownership.lookupOperation(new Lookup(c.account(),s.id(),s.providerReference(),c.operation().providerOperationKey()));
        boolean immediate=!withdraw&&SubscriptionCancellationTransaction.immediate(o);
        var mode=withdraw?SubscriptionCancellationProofStore.Mode.WITHDRAW:immediate?SubscriptionCancellationProofStore.Mode.IMMEDIATE:SubscriptionCancellationProofStore.Mode.PERIOD_END;
        String expected=withdraw?"v1.cancel.withdraw.":immediate?"v1.cancel.immediate.none.":"v1.cancel.period_end.";
        if(!o.equals(c.operation())||o.kind()!=(withdraw?SubscriptionOperation.Kind.WITHDRAW_CANCEL:SubscriptionOperation.Kind.CANCEL)
            ||!o.fingerprint().startsWith(expected)||!s.billingPrice().equals(c.current())
            ||!Objects.equals(s.providerReference(),c.providerSubscriptionReference())||c.target()!=null
            ||c.providerInvoiceReference()!=null||c.providerQuoteReference()!=null||c.startTrial()||c.recurringConsent())throw mismatch();
        var boundary=SubscriptionCancellationTransaction.boundary(o).orElse(null);
        if(!immediate&&(boundary==null||boundary.getNano()!=0))throw mismatch();
        var customer=bindings.findCustomer(c.account().id(),s.siteId(),s.customerId()).orElseThrow(StripeBillingOwnership::mismatch);
        requireBinding(c.account(),s,customer);
        var saved=cancellations.receipt(c.account().id(),o.id()).orElse(null);
        if(saved!=null){requireCancellationReceipt(c.account(),s,o,saved,steps,customer);return new Reply(Outcome.ACCEPTED,s.providerReference(),null);}
        var prior=withdraw?cancellations.activeSchedule(c.account().id(),s.id(),boundary).orElse(null):null;
        var observed=withdraw?cancellations.observedSchedule(c.account().id(),s.id(),boundary).orElse(null):null;
        if(observed!=null&&(observed.event()==null||observed.revision()!=o.expectedRevision()||!boundary.equals(observed.boundary())))throw mismatch();
        if(prior!=null&&(prior.mode()!=SubscriptionCancellationProofStore.Mode.PERIOD_END||!prior.account().equals(c.account().id())
            ||!prior.subscription().equals(s.id())||!prior.reference().equals(s.providerReference())||!boundary.equals(prior.boundary())))throw mismatch();
        if(withdraw){
            var original=steps.find(c.account().id(),o.providerOperationKey()).orElse(null);
            if(original!=null){
                // A lost response retains exactly its original predecessor, fingerprint and key.
                if(prior!=null&&original.fingerprint().equals(cancellationFingerprint(c.account(),s,o,customer,mode,boundary,prior.operation(),null)))observed=null;
                else if(observed!=null&&original.fingerprint().equals(cancellationFingerprint(c.account(),s,o,customer,mode,boundary,null,observed)))prior=null;
                else throw mismatch();
            }else if(observed!=null)prior=null;
            if(prior==null&&observed==null)throw mismatch();
        }
        String fp=cancellationFingerprint(c.account(),s,o,customer,mode,boundary,prior==null?null:prior.operation(),observed);
        if(!Set.of(SubscriptionOperation.Status.PROCESSING,SubscriptionOperation.Status.UNKNOWN).contains(o.status())
            ||s.revision()!=o.expectedRevision()||!s.nonTerminal()||s.pendingChange()!=null||!immediate&&!clock.instant().isBefore(boundary))throw mismatch();
        if(withdraw){SubscriptionCancellationRules.requireWithdrawable(s,clock.instant());if(!boundary.equals(s.cancellation().effectiveAt()))throw mismatch();}
        else if(!immediate&&(s.cancellation()!=null||!boundary.equals(SubscriptionCancellationRules.liveBoundary(s,clock.instant()))))throw mismatch();
        var reserved=steps.reserve(c.account(),immediate?ProviderOperation.Kind.SUBSCRIPTION_CANCEL:ProviderOperation.Kind.SUBSCRIPTION_UPDATE,o.id(),o.providerOperationKey(),fp);
        if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED)throw mismatch();
        String owner=UUID.randomUUID().toString();var claim=steps.claim(reserved.id(),owner,securityClock.instant(),Duration.ofSeconds(60));
        if(claim.isEmpty())return new Reply(Outcome.UNKNOWN,null,null);var step=claim.get();
        try{
            verification.verify(c.account());var client=clients.create(credentials.resolve(c.account()));
            var remoteCustomer=client.v1().customers().retrieve(customer.providerReference());
            if(!customer.providerReference().equals(remoteCustomer.getId())||!"customer".equals(remoteCustomer.getObject())
                ||!Boolean.FALSE.equals(remoteCustomer.getLivemode())||Boolean.TRUE.equals(remoteCustomer.getDeleted()))throw mismatch();
            var remote=client.v1().subscriptions().retrieve(s.providerReference());
            requireCancellationRemote(remote,s,customer,ownership.price(c.account(),s,s.billingPrice()),boundary);
            boolean replay=reserved.firstDispatchedAt()!=null;
            if(immediate){
                requireSafeImmediateInventory(client,s,customer);
            }else if(withdraw){
                boolean nativeEnd=observed!=null&&remote.getCancelAt()==null&&Boolean.TRUE.equals(remote.getCancelAtPeriodEnd())&&boundary.equals(itemPeriod(remote).end());
                if(!Objects.equals(remote.getCancelAt(),boundary.getEpochSecond())&&!nativeEnd&&!(replay&&remote.getCancelAt()==null&&!Boolean.TRUE.equals(remote.getCancelAtPeriodEnd())))throw mismatch();
            }else if(remote.getCancelAt()!=null&&!(replay&&remote.getCancelAt()==boundary.getEpochSecond())||Boolean.TRUE.equals(remote.getCancelAtPeriodEnd())&&remote.getCancelAt()==null)throw mismatch();
            cancellations.authorize(s,o);
            if(observed!=null)cancellations.requireObserved(s,observed);
            if(!immediate&&!clock.instant().isBefore(boundary))throw mismatch();
            String key=new ProviderOperationGuard().requireDispatch(step,c.account(),fp,securityClock.instant());
            if(immediate){
                var response=client.v1().subscriptions().cancel(s.providerReference(),com.stripe.param.SubscriptionCancelParams.builder().setInvoiceNow(false).setProrate(false).build(),RequestOptions.builder().setIdempotencyKey(key).build());
                requireCurrentRemote(response,s,customer,ownership.price(c.account(),s,s.billingPrice()));
                var received=securityClock.instant().truncatedTo(ChronoUnit.MICROS);
                if(!s.providerReference().equals(response.getId())||!"canceled".equals(response.getStatus())||response.getTrialStart()!=null||response.getTrialEnd()!=null
                    ||response.getSchedule()!=null||response.getPendingUpdate()!=null||!StripeBillingOwnership.inWindow(response.getCanceledAt(),step)
                    ||response.getEndedAt()==null||response.getEndedAt()<step.firstDispatchedAt().getEpochSecond()||response.getEndedAt()>received.getEpochSecond())throw mismatch();
                cancellations.record(s,o,step,owner,mode,null,null,received);
                return new Reply(Outcome.ACCEPTED,s.providerReference(),null);
            }
            var params=SubscriptionUpdateParams.builder().setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE);
            if(withdraw)params.setCancelAt(com.stripe.param.common.EmptyParam.EMPTY).setCancelAtPeriodEnd(false);
            else params.setCancelAt(boundary.getEpochSecond());
            var response=client.v1().subscriptions().update(s.providerReference(),params.build(),RequestOptions.builder().setIdempotencyKey(key).build());
            requireCancellationRemote(response,s,customer,ownership.price(c.account(),s,s.billingPrice()),boundary);
            if(withdraw?(response.getCancelAt()!=null||!Boolean.FALSE.equals(response.getCancelAtPeriodEnd())):!Objects.equals(response.getCancelAt(),boundary.getEpochSecond()))throw mismatch();
            // Receiving the complete original response before the deadline proves the write finished
            // before it. Dispatch time or a later status-null read cannot replace this evidence.
            var received=securityClock.instant().truncatedTo(ChronoUnit.MICROS);if(!received.isBefore(boundary))throw mismatch();
            var fresh=ownership.lookupOperation(new Lookup(c.account(),s.id(),s.providerReference(),o.providerOperationKey()));if(!fresh.equals(o))throw mismatch();
            if(observed==null)cancellations.record(s,o,step,owner,mode,boundary,prior==null?null:prior.operation(),received);
            else cancellations.recordObserved(s,o,step,owner,observed,received);
            return new Reply(Outcome.ACCEPTED,s.providerReference(),null);
        }catch(StripeException|RuntimeException uncertain){steps.unknown(step.id(),owner,step.version());return new Reply(Outcome.UNKNOWN,null,null);}
    }
    private static void requireCancellationRemote(com.stripe.model.Subscription r,dev.genericpayments.platform.subscriptions.domain.Subscription s,
        StripeBillingBindingPort.CustomerBinding customer,StripeBillingSnapshotMapper.PublishedPrice price,Instant boundary){
        requireCurrentRemote(r,s,customer,price);
        if(!s.providerReference().equals(r.getId())||Set.of("canceled","incomplete_expired").contains(r.getStatus())
            ||r.getSchedule()!=null||r.getPendingUpdate()!=null||!itemPeriod(r).equals(s.currentPeriod()))throw mismatch();
        if(boundary==null){if(s.trial()!=null||r.getTrialStart()!=null||r.getTrialEnd()!=null)throw mismatch();}
        else if(s.trial()!=null){
            if((r.getTrialStart()!=null||r.getTrialEnd()!=null)&&(!Objects.equals(r.getTrialStart(),s.trial().start().getEpochSecond())||!Objects.equals(r.getTrialEnd(),s.trial().end().getEpochSecond())))throw mismatch();
            if(!boundary.equals(s.trial().end())&&s.billingStatus()==dev.genericpayments.platform.subscriptions.domain.Subscription.Status.TRIALING)throw mismatch();
        }else if(r.getTrialStart()!=null||r.getTrialEnd()!=null)throw mismatch();
    }
    /** DELETE can disable collection on other customer invoices; a Sub-only inventory is insufficient. */
    private static void requireSafeImmediateInventory(StripeClient client,dev.genericpayments.platform.subscriptions.domain.Subscription s,
        StripeBillingBindingPort.CustomerBinding customer)throws StripeException{
        var pending=client.v1().invoiceItems().list(com.stripe.param.InvoiceItemListParams.builder().setCustomer(customer.providerReference()).setPending(true).setLimit(10L).build());
        if(pending==null||!"list".equals(pending.getObject())||!Boolean.FALSE.equals(pending.getHasMore())||pending.getData()==null||!pending.getData().isEmpty())throw mismatch();
        String after=null;var seen=new HashSet<String>();
        for(int page=0;page<10;page++){
            var values=client.v1().invoices().list(com.stripe.param.InvoiceListParams.builder().setCustomer(customer.providerReference()).setLimit(10L).setStartingAfter(after).build());
            if(values==null||!"list".equals(values.getObject())||values.getHasMore()==null||values.getData()==null)throw mismatch();
            for(var invoice:values.getData()){
                if(invoice.getId()==null||!invoice.getId().matches("in_[A-Za-z0-9]{1,252}")||!seen.add(invoice.getId())||!"invoice".equals(invoice.getObject())
                    ||!Boolean.FALSE.equals(invoice.getLivemode())||!customer.providerReference().equals(invoice.getCustomer())||invoice.getStatus()==null)throw mismatch();
                if(Set.of("paid","void").contains(invoice.getStatus()))continue;
                if(!Set.of("open","draft","uncollectible").contains(invoice.getStatus())||invoice.getParent()==null||invoice.getParent().getSubscriptionDetails()==null
                    ||!s.providerReference().equals(invoice.getParent().getSubscriptionDetails().getSubscription())||!noAdjustments(invoice)
                    ||!Objects.equals(invoice.getAmountDue(),s.billingPrice().regularAmount().minorUnits())||!s.billingPrice().regularAmount().currency().equalsIgnoreCase(invoice.getCurrency()))throw mismatch();
            }
            if(!values.getHasMore())return;
            if(values.getData().isEmpty())throw mismatch();after=values.getData().getLast().getId();
        }
        throw mismatch();
    }
    public static String cancellationFingerprint(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount a,
        dev.genericpayments.platform.subscriptions.domain.Subscription s,SubscriptionOperation o,StripeBillingBindingPort.CustomerBinding customer,
        SubscriptionCancellationProofStore.Mode mode,Instant boundary,UUID prior){
        return cancellationFingerprint(a,s,o,customer,mode,boundary,prior,null);
    }
    public static String cancellationFingerprint(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount a,
        dev.genericpayments.platform.subscriptions.domain.Subscription s,SubscriptionOperation o,StripeBillingBindingPort.CustomerBinding customer,
        SubscriptionCancellationProofStore.Mode mode,Instant boundary,UUID prior,SubscriptionCancellationProofStore.ObservedSchedule observed){
        if(observed!=null)return StripeRequestFingerprint.of(StripeBillingGrantTokens::hash,a,"CANCELLATION_OBSERVED_V1",o.id().toString(),o.providerOperationKey(),o.fingerprint(),
            Long.toString(o.expectedRevision()),Long.toString(o.policyVersion()),s.id().toString(),s.providerReference(),customer.providerReference(),mode.name(),Objects.toString(boundary,null),
            observed.event().toString(),Long.toString(observed.revision()),observed.boundary().toString(),"proration:none");
        return StripeRequestFingerprint.of(StripeBillingGrantTokens::hash,a,"CANCELLATION_V1",o.id().toString(),o.providerOperationKey(),o.fingerprint(),
            Long.toString(o.expectedRevision()),Long.toString(o.policyVersion()),s.id().toString(),s.providerReference(),customer.providerReference(),
            mode.name(),Objects.toString(boundary,null),Objects.toString(prior,null),"proration:none");
    }
    static void requireCancellationReceipt(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount a,
        dev.genericpayments.platform.subscriptions.domain.Subscription s,SubscriptionOperation o,SubscriptionCancellationProofStore.Receipt receipt,
        ProviderOperationStore steps,StripeBillingBindingPort.CustomerBinding customer){
        var step=steps.findById(receipt.step()).orElseThrow(StripeBillingOwnership::mismatch);
        if(!receipt.operation().equals(o.id())||!receipt.subscription().equals(s.id())||!receipt.account().equals(a.id())||!receipt.reference().equals(s.providerReference())
            ||step.kind()!=(receipt.mode()==SubscriptionCancellationProofStore.Mode.IMMEDIATE?ProviderOperation.Kind.SUBSCRIPTION_CANCEL:ProviderOperation.Kind.SUBSCRIPTION_UPDATE)||step.status()!=ProviderOperation.Status.ACKNOWLEDGED||!step.resourceId().equals(o.id())
            ||!step.accountId().equals(a.id())||!step.externalAccountId().equals(a.externalAccountId())||!step.credentialsRef().equals(a.credentialsRef())
            ||!step.operationKey().equals(o.providerOperationKey())||!step.providerReference().equals(s.providerReference())
            ||receipt.acknowledgedAt()==null||step.firstDispatchedAt()==null||step.retryBefore()==null||receipt.acknowledgedAt().isBefore(step.firstDispatchedAt())||!receipt.acknowledgedAt().isBefore(step.retryBefore())
            ||!Objects.equals(receipt.boundary(),SubscriptionCancellationTransaction.boundary(o).orElse(null))||receipt.mode()!=SubscriptionCancellationProofStore.Mode.IMMEDIATE&&!receipt.acknowledgedAt().isBefore(receipt.boundary())
            ||receipt.observed()!=null&&(receipt.mode()!=SubscriptionCancellationProofStore.Mode.WITHDRAW||receipt.priorOperation()!=null
                ||receipt.observed().revision()!=o.expectedRevision()||!receipt.boundary().equals(receipt.observed().boundary()))
            ||!step.fingerprint().equals(cancellationFingerprint(a,s,o,customer,receipt.mode(),receipt.boundary(),receipt.priorOperation(),receipt.observed())))throw mismatch();
    }
    private void readScheduleSubscription(Command c,dev.genericpayments.platform.subscriptions.domain.Subscription s,Quote q,StripeClient client,String allowedSchedule)throws StripeException{
        readScheduleSubscription(c,s,q,client,allowedSchedule,false);
    }
    private void readScheduleSubscription(Command c,dev.genericpayments.platform.subscriptions.domain.Subscription s,Quote q,StripeClient client,String allowedSchedule,boolean allowReleased)throws StripeException{
        verification.verify(c.account());var customer=bindings.findCustomer(c.account().id(),s.siteId(),s.customerId()).orElseThrow(StripeBillingOwnership::mismatch);requireBinding(c.account(),s,customer);
        var remoteCustomer=client.v1().customers().retrieve(customer.providerReference());if(!q.customerReference().equals(remoteCustomer.getId())||!Boolean.FALSE.equals(remoteCustomer.getLivemode())||Boolean.TRUE.equals(remoteCustomer.getDeleted()))throw mismatch();
        var remote=client.v1().subscriptions().retrieve(q.subscriptionReference());requireCurrentRemote(remote,s,customer,ownership.price(c.account(),s,q.source()));
        if(!"active".equals(remote.getStatus())||!q.period().equals(itemPeriod(remote))||!q.item().equals(remote.getItems().getData().getFirst().getId())||remote.getPendingUpdate()!=null||remote.getCancelAt()!=null||Boolean.TRUE.equals(remote.getCancelAtPeriodEnd())||!"original-key-replay".equals(allowedSchedule)&&!Objects.equals(remote.getSchedule(),allowedSchedule)&&!(allowReleased&&remote.getSchedule()==null))throw mismatch();
    }
    public static String scheduleFingerprint(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount a,SubscriptionOperation o,Quote q,SubscriptionPlanProofStore.Schedule binding,ProviderOperation.Kind kind){return StripeRequestFingerprint.of(StripeBillingGrantTokens::hash,a,"SCHEDULE_V1",kind.name(),o.id().toString(),o.providerOperationKey(),o.fingerprint(),q.toString(),binding==null?null:binding.reference(),binding==null?null:Long.toString(binding.phaseStart()),binding==null?null:binding.creationStep().toString(),binding==null?null:binding.sourcePhase().toString(),"end:release","top_proration:none","target_proration:none","target_collection:inherit_auto","quantity:1","duration_count:1");}
    static void requireScheduleSteps(Command c,dev.genericpayments.platform.subscriptions.domain.Subscription s,SubscriptionOperation original,Quote q,SubscriptionPlanProofStore.Schedule binding,ProviderOperationStore steps){
        for(var kind:List.of(ProviderOperation.Kind.SCHEDULE_CREATE,ProviderOperation.Kind.SCHEDULE_UPDATE)){
            var id=kind==ProviderOperation.Kind.SCHEDULE_CREATE?binding.creationStep():binding.updateStep();var step=steps.findById(id).orElseThrow(StripeBillingOwnership::mismatch);
            if(!binding.operation().equals(original.id())||!binding.account().equals(c.account().id())||!binding.subscription().equals(s.id())||!binding.quote().equals(q.id())||step.kind()!=kind||step.status()!=ProviderOperation.Status.ACKNOWLEDGED||!step.accountId().equals(c.account().id())||!step.resourceId().equals(original.id())||!step.operationKey().equals(original.providerOperationKey()+":"+kind)||!step.providerReference().equals(binding.reference())||!step.externalAccountId().equals(c.account().externalAccountId())||!step.credentialsRef().equals(c.account().credentialsRef())||!step.fingerprint().equals(scheduleFingerprint(c.account(),original,q,kind==ProviderOperation.Kind.SCHEDULE_CREATE?null:binding,kind)))throw mismatch();
        }
    }
    static void requireReleased(com.stripe.model.SubscriptionSchedule value,Quote q,SubscriptionPlanProofStore.Schedule binding,ProviderOperation original,Instant at){
        if(value==null||!binding.reference().equals(value.getId())||!"subscription_schedule".equals(value.getObject())||!Boolean.FALSE.equals(value.getLivemode())
            ||!q.customerReference().equals(value.getCustomer())||!"released".equals(value.getStatus())||value.getSubscription()!=null||!q.subscriptionReference().equals(value.getReleasedSubscription())
            ||!StripeBillingOwnership.inWindow(value.getReleasedAt(),original)||value.getReleasedAt()>=q.period().end().getEpochSecond()||value.getReleasedAt()>at.getEpochSecond())throw mismatch();
    }
    static void requireSchedule(com.stripe.model.SubscriptionSchedule value,Quote q,SubscriptionPlanProofStore.Schedule binding,boolean configured){
        if(value==null||value.getId()==null||!value.getId().matches("sub_sched_[A-Za-z0-9]{1,245}")
            ||binding!=null&&!binding.reference().equals(value.getId())||!"subscription_schedule".equals(value.getObject())||!Boolean.FALSE.equals(value.getLivemode())
            ||!q.customerReference().equals(value.getCustomer())||!q.subscriptionReference().equals(value.getSubscription())
            ||!"active".equals(value.getStatus())||!"release".equals(value.getEndBehavior())
            ||value.getCurrentPhase()==null||value.getCurrentPhase().getStartDate()==null||value.getCurrentPhase().getStartDate()<=0
            ||value.getCurrentPhase().getEndDate()==null||value.getCurrentPhase().getEndDate()!=q.period().end().getEpochSecond()
            ||value.getPhases()==null||value.getPhases().size()!=(configured?2:1))throw mismatch();
        var defaults=value.getDefaultSettings();
        if(defaults==null||!"charge_automatically".equals(defaults.getCollectionMethod())||defaults.getApplicationFeePercent()!=null
            ||defaults.getTransferData()!=null||defaults.getOnBehalfOf()!=null||defaults.getBillingThresholds()!=null
            ||defaults.getAutomaticTax()!=null&&Boolean.TRUE.equals(defaults.getAutomaticTax().getEnabled()))throw mismatch();
        long start=value.getCurrentPhase().getStartDate();if(binding!=null&&binding.phaseStart()!=start)throw mismatch();
        for(int n=0;n<value.getPhases().size();n++){
            var p=value.getPhases().get(n);var expected=n==0?q.sourcePrice():q.targetPrice();
            if(p.getStartDate()==null||p.getStartDate()!=(n==0?start:q.period().end().getEpochSecond())||p.getEndDate()==null
                ||p.getEndDate()<=(n==0?start:q.period().end().getEpochSecond())||n==0&&p.getEndDate()!=q.period().end().getEpochSecond()
                ||n==1&&!"none".equals(p.getProrationBehavior())||p.getItems()==null||p.getItems().size()!=1
                ||!expected.equals(p.getItems().getFirst().getPrice())||!Objects.equals(1L,p.getItems().getFirst().getQuantity())
                ||p.getCollectionMethod()!=null&&!"charge_automatically".equals(p.getCollectionMethod())
                ||p.getCurrency()!=null&&!q.target().regularAmount().currency().equalsIgnoreCase(p.getCurrency())
                ||p.getApplicationFeePercent()!=null||p.getBillingThresholds()!=null||p.getTransferData()!=null||p.getOnBehalfOf()!=null
                ||p.getTrialEnd()!=null||Boolean.TRUE.equals(p.getTrial())||p.getAutomaticTax()!=null&&Boolean.TRUE.equals(p.getAutomaticTax().getEnabled())
                ||nonempty(p.getDiscounts())||nonempty(p.getDefaultTaxRates())||nonempty(p.getAddInvoiceItems())
                ||p.getMetadata()!=null&&!p.getMetadata().isEmpty()||p.getInvoiceSettings()!=null||p.getBillingCycleAnchor()!=null)throw mismatch();
            var item=p.getItems().getFirst();
            if(item.getBillingThresholds()!=null||nonempty(item.getDiscounts())||nonempty(item.getTaxRates())||item.getMetadata()!=null&&!item.getMetadata().isEmpty())throw mismatch();
            if(n==0){var effective=sourcePhase(p);if(binding!=null&&!binding.sourcePhase().equals(effective))throw mismatch();}
            else if(binding==null||!Objects.equals(binding.sourcePhase().defaultPaymentMethod(),p.getDefaultPaymentMethod()))throw mismatch();
        }
    }
    private static SubscriptionPlanProofStore.SourcePhase sourcePhase(com.stripe.model.SubscriptionSchedule.Phase phase){
        // Missing collection inherits the already verified automatic default. Send the canonical
        // explicit source setting; target collection stays omitted under that verified default.
        return new SubscriptionPlanProofStore.SourcePhase(phase.getProrationBehavior()==null?"none":phase.getProrationBehavior(),phase.getDefaultPaymentMethod(),"charge_automatically");
    }
    private static boolean nonempty(List<?> list){return list!=null&&!list.isEmpty();}
    public static String changeFingerprint(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount a,SubscriptionOperation o,Quote q){return StripeRequestFingerprint.of(StripeBillingGrantTokens::hash,a,"SUBSCRIPTION_CHANGE_V1",o.id().toString(),o.providerOperationKey(),o.fingerprint(),q.id().toString(),q.toString(),"pending_if_incomplete","always_invoice");}
    static dev.genericpayments.platform.subscriptions.domain.Subscription.Period itemPeriod(com.stripe.model.Subscription s){if(s.getItems()==null||!Boolean.FALSE.equals(s.getItems().getHasMore())||s.getItems().getData()==null||s.getItems().getData().size()!=1)throw mismatch();var i=s.getItems().getData().getFirst();return new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(Instant.ofEpochSecond(i.getCurrentPeriodStart()),Instant.ofEpochSecond(i.getCurrentPeriodEnd()));}
    static void requireInvoice(Invoice invoice,Quote q,String id){if(!id.equals(invoice.getId())||!"invoice".equals(invoice.getObject())||!Boolean.FALSE.equals(invoice.getLivemode())||!q.customerReference().equals(invoice.getCustomer())||!"subscription_update".equals(invoice.getBillingReason())||!"charge_automatically".equals(invoice.getCollectionMethod())||!q.target().regularAmount().currency().equalsIgnoreCase(invoice.getCurrency())||invoice.getParent()==null||!"subscription_details".equals(invoice.getParent().getType())||invoice.getParent().getSubscriptionDetails()==null||!q.subscriptionReference().equals(invoice.getParent().getSubscriptionDetails().getSubscription())||!Objects.equals(invoice.getAmountDue(),q.dueNow())||!Objects.equals(invoice.getTotal(),q.dueNow())||!Objects.equals(invoice.getSubtotal(),q.dueNow())||!noAdjustments(invoice))throw mismatch();}
    static UUID quoteId(String ref){try{if(ref==null||!ref.startsWith("bq1:"))throw mismatch();return UUID.fromString(ref.substring(4));}catch(IllegalArgumentException bad){throw mismatch();}}
    private void sameQuote(Quote q,dev.genericpayments.platform.subscriptions.domain.Subscription s,Command c,QuoteRequest r){
        if(!q.account().equals(c.account().id())||!q.site().equals(s.siteId())||!q.customer().equals(s.customerId())||!q.subscription().equals(s.id())||!q.subscriptionReference().equals(s.providerReference())||!q.source().equals(s.billingPrice())||!q.entitled().equals(s.entitledPrice())||!q.target().equals(r.target())||q.timing()!=r.timing()||q.policy()!=c.operation().policyVersion()||!q.period().equals(s.currentPeriod())||!q.paidThrough().equals(s.paidThrough())||q.revision()!=c.operation().expectedRevision()||s.revision()!=q.revision()+(s.pendingChange()==null?0:1)||s.pendingChange()!=null&&!s.pendingChange().operationId().equals(c.operation().id()))throw mismatch();
    }
    static List<Line> prorationLines(List<InvoiceLineItem> input,String subscription,String item,String source,String target,String product,dev.genericpayments.platform.subscriptions.domain.Subscription.Period period,String currency,boolean actual){
        if(input==null||input.size()!=2)throw mismatch();var result=new ArrayList<Line>();
        for(var line:input){
            if(line==null||!"line_item".equals(line.getObject())||!Boolean.FALSE.equals(line.getLivemode())||!Objects.equals(1L,line.getQuantity())||!currency.equalsIgnoreCase(line.getCurrency())||line.getAmount()==null||line.getPeriod()==null||!period.start().equals(Instant.ofEpochSecond(line.getPeriod().getStart()))||!period.end().equals(Instant.ofEpochSecond(line.getPeriod().getEnd()))||line.getParent()==null||!"subscription_item_details".equals(line.getParent().getType())||line.getPricing()==null||!"price_details".equals(line.getPricing().getType())||!empty(line.getDiscountAmounts())||!empty(line.getPretaxCreditAmounts())||nonempty(line.getTaxes())||nonempty(line.getDiscounts()))throw mismatch();
            var detail=line.getParent().getSubscriptionItemDetails();var pricing=line.getPricing().getPriceDetails();
            if(detail==null||!subscription.equals(detail.getSubscription())||!item.equals(detail.getSubscriptionItem())||!Boolean.TRUE.equals(detail.getProration())||pricing==null||!product.equals(pricing.getProduct())||!(source.equals(pricing.getPrice())||target.equals(pricing.getPrice())))throw mismatch();
            var credited=detail.getProrationDetails()==null?null:detail.getProrationDetails().getCreditedItems();
            result.add(new Line(pricing.getPrice(),product,item,line.getAmount(),period,credited==null?null:credited.getInvoice(),credited==null?List.of():credited.getInvoiceLineItems()));
        }
        result.sort(Comparator.comparing(Line::price));
        var credits=result.stream().filter(l->l.price().equals(source)&&l.amount()<=0).toList();var debits=result.stream().filter(l->l.price().equals(target)&&l.amount()>=0).toList();
        if(credits.size()!=1||debits.size()!=1||!debits.getFirst().creditedLines().isEmpty()||debits.getFirst().creditedInvoice()!=null)throw mismatch();
        var credit=credits.getFirst();if((credit.creditedInvoice()==null)!=credit.creditedLines().isEmpty()||credit.creditedLines().size()>1)throw mismatch();
        return List.copyOf(result);
    }
    static boolean noAdjustments(Invoice i){return zeros(i.getAmountPaidOffStripe(),i.getAmountOverpaid(),i.getStartingBalance(),i.getEndingBalance(),i.getPrePaymentCreditNotesAmount(),i.getPostPaymentCreditNotesAmount())&&empty(i.getDiscounts())&&empty(i.getTotalDiscountAmounts())&&empty(i.getTotalPretaxCreditAmounts())&&empty(i.getTotalTaxes())
        &&(i.getAmountShipping()==null||i.getAmountShipping()==0)&&i.getShippingCost()==null&&i.getShippingDetails()==null
        &&!nonempty(i.getDefaultTaxRates())&&(i.getAutomaticTax()==null||!Boolean.TRUE.equals(i.getAutomaticTax().getEnabled()))
        &&(i.getTotalExcludingTax()==null||Objects.equals(i.getTotalExcludingTax(),i.getTotal()))&&(i.getSubtotalExcludingTax()==null||Objects.equals(i.getSubtotalExcludingTax(),i.getSubtotal()));}
    private static boolean zeros(Long... values){return Arrays.stream(values).allMatch(v->Objects.equals(v,0L));}
    private static boolean empty(List<?> values){return values!=null&&values.isEmpty();}
}
