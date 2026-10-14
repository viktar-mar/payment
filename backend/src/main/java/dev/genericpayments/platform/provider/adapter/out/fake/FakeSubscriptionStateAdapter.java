package dev.genericpayments.platform.provider.adapter.out.fake;

import dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Independent provider database. Every public operation is atomic, even on a newly constructed adapter. */
public final class FakeSubscriptionStateAdapter implements FakeSubscriptionControlPort {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final boolean localPortal;

    public FakeSubscriptionStateAdapter(JdbcTemplate jdbc,Clock clock,ObjectMapper json,PlatformTransactionManager manager) {
        this(jdbc,clock,json,manager,false);
    }
    public FakeSubscriptionStateAdapter(JdbcTemplate jdbc,Clock clock,ObjectMapper json,PlatformTransactionManager manager,boolean localPortal) {
        this.jdbc=jdbc;this.clock=clock;this.json=json;this.transaction=new TransactionTemplate(manager);
        this.localPortal=localPortal;
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Adapter-private persisted state, including the original UTC anchor rather than the last truncated date. */
    public static final class State {
        public UUID id;
        public String reference;
        public Subscription.Status status;
        public SubscriptionPriceTerms price;
        /** Nullable only for pre-extension persisted objects, whose original contract always started trials. */
        public Boolean startTrial;
        public Subscription.Period period;
        public Subscription.Period trial;
        public Instant cancellation;
        /** Last applied platform command; deferred requests live only in pending/immutable receipts. */
        public String operationKey;
        public boolean methodReady;
        public long revision;
        public Instant anchor;
        public int anchorDay;
        public dev.genericpayments.platform.catalog.domain.BillingInterval anchorInterval;
        public int periodIndex;
        public SubscriptionPriceTerms scheduledTarget;
        public NextAction action;
        public String actionInvoice;
        public SubscriptionPriceTerms actionTarget;
        public String actionOperationKey;
        /** Autonomous invoice origin stays distinct from its authorized platform recovery association. */
        public boolean actionAutonomous;
        public String actionRecoveryKey;
        public Map<String,OperationState> operations=new LinkedHashMap<>();
        public Pending pending;
    }
    public record QuoteState(SubscriptionPriceTerms current,SubscriptionPriceTerms target,ChangeTiming timing,
                             long revision,ProviderQuote quote) {}
    /** Original authorized request facts only; no platform mutable state or credentials. */
    public record Pending(String method,String key,String invoiceReference,boolean consent,boolean immediate,QuoteState quote,Instant cancellationBoundary) {
        public Pending(String method,String key,String invoiceReference,boolean consent,boolean immediate,QuoteState quote){this(method,key,invoiceReference,consent,immediate,quote,null);}
    }

    static void requireFake(PaymentAccount account) {
        if(account.provider()!=PaymentProviderType.FAKE)throw new IllegalArgumentException("Payment account provider mismatch");
    }
    private <T> T atomic(PaymentAccountId account,Supplier<T> work) {
        return transaction.execute(ignored->{
            jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","fake-subscription:"+account.value());
            jdbc.update("insert into fake_subscription_account_clocks values (?,?) on conflict(payment_account_id) do nothing",account.value(),time(clock.instant()));
            jdbc.update("update fake_subscription_account_clocks set simulated_at=greatest(simulated_at,?) where payment_account_id=?",time(clock.instant()),account.value());
            return work.get();
        });
    }
    private Instant now(PaymentAccountId account) {
        return jdbc.queryForObject("select simulated_at from fake_subscription_account_clocks where payment_account_id=?",Timestamp.class,account.value()).toInstant();
    }
    private static Timestamp time(Instant value){return Timestamp.from(value);}
    private static String reference(String prefix,String seed){return prefix+UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));}
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private String encode(Object value){return json.writeValueAsString(value);}
    private <T> T decode(Object value,Class<T> type){return json.readValue(value.toString(),type);}
    private static IllegalStateException conflict(){return new IllegalStateException("PROVIDER_OPERATION_CONFLICT");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid provider request");}
    private State load(PaymentAccountId account,UUID id) {
        var rows=jdbc.queryForList("select state from fake_subscription_objects where payment_account_id=? and subscription_id=?",account.value(),id);
        if(rows.size()!=1)throw new IllegalArgumentException("Provider subscription not found");
        return decode(rows.getFirst().get("state"),State.class);
    }
    private State lookup(Lookup lookup) {
        var state=load(lookup.account().id(),lookup.subscriptionId());
        if(lookup.reference()!=null&&!lookup.reference().equals(state.reference))throw invalid();
        if(lookup.providerOperationKey()!=null) {
            var count=jdbc.queryForObject("select count(*) from fake_subscription_operations where payment_account_id=? and subscription_id=? and operation_key=?",Integer.class,lookup.account().id().value(),state.id,lookup.providerOperationKey());
            if(count!=1)throw invalid();
        }
        return state;
    }
    private List<Invoice> invoices(PaymentAccountId account,UUID id) {
        return jdbc.query("select invoice from fake_subscription_invoices where payment_account_id=? and subscription_id=? order by ordinal",(rs,n)->decode(rs.getString(1),Invoice.class),account.value(),id);
    }
    private Snapshot snapshot(PaymentAccountId account,State s,Instant observed,String requestedKey) {
        var disposition=requestedKey==null?null:s.operations.get(requestedKey);
        return new Snapshot(s.reference,s.status,s.price,s.period,s.trial,s.cancellation,invoices(account,s.id),s.operationKey,s.methodReady,s.revision,observed,
            disposition==null?null:new OperationObservation(requestedKey,disposition),invoices(account,s.id).stream()
                .map(i->new RecoveryObservation(i.reference(),recoveryDisposition(s,i))).toList());
    }
    private RecoveryDisposition recoveryDisposition(State s,Invoice invoice) {
        if(s.status==Subscription.Status.CANCELLED||s.pending!=null||invoice.settlement()!=SettlementKind.OPEN)return RecoveryDisposition.BLOCKED;
        if(s.action!=null)return s.actionAutonomous&&s.action.kind()==ActionKind.FAKE_CONFIRM_PAYMENT&&s.actionRecoveryKey==null
            &&invoice.reference().equals(s.actionInvoice)?RecoveryDisposition.ASSOCIATE_EXISTING_ACTION:RecoveryDisposition.BLOCKED;
        return invoice.cashObjects().stream().anyMatch(c->c.status()==PaymentStatus.PROCESSING||c.status()==PaymentStatus.REQUIRES_ACTION)
            ?RecoveryDisposition.BLOCKED:RecoveryDisposition.RETRY_ALLOWED;
    }
    public Snapshot retrieve(Lookup lookup) {
        requireFake(lookup.account());return atomic(lookup.account().id(),()->snapshot(lookup.account().id(),lookup(lookup),now(lookup.account().id()),lookup.providerOperationKey()));
    }
    public NextAction nextAction(Lookup lookup) {
        requireFake(lookup.account());return atomic(lookup.account().id(),()->{
            var s=lookup(lookup);
            if(lookup.providerOperationKey()!=null&&!ownsAction(s,lookup.providerOperationKey()))return null;
            var at=now(lookup.account().id());
            if(lookup.providerOperationKey()!=null&&s.action!=null&&!at.isBefore(s.action.expiresAt())){
                refreshExpiredAction(s,lookup.providerOperationKey(),at);
                save(lookup.account().id(),s,at,"SUBSCRIPTION_ACTION_REFRESHED");
            }
            var action=s.action;
            return action!=null&&now(lookup.account().id()).isBefore(action.expiresAt())?action:null;
        });
    }
    private void save(PaymentAccountId account,State s,Instant at,String event) {
        s.revision=Math.incrementExact(s.revision);
        jdbc.update("update fake_subscription_objects set state=?::jsonb where payment_account_id=? and subscription_id=?",encode(s),account.value(),s.id);
        var payload=Map.of("subscription_id",s.id.toString(),"subscription_reference",s.reference,"provider_revision",s.revision,"schema_version",1);
        jdbc.update("insert into fake_subscription_event_queue(id,payment_account_id,subscription_id,provider_reference,provider_revision,event_type,occurred_at,payload) values (?,?,?,?,?,?,?,?::jsonb)",
            UUID.nameUUIDFromBytes((account.value()+":"+s.id+":"+s.revision).getBytes(StandardCharsets.UTF_8)),account.value(),s.id,s.reference,s.revision,event,time(at),encode(payload));
    }
    private Scenario scenario(PaymentAccountId account,UUID id) {
        var rows=jdbc.queryForList("select scenario from fake_subscription_scenarios where payment_account_id=? and subscription_id=?",String.class,account.value(),id);
        return rows.isEmpty()?Scenario.SUCCESS:Scenario.valueOf(rows.getFirst());
    }
    @Override public void scenario(PaymentAccountId account,UUID id,Scenario scenario) {
        Objects.requireNonNull(id);Objects.requireNonNull(scenario);
        atomic(account,()->{jdbc.update("insert into fake_subscription_scenarios values (?,?,?) on conflict(payment_account_id,subscription_id) do update set scenario=excluded.scenario",account.value(),id,scenario.name());return null;});
    }
    private String fingerprint(String method,Command c,boolean immediate,String token) {
        // Hash stable request fields only. Mutable platform operation status/timestamps are not provider identity.
        var fields=new LinkedHashMap<String,Object>();
        fields.put("method",method);fields.put("accountId",c.account().id());fields.put("provider",c.account().provider());
        fields.put("mode",c.account().mode());fields.put("externalAccountId",c.account().externalAccountId());
        fields.put("operationId",c.operation().id());
        fields.put("subscriptionId",c.operation().subscriptionId());fields.put("siteId",c.operation().siteId());fields.put("customerId",c.operation().customerId());
        fields.put("operationFingerprint",c.operation().fingerprint());fields.put("expectedRevision",c.operation().expectedRevision());
        fields.put("current",c.current());fields.put("target",c.target());fields.put("subscriptionReference",c.providerSubscriptionReference());
        fields.put("invoiceReference",c.providerInvoiceReference());fields.put("quoteReference",c.providerQuoteReference());fields.put("consent",c.recurringConsent());
        fields.put("startTrial",c.startTrial());fields.put("immediate",immediate);fields.put("actionDigest",token==null?null:hash(token));
        return hash(encode(fields));
    }
    /** Continuations replay immutable original authority, not today's economic reconstruction. */
    private String continuationFingerprint(String method,Command c,String token) {
        var fields=new LinkedHashMap<String,Object>();var o=c.operation();
        fields.put("method",method);fields.put("accountId",c.account().id());fields.put("provider",c.account().provider());
        fields.put("mode",c.account().mode());fields.put("externalAccountId",c.account().externalAccountId());
        fields.put("operationId",o.id());fields.put("kind",o.kind());fields.put("siteId",o.siteId());fields.put("customerId",o.customerId());
        fields.put("subscriptionId",o.subscriptionId());fields.put("providerOperationKey",o.providerOperationKey());
        fields.put("operationFingerprint",o.fingerprint());fields.put("expectedRevision",o.expectedRevision());fields.put("policyVersion",o.policyVersion());
        fields.put("actionDigest",token==null?null:hash(token));
        if(o.kind()==SubscriptionOperation.Kind.CREATE)fields.put("startTrial",c.startTrial());
        return hash(encode(fields));
    }
    public Reply execute(String method,Command c,boolean immediate,String token) {
        requireFake(c.account());Objects.requireNonNull(c.operation().subscriptionId(),"Provider command requires attached subscription UUID");
        if(!method.equals("CONFIRM_ACTION")&&!method.equals(c.operation().kind().name())&&!(method.equals("SETUP")&&c.operation().kind()==SubscriptionOperation.Kind.CREATE))throw invalid();
        return atomic(c.account().id(),()->executeLocked(method,c,immediate,token));
    }
    private Reply executeLocked(String method,Command c,boolean immediate,String token) {
        var account=c.account().id();var id=c.operation().subscriptionId();var at=now(account);
        boolean createSetup=method.equals("SETUP")&&c.operation().kind()==SubscriptionOperation.Kind.CREATE;
        String key=method.equals("CONFIRM_ACTION")?"confirm:"+hash(c.operation().providerOperationKey()):createSetup?"setup:"+hash(c.operation().providerOperationKey()):c.operation().providerOperationKey();
        String fingerprint=method.equals("CONFIRM_ACTION")||createSetup?continuationFingerprint(method,c,token):fingerprint(method,c,immediate,token);
        var receipts=jdbc.queryForList("select fingerprint,reply from fake_subscription_operations where payment_account_id=? and operation_key=?",account.value(),key);
        if(!receipts.isEmpty()) {
            if(!fingerprint.equals(receipts.getFirst().get("fingerprint")))throw conflict();
            return decode(receipts.getFirst().get("reply"),Reply.class);
        }
        State s;
        if(method.equals("CREATE")) {
            if(!c.recurringConsent()||c.providerSubscriptionReference()!=null||c.startTrial()&&c.current().trialDays()==0)throw invalid();
            if(jdbc.queryForObject("select count(*) from fake_subscription_objects where payment_account_id=? and subscription_id=?",Integer.class,account.value(),id)!=0)throw conflict();
            s=new State();s.id=id;s.reference=reference("fake_sub_",account.value()+":"+id);s.price=c.current();s.status=Subscription.Status.INCOMPLETE;
            s.startTrial=c.startTrial();
            resetAnchor(s,at);s.period=period(s,0);
            jdbc.update("insert into fake_subscription_objects values (?,?,?,?::jsonb)",account.value(),id,s.reference,encode(s));
        } else {
            s=load(account,id);
            if(c.providerSubscriptionReference()!=null&&!c.providerSubscriptionReference().equals(s.reference))throw invalid();
            if(s.status==Subscription.Status.CANCELLED&&!method.equals("CANCEL"))throw invalid();
            if(s.pending!=null)throw conflict();
        }
        var scenario=scenario(account,id);
        if(method.equals("SETUP")&&!c.recurringConsent())throw invalid();
        if(createSetup&&(s.status!=Subscription.Status.INCOMPLETE||s.trial!=null||s.action!=null||c.startTrial()!=startsTrial(s)
            ||s.operations.get(c.operation().providerOperationKey())!=OperationState.DECLINED))throw invalid();
        // Reject unsafe lifecycle requests before even accepting an unknown/pending receipt.
        if(method.equals("CANCEL")&&s.scheduledTarget!=null)throw conflict();
        if(method.equals("WITHDRAW_CANCEL")&&(s.cancellation==null||!at.isBefore(s.cancellation)))throw invalid();
        Instant cancellationBoundary=method.equals("CANCEL")&&!immediate?confirmedBoundary(account,s,at):null;
        var pending=new Pending(method,c.operation().providerOperationKey(),c.providerInvoiceReference(),c.recurringConsent(),immediate,
            method.equals("CHANGE")?quoteFor(account,s,c,at):null,cancellationBoundary);
        boolean applied=true;
        boolean associateAction=method.equals("RECOVER")&&s.action!=null;
        Reply reply;
        if(method.equals("CONFIRM_ACTION")) {
            if(c.operation().kind()==SubscriptionOperation.Kind.CREATE&&c.startTrial()!=startsTrial(s))throw invalid();
            if(token==null||s.action==null||!ownsAction(s,c.operation().providerOperationKey())||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),s.action.secret().getBytes(StandardCharsets.UTF_8)))throw invalid();
            complete(account,s,at);reply=new Reply(Outcome.ACCEPTED,s.reference,null);
        } else if(associateAction) {
            recover(account,s,pending,at,scenario);reply=reply(s,scenario);
        } else if(method.equals("CREATE")) {
            createFacts(account,s,pending,scenario,at);
            reply=reply(s,scenario);
        } else if(scenario==Scenario.UNKNOWN||(scenario==Scenario.DECLINE||scenario==Scenario.PROCESSING)&&!financialAttempt(s,pending)) {
            applied=false;
            reply=new Reply(scenario==Scenario.UNKNOWN?Outcome.UNKNOWN:Outcome.DECLINED,s.reference,null);
            if(scenario==Scenario.PROCESSING)reply=new Reply(Outcome.ACCEPTED,s.reference,null);
        } else {
            apply(account,s,pending,scenario,at);
            reply=reply(s,scenario);
        }
        if(!method.equals("CONFIRM_ACTION")&&!associateAction&&(scenario==Scenario.UNKNOWN||scenario==Scenario.PROCESSING))s.pending=pending;
        if(!method.equals("CONFIRM_ACTION"))observe(s,pending,scenario);
        if(applied&&s.operations.get(c.operation().providerOperationKey())==OperationState.APPLIED)s.operationKey=c.operation().providerOperationKey();
        save(account,s,at,"SUBSCRIPTION_UPDATED");
        jdbc.update("insert into fake_subscription_operations values (?,?,?,?,?,?::jsonb,?)",account.value(),id,key,fingerprint,s.reference,encode(reply),time(at));
        return reply;
    }
    private void observe(State s,Pending request,Scenario scenario) {
        if(s.action!=null&&s.actionOperationKey==null) {
            s.actionOperationKey=request.key();s.actionAutonomous=request.method().equals("RENEWAL");
        }
        if(request.method().equals("RENEWAL"))return;
        var disposition=scenario==Scenario.UNKNOWN||scenario==Scenario.PROCESSING||scenario==Scenario.UNSUPPORTED_SETTLEMENT
            ||ownsAction(s,request.key())?OperationState.PENDING
            :scenario==Scenario.DECLINE?OperationState.DECLINED:OperationState.APPLIED;
        s.operations.put(request.key(),disposition);
    }
    private boolean startsTrial(State s) {return s.startTrial==null?s.price.trialDays()>0:s.startTrial;}
    private boolean financialAttempt(State s,Pending request) {
        return switch(request.method()) {
            case "CREATE" -> !startsTrial(s);
            case "CHANGE" -> request.quote().timing()==ChangeTiming.IMMEDIATE;
            case "RECOVER","RESUME" -> true;
            default -> false;
        };
    }
    private void createFacts(PaymentAccountId account,State s,Pending request,Scenario scenario,Instant at) {
        if(startsTrial(s)) {
            if(Set.of(Scenario.UNKNOWN,Scenario.PROCESSING,Scenario.DECLINE).contains(scenario))return;
            if(s.price.trialPolicy()==TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL||scenario==Scenario.REQUIRES_ACTION)action(s,ActionKind.FAKE_CONFIRM_SETUP,at,null,null);
            else startTrial(s,at);
        } else charge(account,s,BillingRecord.Kind.INITIAL,s.price,null,s.period,s.price.regularAmount().minorUnits(),request.key(),scenario,at);
    }
    private void apply(PaymentAccountId account,State s,Pending request,Scenario scenario,Instant at) {
        switch(request.method()) {
            case "CREATE" -> createFacts(account,s,request,scenario,at);
            case "CHANGE" -> applyQuote(account,s,request,at,scenario);
            case "CLEAR_CHANGE" -> s.scheduledTarget=null;
            case "CANCEL" -> {
                if(s.scheduledTarget!=null)throw conflict();
                if(request.immediate()){s.status=Subscription.Status.CANCELLED;s.cancellation=null;s.action=null;}
                else {if(request.cancellationBoundary()==null)throw invalid();s.cancellation=request.cancellationBoundary();}
            }
            case "WITHDRAW_CANCEL" -> {if(s.cancellation!=null&&!at.isBefore(s.cancellation))throw invalid();s.cancellation=null;}
            case "SETUP" -> {if(s.action!=null)throw conflict();action(s,ActionKind.FAKE_CONFIRM_SETUP,at,null,null);}
            case "RECOVER" -> recover(account,s,request,at,scenario);
            case "RESUME" -> {
                if(s.status!=Subscription.Status.PAUSED||!s.methodReady||!request.consent())throw invalid();
                resetAnchor(s,at);s.period=period(s,0);
                charge(account,s,BillingRecord.Kind.INITIAL,s.price,null,s.period,s.price.regularAmount().minorUnits(),request.key(),scenario,at);
            }
            default -> throw invalid();
        }
    }
    private Reply reply(State s,Scenario scenario) {
        if(s.action!=null)return new Reply(Outcome.REQUIRES_ACTION,s.reference,s.action);
        return new Reply(scenario==Scenario.UNKNOWN?Outcome.UNKNOWN:scenario==Scenario.DECLINE?Outcome.DECLINED:Outcome.ACCEPTED,s.reference,null);
    }
    private void resetAnchor(State s,Instant anchor) {s.anchor=anchor;s.anchorDay=anchor.atZone(ZoneOffset.UTC).getDayOfMonth();s.anchorInterval=s.price.interval();s.periodIndex=0;}
    private Subscription.Period period(State s,int index) {
        return new Subscription.Period(index==0?s.anchor:FakeBillingCalendar.periodEnd(s.anchor,s.anchorDay,index,s.anchorInterval),FakeBillingCalendar.periodEnd(s.anchor,s.anchorDay,index+1,s.anchorInterval));
    }
    private void startTrial(State s,Instant at) {
        s.trial=new Subscription.Period(at,at.plus(Duration.ofDays(s.price.trialDays())));s.period=s.trial;s.status=Subscription.Status.TRIALING;
        resetAnchor(s,s.trial.end());
    }
    private void action(State s,ActionKind kind,Instant at,String invoice,SubscriptionPriceTerms target) {
        s.action=new NextAction(kind,UUID.randomUUID().toString()+UUID.randomUUID(),at.plus(Duration.ofMinutes(10)));
        s.actionInvoice=invoice;s.actionTarget=target;
        s.actionOperationKey=null;s.actionRecoveryKey=null;s.actionAutonomous=false;
    }
    private boolean ownsAction(State s,String key) {
        return key.equals(s.actionOperationKey)||key.equals(s.actionRecoveryKey);
    }
    private void writeInvoice(PaymentAccountId account,State s,Invoice invoice) {
        long ordinal=invoices(account,s.id).size()+1L;
        jdbc.update("insert into fake_subscription_invoices values (?,?,?,?,?::jsonb) on conflict(payment_account_id,subscription_id,provider_reference) do update set invoice=excluded.invoice",
            account.value(),s.id,invoice.reference(),ordinal,encode(invoice));
        for(var cash:invoice.cashObjects())jdbc.update("""
            insert into fake_subscription_cash_objects values (?,?,?,?,?,?,?,?,?::jsonb)
            on conflict(payment_account_id,provider_reference) do update set status=excluded.status,occurred_at=excluded.occurred_at,cash_object=excluded.cash_object
            """,account.value(),s.id,invoice.reference(),cash.reference(),cash.amountMinor(),cash.currency(),cash.status().name(),time(cash.occurredAt()),encode(cash));
    }
    private void charge(PaymentAccountId account,State s,BillingRecord.Kind kind,SubscriptionPriceTerms price,SubscriptionPriceTerms previous,
                        Subscription.Period period,long due,String key,Scenario scenario,Instant at) {
        String ref=reference("fake_in_",account.value()+":"+s.id+":"+key);
        var invoice=new Invoice(ref,kind,price,previous,period,due,price.regularAmount().currency(),due==0?SettlementKind.ZERO:SettlementKind.OPEN,List.of());
        invoice=attempt(s,invoice,key,scenario,at);
        writeInvoice(account,s,invoice);
        applyInvoice(s,invoice);
    }
    private Invoice attempt(State s,Invoice invoice,String key,Scenario scenario,Instant at) {
        if(invoice.amountDueMinor()==0)return invoice;
        var cash=new ArrayList<>(invoice.cashObjects());
        SettlementKind settlement=SettlementKind.OPEN;
        if(scenario==Scenario.UNSUPPORTED_SETTLEMENT)settlement=SettlementKind.UNSUPPORTED;
        else if(scenario!=Scenario.UNKNOWN) {
            var status=switch(scenario){case DECLINE->PaymentStatus.FAILED;case REQUIRES_ACTION->PaymentStatus.REQUIRES_ACTION;case PROCESSING->PaymentStatus.PROCESSING;default->PaymentStatus.SUCCEEDED;};
            cash.add(new CashObject(reference("fake_cash_",invoice.reference()+":"+key),invoice.amountDueMinor(),invoice.currency(),status,at));
            if(status==PaymentStatus.SUCCEEDED){settlement=SettlementKind.CASH;s.methodReady=true;}
            if(status==PaymentStatus.REQUIRES_ACTION)action(s,ActionKind.FAKE_CONFIRM_PAYMENT,at,invoice.reference(),invoice.price());
        }
        return new Invoice(invoice.reference(),invoice.kind(),invoice.price(),invoice.previousPrice(),invoice.period(),invoice.amountDueMinor(),invoice.currency(),settlement,cash);
    }
    private void applyInvoice(State s,Invoice invoice) {
        // Invoice/cash history is persisted by the caller even when the lifecycle is terminal.
        if(s.status==Subscription.Status.CANCELLED)return;
        boolean settled=invoice.settlement()==SettlementKind.CASH||invoice.settlement()==SettlementKind.ZERO;
        if(settled) {s.price=invoice.price();s.period=invoice.period();s.status=Subscription.Status.ACTIVE;}
        else if(invoice.kind()!=BillingRecord.Kind.PRORATION)s.status=invoice.kind()==BillingRecord.Kind.INITIAL?Subscription.Status.INCOMPLETE:Subscription.Status.PAST_DUE;
    }
    private Instant confirmedBoundary(PaymentAccountId account,State s,Instant at) {
        if(s.status==Subscription.Status.CANCELLED||s.cancellation!=null)throw invalid();
        if(s.status==Subscription.Status.TRIALING&&s.trial!=null&&!at.isBefore(s.trial.start())&&at.isBefore(s.trial.end()))return s.trial.end();
        return invoices(account,s.id).stream().filter(i->i.settlement()==SettlementKind.CASH||i.settlement()==SettlementKind.ZERO)
            .filter(i->i.price().equals(s.price)&&i.period().equals(s.period)&&!at.isBefore(i.period().start())&&at.isBefore(i.period().end()))
            .map(i->i.period().end()).min(java.util.Comparator.naturalOrder()).orElseThrow(FakeSubscriptionStateAdapter::invalid);
    }
    private void recover(PaymentAccountId account,State s,Pending request,Instant at,Scenario scenario) {
        if(s.action!=null) {
            if(!s.actionAutonomous||s.action.kind()!=ActionKind.FAKE_CONFIRM_PAYMENT||s.actionRecoveryKey!=null
                ||request.invoiceReference()==null||!request.invoiceReference().equals(s.actionInvoice))throw conflict();
            s.actionRecoveryKey=request.key();refreshExpiredAction(s,request.key(),at);return;
        }
        var open=invoices(account,s.id).stream().filter(i->i.settlement()==SettlementKind.OPEN)
            .filter(i->request.invoiceReference()==null||request.invoiceReference().equals(i.reference())).toList();
        if(open.size()!=1)throw invalid();
        if(open.getFirst().cashObjects().stream().anyMatch(cash->cash.status()==PaymentStatus.PROCESSING||cash.status()==PaymentStatus.REQUIRES_ACTION))throw conflict();
        var invoice=attempt(s,open.getFirst(),request.key(),scenario,at);writeInvoice(account,s,invoice);applyInvoice(s,invoice);
    }
    /** Call only inside provider account lock, after authorization of the original/associated key.
     * Replaces capability material only; generic null-key reads must never invoke it. */
    private void refreshExpiredAction(State s,String authorizedKey,Instant at) {
        if(s.action==null||authorizedKey==null||!ownsAction(s,authorizedKey))throw conflict();
        if(!at.isBefore(s.action.expiresAt()))s.action=new NextAction(s.action.kind(),UUID.randomUUID().toString()+UUID.randomUUID(),at.plus(Duration.ofMinutes(10)));
    }
    public ProviderQuote preview(QuoteRequest request) {
        var c=request.command();requireFake(c.account());
        return atomic(c.account().id(),()->{
            var s=load(c.account().id(),c.operation().subscriptionId());validateTerms(s,request.target());
            if(!s.price.equals(c.current())||s.status!=Subscription.Status.ACTIVE||s.action!=null||s.cancellation!=null)throw invalid();
            if(c.providerSubscriptionReference()!=null&&!c.providerSubscriptionReference().equals(s.reference))throw invalid();
            var at=now(c.account().id());if(!at.isBefore(s.period.end()))throw invalid();
            if(c.providerQuoteReference()!=null) {
                var quoted=quoteFor(c.account().id(),s,c,at);
                if(quoted.timing()!=request.timing()||!quoted.target().equals(request.target()))throw invalid();
                return quoted.quote();
            }
            long due=0;
            if(request.timing()==ChangeTiming.IMMEDIATE&&scenario(c.account().id(),s.id)!=Scenario.ZERO_PRORATION) {
                long delta=Math.subtractExact(request.target().regularAmount().minorUnits(),s.price.regularAmount().minorUnits());
                due=BigInteger.valueOf(delta).multiply(BigInteger.valueOf(Duration.between(at,s.period.end()).toSeconds())).divide(BigInteger.valueOf(Duration.between(s.period.start(),s.period.end()).toSeconds())).longValueExact();
            }
            var quote=new ProviderQuote("fake_quote_"+UUID.randomUUID(),due,request.target().regularAmount().minorUnits(),request.target().regularAmount().currency(),request.timing()==ChangeTiming.IMMEDIATE?at:s.period.end(),at.plus(Duration.ofMinutes(5)),true);
            jdbc.update("insert into fake_subscription_quotes values (?,?,?,?::jsonb)",c.account().id().value(),s.id,quote.reference(),encode(new QuoteState(s.price,request.target(),request.timing(),s.revision,quote)));
            return quote;
        });
    }
    private void validateTerms(State s,SubscriptionPriceTerms target) {
        if(target==null||!target.productId().equals(s.price.productId())||!target.regularAmount().currency().equals(s.price.regularAmount().currency()))throw invalid();
    }
    private QuoteState quoteFor(PaymentAccountId account,State s,Command c,Instant at) {
        validateTerms(s,c.target());
        var rows=jdbc.queryForList("select quote from fake_subscription_quotes where payment_account_id=? and subscription_id=? and provider_reference=?",account.value(),s.id,c.providerQuoteReference());
        if(rows.size()!=1)throw invalid();var quote=decode(rows.getFirst().get("quote"),QuoteState.class);
        if(!quote.current().equals(s.price)||!quote.current().equals(c.current())||!quote.target().equals(c.target())
            ||quote.revision()!=s.revision||!at.isBefore(quote.quote().expiresAt())||s.action!=null||s.scheduledTarget!=null||s.cancellation!=null||s.status!=Subscription.Status.ACTIVE)throw conflict();
        if(quote.timing()==ChangeTiming.IMMEDIATE&&quote.quote().dueNowMinor()<0)throw invalid();
        return quote;
    }
    private void applyQuote(PaymentAccountId account,State s,Pending request,Instant at,Scenario scenario) {
        var quote=request.quote();
        if(quote.timing()==ChangeTiming.NEXT_PERIOD)s.scheduledTarget=quote.target();
        else {
            charge(account,s,BillingRecord.Kind.PRORATION,quote.target(),quote.current(),new Subscription.Period(quote.quote().effectiveAt(),s.period.end()),quote.quote().dueNowMinor(),request.key(),scenario,at);
        }
    }
    private void complete(PaymentAccountId account,State s,Instant at) {
        if(s.action==null||!at.isBefore(s.action.expiresAt())||s.status==Subscription.Status.CANCELLED)throw invalid();
        if(s.action.kind()==ActionKind.FAKE_CONFIRM_SETUP) {
            s.methodReady=true;
            if(s.status==Subscription.Status.INCOMPLETE&&startsTrial(s)&&s.trial==null)startTrial(s,at);
        } else {
            var invoice=invoices(account,s.id).stream().filter(i->i.reference().equals(s.actionInvoice)).findFirst().orElseThrow(FakeSubscriptionStateAdapter::invalid);
            var cash=invoice.cashObjects().stream().map(c->c.status()==PaymentStatus.REQUIRES_ACTION?new CashObject(c.reference(),c.amountMinor(),c.currency(),PaymentStatus.SUCCEEDED,at):c).toList();
            var paid=new Invoice(invoice.reference(),invoice.kind(),invoice.price(),invoice.previousPrice(),invoice.period(),invoice.amountDueMinor(),invoice.currency(),SettlementKind.CASH,cash);
            writeInvoice(account,s,paid);applyInvoice(s,paid);s.methodReady=true;
        }
        if(s.actionOperationKey!=null&&s.operations.containsKey(s.actionOperationKey)) {
            s.operations.put(s.actionOperationKey,OperationState.APPLIED);s.operationKey=s.actionOperationKey;
        }
        if(s.actionRecoveryKey!=null) {
            s.operations.put(s.actionRecoveryKey,OperationState.APPLIED);s.operationKey=s.actionRecoveryKey;
        }
        s.action=null;s.actionInvoice=null;s.actionTarget=null;s.actionOperationKey=null;s.actionRecoveryKey=null;s.actionAutonomous=false;
    }
    @Override public void completeAction(PaymentAccountId account,UUID subscriptionId) {
        atomic(account,()->{var s=load(account,subscriptionId);var at=now(account);complete(account,s,at);save(account,s,at,"SUBSCRIPTION_ACTION_COMPLETED");return null;});
    }
    @Override public void advance(PaymentAccountId account,Instant until) {
        Objects.requireNonNull(until);
        atomic(account,()->{
            if(until.isBefore(now(account)))throw invalid();
            var ids=jdbc.queryForList("select subscription_id from fake_subscription_objects where payment_account_id=? order by subscription_id",UUID.class,account.value());
            for(var id:ids)advance(account,load(account,id),until);
            jdbc.update("update fake_subscription_account_clocks set simulated_at=? where payment_account_id=?",time(until),account.value());return null;
        });
    }
    private void advance(PaymentAccountId account,State s,Instant until) {
        resolvePending(account,s,until);
        if(s.pending!=null)return;
        int iterations=0;
        while(s.status!=Subscription.Status.CANCELLED) {
            if(++iterations>12000)throw new IllegalArgumentException("Fake advance exceeds supported horizon");
            if(s.cancellation!=null&&!until.isBefore(s.cancellation)) {
                var at=s.cancellation;s.status=Subscription.Status.CANCELLED;s.cancellation=null;s.scheduledTarget=null;s.action=null;
                save(account,s,at,"SUBSCRIPTION_CANCELLED");break;
            }
            // Do not overwrite a capability or let its eventual completion restore an older period.
            // Cancellation above remains effective while billing waits for the outstanding action.
            if(s.action!=null)break;
            if((s.status!=Subscription.Status.ACTIVE&&s.status!=Subscription.Status.TRIALING)||until.isBefore(s.period.end()))break;
            var at=s.period.end();boolean trial=s.status==Subscription.Status.TRIALING;
            if(trial&&!s.methodReady){s.status=Subscription.Status.PAUSED;save(account,s,at,"SUBSCRIPTION_PAUSED");break;}
            if(s.scheduledTarget!=null) {
                s.price=s.scheduledTarget;s.scheduledTarget=null;
            }
            if(s.anchorInterval!=s.price.interval())resetAnchor(s,at);
            else if(!trial)s.periodIndex++;
            s.period=period(s,s.periodIndex);
            var key="renewal:"+s.id+":"+at;
            var scenario=scenario(account,s.id);
            charge(account,s,trial?BillingRecord.Kind.INITIAL:BillingRecord.Kind.RENEWAL,s.price,null,s.period,s.price.regularAmount().minorUnits(),key,scenario,at);
            if(s.action!=null&&s.actionOperationKey==null){s.actionOperationKey=key;s.actionAutonomous=true;}
            if(scenario==Scenario.UNKNOWN||scenario==Scenario.PROCESSING)
                s.pending=new Pending("RENEWAL",key,null,false,false,null);
            save(account,s,at,"SUBSCRIPTION_INVOICE_CREATED");
        }
    }
    private void resolvePending(PaymentAccountId account,State s,Instant at) {
        if(s.pending==null)return;
        var request=s.pending;
        // Neither an unresolved withdrawal nor standalone method setup displaces an
        // already confirmed cancellation. Reject the unfinished nonfinancial intent
        // at that boundary; retain its immutable receipt and scoped negative proof.
        if((request.method().equals("WITHDRAW_CANCEL")||request.method().equals("SETUP"))&&(s.status==Subscription.Status.CANCELLED
            ||s.cancellation!=null&&!at.isBefore(s.cancellation))){
            observe(s,request,Scenario.DECLINE);s.pending=null;
            save(account,s,at,"SUBSCRIPTION_OPERATION_RESOLVED");return;
        }
        var scenario=scenario(account,s.id);
        if(scenario==Scenario.UNKNOWN||scenario==Scenario.PROCESSING)return;
        String invoiceRef=reference("fake_in_",account.value()+":"+s.id+":"+request.key());
        var pendingInvoice=invoices(account,s.id).stream().filter(i->i.reference().equals(invoiceRef)
            ||request.method().equals("RECOVER")&&i.settlement()==SettlementKind.OPEN
                &&(request.invoiceReference()==null||request.invoiceReference().equals(i.reference()))).findFirst();
        boolean applied=false;
        if(pendingInvoice.isPresent()) {
            var invoice=pendingInvoice.get();
            var processing=invoice.cashObjects().stream().filter(c->c.status()==PaymentStatus.PROCESSING).findFirst();
            Invoice resolved;
            if(processing.isEmpty())resolved=attempt(s,invoice,request.key(),scenario,at);
            else {
                var status=switch(scenario){case DECLINE->PaymentStatus.FAILED;case REQUIRES_ACTION->PaymentStatus.REQUIRES_ACTION;case UNSUPPORTED_SETTLEMENT->PaymentStatus.CANCELLED;default->PaymentStatus.SUCCEEDED;};
                var cash=invoice.cashObjects().stream().map(c->c.reference().equals(processing.get().reference())?new CashObject(c.reference(),c.amountMinor(),c.currency(),status,at):c).toList();
                var settlement=status==PaymentStatus.SUCCEEDED?SettlementKind.CASH:scenario==Scenario.UNSUPPORTED_SETTLEMENT?SettlementKind.UNSUPPORTED:SettlementKind.OPEN;
                resolved=new Invoice(invoice.reference(),invoice.kind(),invoice.price(),invoice.previousPrice(),invoice.period(),invoice.amountDueMinor(),invoice.currency(),settlement,cash);
                if(status==PaymentStatus.REQUIRES_ACTION)action(s,ActionKind.FAKE_CONFIRM_PAYMENT,at,invoice.reference(),invoice.price());
                if(status==PaymentStatus.SUCCEEDED)s.methodReady=true;
            }
            writeInvoice(account,s,resolved);applyInvoice(s,resolved);applied=true;
        } else if(scenario!=Scenario.DECLINE||financialAttempt(s,request)) {
            apply(account,s,request,scenario,at);applied=true;
        }
        observe(s,request,scenario);
        if(applied&&s.operations.get(request.key())==OperationState.APPLIED)s.operationKey=request.key();
        s.pending=null;save(account,s,at,"SUBSCRIPTION_OPERATION_RESOLVED");
    }
    public PortalNavigation portal(Command c,String allowedReturnUrl) {
        requireFake(c.account());
        URI uri;
        try{uri=URI.create(allowedReturnUrl);}catch(RuntimeException malformed){throw invalid();}
        if(allowedReturnUrl.length()>2048||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null
                ||uri.getPort()==0||uri.getPort()>65535||portalControls(allowedReturnUrl)||portalControls(uri.getPath())||portalControls(uri.getQuery()))throw invalid();
        if(!"https".equalsIgnoreCase(uri.getScheme())&&!(localPortal&&"http".equalsIgnoreCase(uri.getScheme())
                &&Set.of("localhost","127.0.0.1","[::1]").stream().anyMatch(uri.getHost()::equalsIgnoreCase)))throw invalid();
        if(uri.getQuery()!=null)for(String parameter:uri.getQuery().split("&")) {
            String name=parameter.split("=",2)[0];
            if(name.equals("subscriptionPortal")||name.equals("operationId"))throw invalid();
        }
        if(c.operation().kind()!=SubscriptionOperation.Kind.PORTAL)throw invalid();
        return atomic(c.account().id(),()->{
            var s=load(c.account().id(),c.operation().subscriptionId());
            if(c.providerSubscriptionReference()!=null&&!c.providerSubscriptionReference().equals(s.reference))throw invalid();
            var key=c.operation().providerOperationKey();String fp=hash(continuationFingerprint("PORTAL",c,null)+allowedReturnUrl);
            var rows=jdbc.queryForList("select fingerprint,reply from fake_subscription_operations where payment_account_id=? and operation_key=?",c.account().id().value(),key);
            if(!rows.isEmpty()) {if(!fp.equals(rows.getFirst().get("fingerprint")))throw conflict();return decode(rows.getFirst().get("reply"),PortalNavigation.class);}
            var at=now(c.account().id());var navigation=new PortalNavigation(allowedReturnUrl+(uri.getRawQuery()==null?"?":"&")+"subscriptionPortal=simulated&operationId="+c.operation().id(),at.plus(Duration.ofMinutes(5)),true);
            jdbc.update("insert into fake_subscription_operations values (?,?,?,?,?,?::jsonb,?)",c.account().id().value(),s.id,key,fp,s.reference,encode(navigation),time(at));
            return navigation;
        });
    }
    private static boolean portalControls(String value) {
        return value!=null&&value.chars().anyMatch(Character::isISOControl);
    }
}
