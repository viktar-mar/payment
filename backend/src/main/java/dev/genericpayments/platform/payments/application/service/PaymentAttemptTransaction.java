package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.port.out.LoadPaymentAttemptPort.StoredAttempt;
import dev.genericpayments.platform.payments.domain.*;
import org.springframework.transaction.annotation.*;
public class PaymentAttemptTransaction {
 private final LoadPaymentPort load;private final SavePaymentPort save;private final LoadPaymentAttemptPort attempts;private final SavePaymentAttemptPort saveAttempts;private final PaymentPhaseSupport support;
 public PaymentAttemptTransaction(LoadPaymentPort load,SavePaymentPort save,LoadPaymentAttemptPort attempts,SavePaymentAttemptPort saveAttempts,PaymentPhaseSupport support){this.load=load;this.save=save;this.attempts=attempts;this.saveAttempts=saveAttempts;this.support=support;}
 public record Started(Payment payment,StoredAttempt attempt){}
 @Transactional
 public Started startAttempt(ConfirmPaymentCommand c){
  support.ownership(c.siteId(),c.account(),c.siteKey());
  var locked=load.findForAttemptStart(c.siteId(),c.paymentId()).orElseThrow(()->new IllegalArgumentException("Payment not found"));var p=locked.value();
  if(!p.paymentAccountId().equals(c.account().id())||locked.hasLiveLease(support.now()))throw new IllegalStateException("PAYMENT_STATE_CONFLICT");
  var existing=attempts.findById(c.siteId(),c.attemptId());
  if(existing.isPresent()){
   var a=existing.get().value();
   if(!a.paymentId().equals(c.paymentId())||!a.paymentMethodFamily().equals(c.method().name())||!a.providerOperationKey().equals(ProviderOperationKeys.confirm(c.attemptId())))throw new IllegalArgumentException("Attempt identity conflict");
   return new Started(p,existing.get());
  }
  var previous=attempts.findByPayment(c.siteId(),c.paymentId());
  if(!p.canStartAttempt()||previous.stream().anyMatch(a->a.value().blocksSubsequentAttempt()||a.value().status()==PaymentAttemptStatus.CREATED))throw new IllegalStateException("PAYMENT_STATE_CONFLICT");
  int sequence=previous.stream().mapToInt(a->a.value().sequence()).max().orElse(0)+1;
  var a=PaymentAttempt.start(c.attemptId(),p.id(),sequence,ProviderOperationKeys.confirm(c.attemptId()),c.method().name(),support.now());
  var stored=saveAttempts.insert(a,support.now());
  support.event(p,a.providerOperationKey(),"created",PaymentEventType.ATTEMPT_CREATED,c.requestId(),c.idempotencyKey(),null);
  return new Started(p,stored);
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public PaymentPhaseSupport.Change recordProviderAcknowledgementOrUnknown(ConfirmPaymentCommand c,ProviderResult result){
  var locked=load.findForAttemptStart(c.siteId(),c.paymentId()).orElseThrow();var p=locked.value();
  if(!p.paymentAccountId().equals(c.account().id()))throw new IllegalArgumentException("Payment ownership mismatch");
  var stored=attempts.findByIdForUpdate(c.siteId(),c.attemptId()).orElseThrow();var a=stored.value();
  if(!a.paymentId().equals(p.id())||!a.paymentMethodFamily().equals(c.method().name()))throw new IllegalArgumentException("Attempt identity conflict");
  String phase="ack_"+result.kind();
  if(support.hasEvent(p,a.providerOperationKey(),phase))return new PaymentPhaseSupport.Change(p,p.status(),false);
  if(locked.hasLiveLease(support.now()))throw new IllegalStateException("PAYMENT_STATE_CONFLICT");
  var now=support.now();if(result.reference()!=null)a=a.withProviderIntentReference(result.reference(),now);
  a=switch(result.kind()){
   case ACCEPTED -> a.requiresPaymentMethod(now);case PROCESSING -> a.processing(now);case REQUIRES_ACTION -> a.requiresAction(now);
   case RECOVERABLE_DECLINE,TERMINAL_DECLINE,TEMPORARILY_UNAVAILABLE -> a.failed(result.code().name(),now);
   case UNKNOWN -> a.unknown(result.code().name(),now);
  };
  var next=support.apply(p,result,true);next=save.save(next);
  saveAttempts.save(new StoredAttempt(a,stored.version()));
  support.event(next,a.providerOperationKey(),phase,support.type(result,true),c.requestId(),c.idempotencyKey(),result);
  return new PaymentPhaseSupport.Change(next,p.status(),p.status()!=next.status());
 }
}
