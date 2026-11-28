package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.subscriptions.domain.ChangeTiming;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;

/** Immutable mapping, committed independently after the fenced dispatch receipt. No HTTP in this transaction. */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class StripeBillingBindingAdapter implements StripeBillingBindingPort,SubscriptionPlanProofStore {
    private final JdbcTemplate jdbc;
    private final ProviderOperationStore operations;
    private final ObjectMapper json;
    public StripeBillingBindingAdapter(JdbcTemplate jdbc, ProviderOperationStore operations,ObjectMapper json) {
        this.jdbc = jdbc; this.operations = operations;this.json=json;
    }
    public Quote saveQuote(Quote q){
        if(q==null||q.lines().size()>2||q.source()==null||!q.source().equals(q.entitled())||!q.source().productId().equals(q.target().productId())||!q.source().regularAmount().currency().equals(q.target().regularAmount().currency())||!q.period().end().equals(q.paidThrough()))throw conflict();
        if(jdbc.queryForList("""
            select s.id from subscriptions s join sites site on site.id=s.site_id join customers c on c.site_id=s.site_id and c.id=s.customer_id
            join payment_accounts a on a.id=s.payment_account_id join site_subscription_policies p on p.site_id=s.site_id
            where s.id=? and s.site_id=? and s.customer_id=? and s.payment_account_id=? and s.provider_reference=? and s.revision=?
            and s.billing_price_id=? and s.billing_price_version=? and s.entitled_price_id=s.billing_price_id and s.entitled_price_version=s.billing_price_version
            and s.current_period_start=? and s.current_period_end=? and s.paid_through=? and s.pending_operation_id is null
            and s.billing_status='ACTIVE' and s.debt_billing_record_id is null and s.cancellation_effective_at is null
            and site.status='ACTIVE' and c.status='ACTIVE' and a.account_status='ACTIVE' and a.provider='STRIPE' and a.account_mode='SANDBOX' and p.enabled and p.version=?
            """,UUID.class,q.subscription(),q.site().value(),q.customer().value(),q.account().value(),q.subscriptionReference(),q.revision(),q.source().priceId().value(),q.source().version(),java.sql.Timestamp.from(q.period().start()),java.sql.Timestamp.from(q.period().end()),java.sql.Timestamp.from(q.paidThrough()),q.policy()).size()!=1)throw conflict();
        jdbc.update("""
            insert into stripe_plan_quotes(id,payment_account_id,site_id,customer_id,subscription_id,reference,subscription_reference,customer_reference,source_price_id,target_price_id,subscription_revision,policy_version,timing,anchor,period_start,period_end,expires_at,due_now_minor,descriptor)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb) on conflict(id) do nothing
            """,q.id(),q.account().value(),q.site().value(),q.customer().value(),q.subscription(),q.reference(),q.subscriptionReference(),q.customerReference(),q.source().priceId().value(),q.target().priceId().value(),q.revision(),q.policy(),q.timing().name(),q.anchor().getEpochSecond(),q.period().start().getEpochSecond(),q.period().end().getEpochSecond(),q.expiresAt().getEpochSecond(),q.dueNow(),json.writeValueAsString(q));
        return quote(q.account(),q.id()).filter(q::equals).orElseThrow(StripeBillingBindingAdapter::conflict);
    }
    public Optional<Quote> quote(PaymentAccountId a,UUID id){return jdbc.query("select descriptor::text from stripe_plan_quotes where payment_account_id=? and id=?",(r,n)->json.readValue(r.getString(1),Quote.class),a.value(),id).stream().findFirst();}
    public void authorize(Subscription s,SubscriptionOperation o,Quote q){lockPlan(s,o,null);requirePlan(s,o,q);}
    private UUID requirePlan(Subscription s,SubscriptionOperation o,Quote q){
        if(!q.account().equals(s.paymentAccountId())||!q.subscription().equals(s.id())||!q.site().equals(s.siteId())||!q.customer().equals(s.customerId())||!q.subscriptionReference().equals(s.providerReference())||!q.source().equals(s.billingPrice())||!q.entitled().equals(s.entitledPrice())||!q.period().equals(s.currentPeriod())||!q.paidThrough().equals(s.paidThrough())||!quote(q.account(),q.id()).filter(q::equals).isPresent())throw conflict();
        var rows=jdbc.queryForList("""
            select q.id from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
            join subscription_change_intents i on i.operation_id=o.id and i.site_id=o.site_id and i.subscription_id=o.subscription_id
            join subscription_quotes q on q.id=i.quote_id and q.site_id=i.site_id and q.subscription_id=i.subscription_id
            join site_subscription_policies p on p.site_id=s.site_id join payment_accounts a on a.id=s.payment_account_id
            join sites site on site.id=s.site_id join customers c on c.site_id=s.site_id and c.id=s.customer_id
            where o.id=? and o.version=? and o.fingerprint=? and o.provider_operation_key=? and o.status in ('PROCESSING','REQUIRES_ACTION','UNKNOWN')
            and s.revision=? and s.payment_account_id=? and s.billing_status='ACTIVE' and s.debt_billing_record_id is null and s.cancellation_effective_at is null
            and s.pending_operation_id is not null and s.pending_price_id=? and s.pending_price_version=? and s.pending_effective_at=?
            and ((o.kind='CHANGE' and s.pending_operation_id=o.id and s.revision=q.subscription_revision+1)
              or (o.kind='CLEAR_CHANGE' and s.pending_state='SCHEDULED' and exists(select 1 from stripe_plan_schedules x where x.operation_id=s.pending_operation_id and x.quote_id=? and x.update_step is not null and x.release_step is null)))
            and q.provider_quote_reference=? and q.subscription_revision=? and q.policy_version=? and q.timing=? and q.from_price_id=? and q.from_price_version=?
            and q.target_price_id=? and q.target_price_version=? and q.target_amount_minor=? and q.currency=? and q.due_now_minor=? and q.next_amount_minor=?
            and q.effective_at=? and q.expires_at=? and q.tax_final=true and p.enabled and p.version=o.policy_version and a.account_status='ACTIVE' and a.provider='STRIPE' and a.account_mode='SANDBOX' and site.status='ACTIVE' and c.status='ACTIVE'
            """,UUID.class,o.id(),o.version(),o.fingerprint(),o.providerOperationKey(),s.revision(),s.paymentAccountId().value(),q.target().priceId().value(),q.target().version(),java.sql.Timestamp.from(q.timing()==ChangeTiming.IMMEDIATE?q.anchor():q.period().end()),q.id(),q.reference(),q.revision(),q.policy(),q.timing().name(),q.source().priceId().value(),q.source().version(),q.target().priceId().value(),q.target().version(),q.target().regularAmount().minorUnits(),q.target().regularAmount().currency(),q.dueNow(),q.target().regularAmount().minorUnits(),java.sql.Timestamp.from(q.timing()==ChangeTiming.IMMEDIATE?q.anchor():q.period().end()),java.sql.Timestamp.from(q.expiresAt()));
        if(rows.size()!=1)throw conflict();return rows.getFirst();
    }
    private void lockPlan(Subscription s,SubscriptionOperation o,ProviderOperation step){
        if(!o.subscriptionId().equals(s.id())||!o.siteId().equals(s.siteId())||!o.customerId().equals(s.customerId())||!Set.of(SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Kind.CLEAR_CHANGE).contains(o.kind())||jdbc.queryForList("select id from subscription_operations where id=? and version=? and fingerprint=? and provider_operation_key=? for update",UUID.class,o.id(),o.version(),o.fingerprint(),o.providerOperationKey()).size()!=1)throw conflict();
        jdbc.queryForList("select id from sites where id=? for update",UUID.class,s.siteId().value());jdbc.queryForList("select id from customers where site_id=? and id=? for update",UUID.class,s.siteId().value(),s.customerId().value());
        if(step!=null){if(!step.resourceId().equals(o.id())||!step.accountId().equals(s.paymentAccountId()))throw conflict();operations.lockPlanReceipt(step);}
        jdbc.queryForList("select id from payment_accounts where id=? for update",UUID.class,s.paymentAccountId().value());jdbc.queryForList("select id from subscriptions where site_id=? and id=? for update",UUID.class,s.siteId().value(),s.id());
    }
    public Result recordResult(Subscription s,SubscriptionOperation o,Quote q,ProviderOperation step,String owner,String invoice,Long expiry,String creditLine,String debitLine){
        lockPlan(s,o,step);var common=requirePlan(s,o,q);if(o.kind()!=SubscriptionOperation.Kind.CHANGE||q.timing()!=ChangeTiming.IMMEDIATE||step.kind()!=ProviderOperation.Kind.SUBSCRIPTION_UPDATE)throw conflict();
        operations.acknowledgeInTransaction(step.id(),owner,step.version(),q.subscriptionReference());
        jdbc.update("insert into stripe_plan_results(operation_id,site_id,customer_id,subscription_id,payment_account_id,quote_id,common_quote_id,quote_reference,step_id,subscription_reference,invoice_reference,pending_expiry,credit_line,debit_line) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(operation_id) do nothing",o.id(),s.siteId().value(),s.customerId().value(),s.id(),s.paymentAccountId().value(),q.id(),common,q.reference(),step.id(),q.subscriptionReference(),invoice,expiry,creditLine,debitLine);
        return result(q.account(),o.id()).filter(v->v.quote().equals(q.id())&&v.step().equals(step.id())&&v.invoice().equals(invoice)&&Objects.equals(v.pendingExpiry(),expiry)&&v.creditLine().equals(creditLine)&&v.debitLine().equals(debitLine)).orElseThrow(StripeBillingBindingAdapter::conflict);
    }
    public Optional<Result> result(PaymentAccountId a,UUID operation){return jdbc.query("select * from stripe_plan_results where payment_account_id=? and operation_id=?",this::planResult,a.value(),operation).stream().findFirst();}
    public Optional<Proration> proration(PaymentAccountId a,String invoice){return jdbc.query("select * from stripe_plan_results where payment_account_id=? and invoice_reference=?",this::planResult,a.value(),invoice).stream().findFirst().map(r->new Proration(quote(a,r.quote()).orElseThrow(StripeBillingBindingAdapter::conflict),r));}
    public List<Proration> prorations(PaymentAccountId a,UUID subscription){var results=jdbc.query("select * from stripe_plan_results where payment_account_id=? and subscription_id=? order by operation_id limit 101",this::planResult,a.value(),subscription);if(results.size()>100)throw conflict();return results.stream().map(r->new Proration(quote(a,r.quote()).orElseThrow(StripeBillingBindingAdapter::conflict),r)).toList();}
    private Result planResult(ResultSet r,int n)throws SQLException{return new Result(r.getObject("operation_id",UUID.class),r.getObject("subscription_id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getObject("quote_id",UUID.class),r.getObject("step_id",UUID.class),r.getString("subscription_reference"),r.getString("invoice_reference"),r.getObject("pending_expiry",Long.class),r.getString("credit_line"),r.getString("debit_line"));}
    public Optional<Schedule> schedule(PaymentAccountId a,UUID operation){return jdbc.query("select * from stripe_plan_schedules where payment_account_id=? and operation_id=?",this::planSchedule,a.value(),operation).stream().findFirst();}
    public List<ScheduleProof> schedules(PaymentAccountId a,UUID subscription){var rows=jdbc.query("select * from stripe_plan_schedules where payment_account_id=? and subscription_id=? and update_step is not null order by operation_id limit 101",this::planSchedule,a.value(),subscription);if(rows.size()>100)throw conflict();return rows.stream().map(s->new ScheduleProof(quote(a,s.quote()).orElseThrow(StripeBillingBindingAdapter::conflict),s)).toList();}
    public Optional<Schedule> scheduleReference(PaymentAccountId a,String reference){return jdbc.query("select * from stripe_plan_schedules where payment_account_id=? and schedule_reference=?",this::planSchedule,a.value(),reference).stream().findFirst();}
    public Schedule recordSchedule(Subscription s,SubscriptionOperation o,Quote q,ProviderOperation step,String owner,String reference,long start,SourcePhase sourcePhase){
        Objects.requireNonNull(sourcePhase);
        lockPlan(s,o,step);var common=requirePlan(s,o,q);
        if(o.kind()!=SubscriptionOperation.Kind.CHANGE||q.timing()!=ChangeTiming.NEXT_PERIOD||step.kind()!=ProviderOperation.Kind.SCHEDULE_CREATE)throw conflict();
        operations.acknowledgeInTransaction(step.id(),owner,step.version(),reference);
        jdbc.update("""
            insert into stripe_plan_schedules(operation_id,site_id,customer_id,subscription_id,payment_account_id,quote_id,
                common_quote_id,quote_reference,schedule_reference,creation_step,phase_start,source_proration_behavior,
                source_default_payment_method,source_collection_method) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            on conflict(operation_id) do nothing
            """,o.id(),s.siteId().value(),s.customerId().value(),s.id(),s.paymentAccountId().value(),q.id(),common,q.reference(),reference,
            step.id(),start,sourcePhase.prorationBehavior(),sourcePhase.defaultPaymentMethod(),sourcePhase.collectionMethod());
        return schedule(q.account(),o.id()).filter(v->v.quote().equals(q.id())&&v.creationStep().equals(step.id())
            &&v.reference().equals(reference)&&v.phaseStart()==start&&v.sourcePhase().equals(sourcePhase)).orElseThrow(StripeBillingBindingAdapter::conflict);
    }
    public Schedule recordScheduleUpdate(Subscription s,SubscriptionOperation o,Schedule expected,ProviderOperation step,String owner,long end){
        var q=quote(expected.account(),expected.quote()).orElseThrow(StripeBillingBindingAdapter::conflict);lockPlan(s,o,step);requirePlan(s,o,q);
        if(step.kind()!=ProviderOperation.Kind.SCHEDULE_UPDATE||!expected.operation().equals(o.id())||!schedule(q.account(),o.id()).filter(expected::equals).isPresent()||end<=q.period().end().getEpochSecond())throw conflict();
        operations.acknowledgeInTransaction(step.id(),owner,step.version(),expected.reference());
        jdbc.update("update stripe_plan_schedules set update_step=?,target_end=? where operation_id=? and update_step is null",step.id(),end,o.id());return schedule(q.account(),o.id()).filter(v->v.updateStep().equals(step.id())&&v.targetEnd()==end).orElseThrow(StripeBillingBindingAdapter::conflict);
    }
    public Schedule recordRelease(Subscription s,SubscriptionOperation o,Schedule expected,ProviderOperation step,String owner){
        var q=quote(expected.account(),expected.quote()).orElseThrow(StripeBillingBindingAdapter::conflict);lockPlan(s,o,step);requirePlan(s,o,q);
        if(o.kind()!=SubscriptionOperation.Kind.CLEAR_CHANGE||step.kind()!=ProviderOperation.Kind.SCHEDULE_RELEASE||!expected.operation().equals(s.pendingChange().operationId())||!schedule(q.account(),expected.operation()).filter(expected::equals).isPresent())throw conflict();
        operations.acknowledgeInTransaction(step.id(),owner,step.version(),expected.reference());jdbc.update("update stripe_plan_schedules set clear_operation=?,release_step=? where operation_id=? and release_step is null",o.id(),step.id(),expected.operation());return schedule(q.account(),expected.operation()).filter(v->v.clearOperation().equals(o.id())&&v.releaseStep().equals(step.id())).orElseThrow(StripeBillingBindingAdapter::conflict);
    }
    private Schedule planSchedule(ResultSet r,int n)throws SQLException{return new Schedule(r.getObject("operation_id",UUID.class),r.getObject("subscription_id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getObject("quote_id",UUID.class),r.getObject("creation_step",UUID.class),r.getString("schedule_reference"),r.getLong("phase_start"),r.getObject("update_step",UUID.class),r.getObject("target_end",Long.class),r.getObject("clear_operation",UUID.class),r.getObject("release_step",UUID.class),new SourcePhase(r.getString("source_proration_behavior"),r.getString("source_default_payment_method"),r.getString("source_collection_method")));}
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<CustomerBinding> findCustomer(PaymentAccountId accountId, SiteId siteId, CustomerId customerId) {
        requireScope(accountId, siteId, customerId);
        return jdbc.query("select * from stripe_billing_customers where payment_account_id=? and site_id=? and customer_id=?",
            this::binding, accountId.value(), siteId.value(), customerId.value()).stream().findFirst();
    }
    @Override
    public CustomerBinding bindCustomer(PaymentAccountId accountId, SiteId siteId, CustomerId customerId,
                                       String reference, UUID originalOperationId) {
        requireScope(accountId, siteId, customerId);
        if (originalOperationId == null || reference == null || !reference.matches("cus_[A-Za-z0-9]{1,251}")) throw conflict();
        var snapshot = operations.findById(originalOperationId).orElseThrow(StripeBillingBindingAdapter::conflict);
        // Locks and revalidates operation -> original Account -> Site -> Customer before mapping.
        var operation = operations.lockRecovery(snapshot);
        if (operation.kind() != ProviderOperation.Kind.CUSTOMER_CREATE
            || operation.status() != ProviderOperation.Status.ACKNOWLEDGED
            || !accountId.equals(operation.accountId()) || !customerId.value().equals(operation.resourceId())
            || !reference.equals(operation.providerReference()) || operation.firstDispatchedAt() == null
            || jdbc.queryForList("select id from customers where id=? and site_id=?", UUID.class,
                customerId.value(), siteId.value()).size() != 1) throw conflict();
        try {
            jdbc.update("""
                insert into stripe_billing_customers(id,payment_account_id,site_id,customer_id,provider_reference,creation_operation_id)
                values (?,?,?,?,?,?) on conflict do nothing
                """, UUID.randomUUID(), accountId.value(), siteId.value(), customerId.value(), reference, originalOperationId);
            var rows = jdbc.query("""
                select * from stripe_billing_customers where payment_account_id=?
                 and ((site_id=? and customer_id=?) or provider_reference=?) for update
                """, this::binding, accountId.value(), siteId.value(), customerId.value(), reference);
            if (rows.size() != 1) throw conflict();
            var binding = rows.getFirst();
            if (!binding.accountId().equals(accountId) || !binding.siteId().equals(siteId)
                || !binding.customerId().equals(customerId) || !binding.providerReference().equals(reference)
                || !binding.creationOperationId().equals(originalOperationId)) throw conflict();
            return binding;
        } catch (DataIntegrityViolationException invalidProof) { throw conflict(); }
    }
    private CustomerBinding binding(ResultSet row, int index) throws SQLException {
        return new CustomerBinding(row.getObject("id", UUID.class),
            new PaymentAccountId(row.getObject("payment_account_id", UUID.class)),
            new SiteId(row.getObject("site_id", UUID.class)), new CustomerId(row.getObject("customer_id", UUID.class)),
            row.getString("provider_reference"), row.getObject("creation_operation_id", UUID.class), row.getLong("version"));
    }
    private static void requireScope(PaymentAccountId a, SiteId s, CustomerId c) {
        if (a == null || s == null || c == null) throw conflict();
    }
    private static PaymentApplicationException conflict() {
        return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT", 409, "Provider operation conflicts with the request");
    }
}
