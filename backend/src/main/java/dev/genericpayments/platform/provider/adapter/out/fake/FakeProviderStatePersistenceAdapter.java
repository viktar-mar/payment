package dev.genericpayments.platform.provider.adapter.out.fake;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.LoadRefundOwnershipPort;
import dev.genericpayments.platform.payments.application.service.CanonicalFingerprint;
import dev.genericpayments.platform.payments.application.service.ProviderInboxService;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Fake provider storage and private Admin/test controls. Never reads Payment as provider truth. */
@Repository
@Transactional
public class FakeProviderStatePersistenceAdapter implements dev.genericpayments.platform.payments.application.port.out.FakeEventControlPort {
    public enum Scenario {
        ACCEPTED, PROCESSING, REQUIRES_ACTION, RECOVERABLE_DECLINE, TERMINAL_DECLINE, TEMPORARILY_UNAVAILABLE, UNKNOWN;
    }
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final DigestPort digest;
    private final CanonicalFingerprint fingerprints;
    private final LoadRefundOwnershipPort refundOwnership;
    private final ProviderInboxService inbox;
    private final FakeSubscriptionCashLookupAdapter subscriptionCash;
    public FakeProviderStatePersistenceAdapter(JdbcTemplate jdbc,Clock clock,DigestPort digest,LoadRefundOwnershipPort refundOwnership,ProviderInboxService inbox,FakeSubscriptionCashLookupAdapter subscriptionCash){
        this.jdbc=jdbc;this.clock=clock;this.digest=digest;this.fingerprints=new CanonicalFingerprint(digest);this.refundOwnership=Objects.requireNonNull(refundOwnership);this.inbox=inbox;
        this.subscriptionCash=subscriptionCash;
    }
    private String paymentFingerprint(PaymentAccount account,Payment p){
        var facts=new TreeMap<String,Object>();
        facts.put("payment_id",p.id().value());facts.put("site_id",p.siteId().value());facts.put("account",account.id().value());
        facts.put("routing",List.of(account.key(),account.provider().name(),account.mode().name(),account.externalAccountId(),digest.sha256Hex(account.credentialsRef())));
        facts.put("amount",p.money().minorUnits());facts.put("currency",p.money().currency());facts.put("tax",p.taxMode().name());
        facts.put("source",p.source().name());facts.put("order",Objects.toString(p.externalOrderId(),""));facts.put("metadata",p.technicalMetadata());
        if(p.customerId()!=null)facts.put("customer_id",p.customerId());
        if(p.priceSnapshot()!=null)facts.put("price",List.of(p.priceSnapshot().priceId().value(),p.priceSnapshot().priceKey(),p.priceSnapshot().version()));
        if(p.taxAmountMinor()!=null)facts.put("tax_amount",p.taxAmountMinor());
        return fingerprints.hash(facts);
    }
    private void fake(PaymentAccount account){if(account.provider()!=PaymentProviderType.FAKE)throw new IllegalArgumentException("Payment account provider mismatch");}
    private Map<String,Object> object(PaymentAccount account,Payment p,String type,UUID id,String initialKey,String fingerprint){
        fake(account);String ref=(type.equals("PAYMENT")?"fake_pi_":"fake_re_")+id;
        var now=Timestamp.from(clock.instant());
        jdbc.update("""
            insert into fake_provider_objects(provider_reference,provider_operation_key,object_type,internal_object_id,payment_account_id,state,request_fingerprint,created_at,updated_at)
            values (?,?,?,?,?,'CREATED',?,?,?) on conflict(provider_reference) do nothing
            """,ref,initialKey,type,id,account.id().value(),fingerprint,now,now);
        var row=jdbc.queryForMap("select * from fake_provider_objects where provider_reference=? FOR UPDATE",ref);
        if(!row.get("payment_account_id").equals(account.id().value())||!row.get("object_type").equals(type)||!row.get("internal_object_id").equals(id)||!Objects.equals(row.get("request_fingerprint"),fingerprint))throw conflict();
        return row;
    }
    private IllegalStateException conflict(){return new IllegalStateException("PROVIDER_OPERATION_CONFLICT");}
    public ProviderResult prepare(ProviderPaymentRequest req){
        return execute(req.account(),req.payment(),"PAYMENT",req.payment().id().value(),req.operationKey(),paymentFingerprint(req.account(),req.payment()),"PREPARE",false);
    }
    public ProviderResult confirm(ProviderConfirmationRequest req,boolean autoSuccess){
        if(req.attempt().providerIntentReference()!=null&&!req.attempt().providerIntentReference().equals("fake_pi_"+req.payment().id().value()))throw conflict();
        String objectFingerprint=paymentFingerprint(req.account(),req.payment());
        String requestFingerprint=fingerprints.hash(Map.of("object",objectFingerprint,"attempt",req.attempt().id().value(),"sequence",req.attempt().sequence(),"method",req.method().name()));
        return execute(req.account(),req.payment(),"PAYMENT",req.payment().id().value(),req.operationKey(),objectFingerprint,requestFingerprint,autoSuccess);
    }
    public ProviderResult refund(ProviderRefundRequest req){
        var ownership=requireRefundOwnership(req.refund().id(),req.payment().id(),req.account().id());
        if(!ownership.siteId().equals(req.payment().siteId()))throw conflict();
        if(req.payment().source()==PaymentSource.SUBSCRIPTION)subscriptionCash.requireRefundable(req);
        if(req.refund().providerRefundReference()!=null&&!req.refund().providerRefundReference().equals("fake_re_"+req.refund().id().value()))throw conflict();
        String fingerprint=fingerprints.hash(Map.of("payment",paymentFingerprint(req.account(),req.payment()),"refund",req.refund().id().value(),"amount",req.refund().money().minorUnits(),"currency",req.refund().money().currency(),"reason",Objects.toString(req.refund().reasonCode(),""),"note",Objects.toString(req.refund().technicalNote(),"")));
        return execute(req.account(),req.payment(),"REFUND",req.refund().id().value(),req.operationKey(),fingerprint,"REFUND",false);
    }
    private ProviderResult execute(PaymentAccount account,Payment p,String type,UUID id,String key,String objectFingerprint,String operationFacts,boolean autoSuccess){
        if(p.source()==PaymentSource.SUBSCRIPTION) {
            if(!type.equals("REFUND"))throw conflict();
        } else if(p.providerPaymentReference()!=null&&!p.providerPaymentReference().equals("fake_pi_"+p.id().value()))throw conflict();
        // A database lock serializes same keys even when an invalid caller supplies another object.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",key);
        String fingerprint=fingerprints.hash(Map.of("object",objectFingerprint,"operation",operationFacts));
        var receipts=jdbc.queryForList("select * from fake_provider_operations where operation_key=?",key);
        if(!receipts.isEmpty()){
            var old=receipts.getFirst();
            if(!old.get("fingerprint").equals(fingerprint)||!old.get("payment_account_id").equals(account.id().value())||!old.get("internal_object_id").equals(id)||!old.get("object_type").equals(type))throw conflict();
            return result(ProviderResult.Kind.valueOf((String)old.get("result_kind")),(String)old.get("result_reference"),(String)old.get("action_payload"));
        }
        var row=object(account,p,type,id,type.equals("PAYMENT")?ProviderOperationKeys.prepare(p.id()):key,objectFingerprint);
        String ref=(String)row.get("provider_reference");
        boolean terminal=Set.of("SUCCEEDED","FAILED","CANCELLED").contains(row.get("state"));
        var kind=terminal?ProviderResult.Kind.UNKNOWN:row.get("scenario")==null?(operationFacts.equals("PREPARE")?ProviderResult.Kind.ACCEPTED:ProviderResult.Kind.PROCESSING):ProviderResult.Kind.valueOf((String)row.get("scenario"));
        ProviderResult result=result(kind,ref,"verify_payment");
        String next=switch(kind){case ACCEPTED -> "REQUIRES_PAYMENT_METHOD";case PROCESSING -> "PROCESSING";case REQUIRES_ACTION -> "REQUIRES_ACTION";case RECOVERABLE_DECLINE -> "REQUIRES_PAYMENT_METHOD";case TERMINAL_DECLINE -> "FAILED";case UNKNOWN -> "UNKNOWN";case TEMPORARILY_UNAVAILABLE -> (String)row.get("state");};
        // Receipts are replayed before this branch, so later truth is never overwritten on replay.
        if(!terminal)
            jdbc.update("update fake_provider_objects set state=?,result_code=?,updated_at=?,version=version+1 where provider_reference=?",next,result.code().name(),Timestamp.from(clock.instant()),ref);
        var action=result instanceof ProviderResult.RequiresAction a?a.action():null;
        jdbc.update("""
            insert into fake_provider_operations(operation_key,fingerprint,provider_reference,payment_account_id,object_type,internal_object_id,result_kind,result_reference,result_code,action_kind,action_payload,created_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?)
            """,key,fingerprint,ref,account.id().value(),type,id,kind.name(),result.reference(),result.code().name(),action==null?null:action.kind().name(),action==null?null:action.payload(),Timestamp.from(clock.instant()));
        if(autoSuccess && (kind==ProviderResult.Kind.PROCESSING||kind==ProviderResult.Kind.ACCEPTED)){
            long sequence=((Number)row.get("event_counter")).longValue()+1;
            enqueue(account,p.id().value(),ref,sequence,ProviderState.Status.SUCCEEDED,clock.instant());
            jdbc.update("update fake_provider_objects set state='SUCCEEDED',event_counter=?,version=version+1,updated_at=? where provider_reference=?",sequence,Timestamp.from(clock.instant()),ref);
        }
        return result;
    }
    private ProviderResult result(ProviderResult.Kind kind,String ref,String payload){return switch(kind){
        case ACCEPTED -> new ProviderResult.Accepted(ref,false);case PROCESSING -> new ProviderResult.Accepted(ref,true);
        case REQUIRES_ACTION -> new ProviderResult.RequiresAction(ref,new ProviderResult.Action(ProviderResult.ActionKind.COMPLETE_VERIFICATION,payload));
        case RECOVERABLE_DECLINE -> new ProviderResult.RecoverableDecline(ref);case TERMINAL_DECLINE -> new ProviderResult.TerminalDecline(ref);
        case TEMPORARILY_UNAVAILABLE -> new ProviderResult.TemporarilyUnavailable();case UNKNOWN -> new ProviderResult.Unknown(ref);
    };}
    public void configure(ProviderPaymentRequest req,Scenario scenario){
        Objects.requireNonNull(scenario);var row=object(req.account(),req.payment(),"PAYMENT",req.payment().id().value(),req.operationKey(),paymentFingerprint(req.account(),req.payment()));
        jdbc.update("update fake_provider_objects set scenario=?,version=version+1,updated_at=? where provider_reference=?",scenario.name(),Timestamp.from(clock.instant()),row.get("provider_reference"));
    }
    public void setPaymentState(ProviderPaymentRequest req,ProviderState.Status status){
        Objects.requireNonNull(status);var row=object(req.account(),req.payment(),"PAYMENT",req.payment().id().value(),req.operationKey(),paymentFingerprint(req.account(),req.payment()));
        jdbc.update("update fake_provider_objects set state=?,version=version+1,updated_at=? where provider_reference=?",status.name(),Timestamp.from(clock.instant()),row.get("provider_reference"));
    }
    public ProviderState lookup(PaymentAccount account,String type,UUID id,String ref){
        fake(account);var rows=jdbc.queryForList("select state from fake_provider_objects where provider_reference=? and payment_account_id=? and object_type=? and internal_object_id=?",ref,account.id().value(),type,id);
        if(rows.isEmpty())throw new IllegalArgumentException("Provider object not found");
        return new ProviderState(ref,ProviderState.Status.valueOf((String)rows.getFirst().get("state")));
    }
    public ProviderState lookupPayment(ProviderPaymentLookup lookup){
        return subscriptionCash.lookup(lookup).orElseGet(()->lookup(lookup.account(),"PAYMENT",lookup.paymentId().value(),lookup.reference()));
    }
    public ProviderState lookupRefund(ProviderRefundLookup lookup){
        // This is an ownership check only; current provider truth still comes exclusively from the fake object.
        requireRefundOwnership(lookup.refundId(),lookup.paymentId(),lookup.account().id());
        return lookup(lookup.account(),"REFUND",lookup.refundId().value(),lookup.reference());
    }
    private LoadRefundOwnershipPort.RefundOwnership requireRefundOwnership(RefundId refundId,PaymentId paymentId,PaymentAccountId accountId){
        var ownership=refundOwnership.findOwnership(refundId).orElseThrow(() -> new IllegalArgumentException("Provider object not found"));
        if(!ownership.paymentId().equals(paymentId)||!ownership.paymentAccountId().equals(accountId))throw new IllegalArgumentException("Provider object not found");
        return ownership;
    }
    public void enqueuePaymentEvent(ProviderPaymentRequest req,long sequence,ProviderState.Status status,Instant occurredAt){
        if(sequence<=0)throw new IllegalArgumentException("Invalid event sequence");Objects.requireNonNull(occurredAt);
        var row=object(req.account(),req.payment(),"PAYMENT",req.payment().id().value(),req.operationKey(),paymentFingerprint(req.account(),req.payment()));
        enqueue(req.account(),req.payment().id().value(),(String)row.get("provider_reference"),sequence,status,occurredAt);
        jdbc.update("update fake_provider_objects set event_counter=greatest(event_counter,?),updated_at=?,version=version+1 where provider_reference=?",sequence,Timestamp.from(clock.instant()),row.get("provider_reference"));
    }
    private void enqueue(PaymentAccount account,UUID id,String ref,long sequence,ProviderState.Status status,Instant occurredAt){
        String eventType=switch(status){case SUCCEEDED -> "PAYMENT_SUCCEEDED";case FAILED -> "PAYMENT_FAILED";case CANCELLED -> "PAYMENT_CANCELLED";case PROCESSING -> "PAYMENT_PROCESSING";case REQUIRES_ACTION -> "CUSTOMER_ACTION_REQUIRED";case REQUIRES_PAYMENT_METHOD -> "PAYMENT_METHOD_REQUIRED";default -> throw new IllegalArgumentException("Unsupported event state");};
        String eventId="fake_evt_"+id+"_"+sequence;
        inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),eventId,ref,PaymentEventType.valueOf(eventType),occurredAt,clock.instant(),Map.of("payment_id",id.toString(),"state",status.name(),"schema_version",1)));
    }
    @Override public String enqueue(PaymentAccount account,Payment p,Refund refund,dev.genericpayments.platform.payments.application.port.in.SimulateFakeEventUseCase.Target target,PaymentEventType type,long sequence,Instant occurredAt){
        fake(account);boolean isRefund=target==dev.genericpayments.platform.payments.application.port.in.SimulateFakeEventUseCase.Target.REFUND;
        UUID id=isRefund?refund.id().value():p.id().value();String ref=isRefund?refund.providerRefundReference():p.providerPaymentReference();
        if(isRefund)requireRefundOwnership(refund.id(),p.id(),account.id());
        var rows=jdbc.queryForList("select state,event_counter from fake_provider_objects where provider_reference=? and payment_account_id=? and object_type=? and internal_object_id=? for update",ref,account.id().value(),isRefund?"REFUND":"PAYMENT",id);
        if(rows.size()!=1)throw conflict();var row=rows.getFirst();
        String state=switch(type){case PAYMENT_METHOD_REQUIRED -> "REQUIRES_PAYMENT_METHOD";case CUSTOMER_ACTION_REQUIRED -> "REQUIRES_ACTION";case PAYMENT_PROCESSING,REFUND_PROCESSING -> "PROCESSING";case PAYMENT_SUCCEEDED,REFUND_SUCCEEDED -> "SUCCEEDED";case PAYMENT_FAILED,REFUND_FAILED -> "FAILED";case PAYMENT_CANCELLED -> "CANCELLED";default -> throw conflict();};
        String eventId="fake_evt_"+id+"_"+sequence;var payload=new TreeMap<String,Object>();payload.put("payment_id",p.id().value().toString());if(isRefund)payload.put("refund_id",id.toString());payload.put("schema_version",1);payload.put("state",state);
        inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),eventId,ref,type,occurredAt,clock.instant(),payload));
        // A delayed sequence may be enqueued but cannot regress later provider truth.
        if(sequence>((Number)row.get("event_counter")).longValue()&&!Set.of("SUCCEEDED","FAILED","CANCELLED").contains(row.get("state")))jdbc.update("update fake_provider_objects set state=?,event_counter=?,updated_at=?,version=version+1 where provider_reference=?",state,sequence,Timestamp.from(clock.instant()),ref);
        else jdbc.update("update fake_provider_objects set event_counter=greatest(event_counter,?),updated_at=?,version=version+1 where provider_reference=?",sequence,Timestamp.from(clock.instant()),ref);
        return eventId;
    }
}
