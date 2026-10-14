package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import org.springframework.transaction.annotation.*;
import java.util.Objects;
public class PaymentCreationTransaction {
 private final LoadPaymentPort load;private final SavePaymentPort save;private final PaymentPhaseSupport support;
 public PaymentCreationTransaction(LoadPaymentPort load,SavePaymentPort save,PaymentPhaseSupport support){this.load=load;this.save=save;this.support=support;}
 public record Created(Payment payment,boolean created){}
 @Transactional(propagation=Propagation.MANDATORY)
 public Created createDonation(PreparePaymentCommand c){
  if(c.payment().source()!=PaymentSource.DONATION)throw new IllegalArgumentException("Donation payment required");
  return createLocal(c);
 }
 @Transactional
 public Created createLocal(PreparePaymentCommand c){
  support.ownership(c.payment().siteId(),c.account(),c.siteKey());
  var old=load.findByIdForUpdate(c.payment().siteId(),c.payment().id());
  if(old.isPresent()){
   var p=old.get();var expected=c.payment();
   if(!p.paymentAccountId().equals(expected.paymentAccountId())||p.source()!=expected.source()||!p.money().equals(expected.money())||p.taxMode()!=expected.taxMode()
    ||!Objects.equals(p.externalOrderId(),expected.externalOrderId())||!Objects.equals(p.priceSnapshot(),expected.priceSnapshot())||!Objects.equals(p.customerId(),expected.customerId())||!Objects.equals(p.donationId(),expected.donationId())||!Objects.equals(p.donationSnapshot(),expected.donationSnapshot())||!Objects.equals(p.subscriptionContext(),expected.subscriptionContext())||!Objects.equals(p.taxAmountMinor(),expected.taxAmountMinor())||!p.technicalMetadata().equals(expected.technicalMetadata()))throw new IllegalArgumentException("Payment creation conflict");
   return new Created(p,false);
  }
  var p=c.payment();var now=support.now();
  p=save.save(new Payment(p.id(),p.siteId(),p.customerId(),p.paymentAccountId(),p.source(),p.priceId(),p.priceSnapshot(),p.externalOrderId(),p.donationId(),p.donationSnapshot(),p.subscriptionContext(),p.money(),p.taxMode(),p.taxAmountMinor(),p.status(),null,0,0,now,now,p.technicalMetadata()));
  support.event(p,ProviderOperationKeys.prepare(p.id()),"created",PaymentEventType.PAYMENT_CREATED,c.requestId(),c.idempotencyKey(),null);
  return new Created(p,true);
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public PaymentPhaseSupport.Change recordPreparedOrUnknown(PreparePaymentCommand c,ProviderResult result){
  var locked=load.findForAttemptStart(c.payment().siteId(),c.payment().id()).orElseThrow();var p=locked.value();
  if(!p.paymentAccountId().equals(c.account().id()))throw new IllegalArgumentException("Payment ownership mismatch");
  var key=ProviderOperationKeys.prepare(p.id());String phase="prepared_"+result.kind();
  if(support.hasEvent(p,key,phase))return new PaymentPhaseSupport.Change(p,p.status(),false);
  // A delayed preparation acknowledgement must not overwrite a later confirmation/reconciliation.
  if(locked.hasLiveLease(support.now())||!p.canStartAttempt())throw new IllegalStateException("PAYMENT_STATE_CONFLICT");
  var next=support.apply(p,result,false);next=save.save(next);
  support.event(next,key,phase,support.type(result,false),c.requestId(),c.idempotencyKey(),result);
  return new PaymentPhaseSupport.Change(next,p.status(),p.status()!=next.status());
 }
}
