package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingPortalAdapter;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionPortalService;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Safe immutable Portal authority; every HTTP request occurs outside these short transactions. */
@Repository @Transactional(propagation=Propagation.REQUIRES_NEW)
public class StripePortalConfigurationPersistenceAdapter implements StripePortalConfigurationPort {
    private final JdbcTemplate jdbc;private final ProviderOperationStore steps;private final SubscriptionStore subscriptions;
    private final SubscriptionOperationStore operations;private final SubscriptionPolicyPort policies;private final StripeBillingBindingPort bindings;private final ObjectMapper json;
    public StripePortalConfigurationPersistenceAdapter(JdbcTemplate jdbc,ProviderOperationStore steps,SubscriptionStore subscriptions,
        SubscriptionOperationStore operations,SubscriptionPolicyPort policies,StripeBillingBindingPort bindings,ObjectMapper json){
        this.jdbc=jdbc;this.steps=steps;this.subscriptions=subscriptions;this.operations=operations;this.policies=policies;this.bindings=bindings;this.json=json;
    }
    public Scope capture(Command command,String url){
        var o=operations.lock(command.operation().id());if(!o.equals(command.operation())||o.kind()!=SubscriptionOperation.Kind.PORTAL)throw conflict();
        base(o,command.account());var policy=policies.load(o.siteId());SubscriptionPortalService.requireAllowedReturn(policy,url);
        var members=members(command.account(),o);var selected=members.stream().filter(s->s.id().equals(o.subscriptionId())).findFirst().orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);
        if(selected.revision()!=o.expectedRevision()||o.policyVersion()!=policy.version()||!selected.nonTerminal()||!selected.billingPrice().equals(command.current())
            ||!Objects.equals(o.providerReference(),selected.providerReference())||!Objects.equals(command.providerSubscriptionReference(),selected.providerReference()))throw conflict();
        var customer=bindings.findCustomer(command.account().id(),o.siteId(),o.customerId()).orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);
        var scope=new Scope(command.account(),customer,o,policy,members,url);requireScope(scope,false);return scope;
    }
    public void authorize(Scope scope){requireScope(scope,true);}
    private void base(SubscriptionOperation o,PaymentAccount a){
        if(o.kind()!=SubscriptionOperation.Kind.PORTAL||a.provider()!=PaymentProviderType.STRIPE||a.mode()!=PaymentAccountMode.SANDBOX||a.status()!=PaymentAccountStatus.ACTIVE
            ||!Set.of(SubscriptionOperation.Status.RESERVED,SubscriptionOperation.Status.SUCCEEDED).contains(o.status()))throw conflict();
        if(jdbc.queryForList("select id from sites where id=? and status='ACTIVE' for update",UUID.class,o.siteId().value()).size()!=1
            ||jdbc.queryForList("select id from customers where site_id=? and id=? and status='ACTIVE' for update",UUID.class,o.siteId().value(),o.customerId().value()).size()!=1
            ||jdbc.queryForList("select site_id from site_subscription_policies where site_id=? for update",UUID.class,o.siteId().value()).size()!=1
            ||jdbc.queryForList("select id from payment_accounts where id=? and provider='STRIPE' and account_mode='SANDBOX' and account_status='ACTIVE' and external_account_id=? and credentials_ref=? for update",UUID.class,a.id().value(),a.externalAccountId(),a.credentialsRef()).size()!=1)throw conflict();
    }
    private List<Subscription> members(PaymentAccount a,SubscriptionOperation o){
        var ids=jdbc.queryForList("select id from subscriptions where site_id=? and customer_id=? and payment_account_id=? order by id limit 101 for update",UUID.class,o.siteId().value(),o.customerId().value(),a.id().value());
        if(ids.isEmpty()||ids.size()>100)throw conflict();return ids.stream().map(id->subscriptions.find(o.siteId(),id).orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict)).sorted(Comparator.comparing(Subscription::id)).toList();
    }
    private void requireScope(Scope s,boolean lock){
        if(s==null||s.customer()==null||s.operation()==null||s.policy()==null||s.returnUrl()==null)throw conflict();
        var o=operations.lock(s.operation().id());if(!o.equals(s.operation()))throw conflict();if(lock)base(o,s.account());
        var policy=policies.load(o.siteId());SubscriptionPortalService.requireAllowedReturn(policy,s.returnUrl());
        if(!policy.equals(s.policy())||o.policyVersion()!=policy.version()||!s.customer().accountId().equals(s.account().id())||!s.customer().siteId().equals(o.siteId())||!s.customer().customerId().equals(o.customerId()))throw conflict();
        if(!bindings.findCustomer(s.account().id(),o.siteId(),o.customerId()).filter(s.customer()::equals).isPresent())throw conflict();
        var current=members(s.account(),o);
        if(!current.equals(s.members().stream().sorted(Comparator.comparing(Subscription::id)).toList()))throw conflict();
        var selected=current.stream().filter(m->m.id().equals(o.subscriptionId())).findFirst().orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);
        if(selected.revision()!=o.expectedRevision()||!selected.nonTerminal()||!Objects.equals(o.providerReference(),selected.providerReference()))throw conflict();
        for(var member:current)if(operations.unresolved(member.siteId(),member.id()).stream().anyMatch(other->other.kind()!=SubscriptionOperation.Kind.PORTAL&&other.kind()!=SubscriptionOperation.Kind.RECONCILE))throw conflict();
    }
    public Configuration intent(Configuration c){
        var o=operations.lock(c.owner());var a=account(c.account());var s=subscriptions.find(o.siteId(),o.subscriptionId()).orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);
        var p=policies.load(o.siteId());var scope=capture(new Command(a,o,s.billingPrice(),null,s.providerReference(),null,null,false,false),p.portalReturnUrls().stream().sorted().findFirst().orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict));
        if(!c.site().equals(o.siteId())||c.policy()!=o.policyVersion()||!c.digest().equals(StripeBillingPortalAdapter.policyDigest(scope.policy()))
            ||!c.fingerprint().equals(StripeBillingPortalAdapter.configFingerprint(scope,c.id(),c.owner(),c.key(),c.digest()))||c.reference()!=null||c.step()!=null)throw conflict();
        jdbc.update("insert into stripe_portal_configuration_intents(id,payment_account_id,site_id,policy_version,policy_digest,owner_operation,owner_subscription,operation_key,fingerprint) values (?,?,?,?,?,?,?,?,?) on conflict do nothing",c.id(),c.account().value(),c.site().value(),c.policy(),c.digest(),c.owner(),o.subscriptionId(),c.key(),c.fingerprint());
        var found=jdbc.query("select i.*,b.configuration_reference,b.creation_step from stripe_portal_configuration_intents i left join stripe_portal_configurations b on b.intent_id=i.id where i.payment_account_id=? and i.site_id=? and i.policy_version=?",this::mapConfiguration,c.account().value(),c.site().value(),c.policy());
        if(found.size()!=1||!found.getFirst().digest().equals(c.digest()))throw conflict();return found.getFirst();
    }
    public Configuration bind(Configuration c,UUID stepId,String reference){
        var step=steps.findById(stepId).orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);requireStep(step,c.account(),ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE,c.id(),c.key(),c.fingerprint(),reference);
        var saved=configuration(c.id());if(!sameConfigurationIntent(saved,c))throw conflict();
        jdbc.update("insert into stripe_portal_configurations(intent_id,payment_account_id,site_id,policy_version,policy_digest,configuration_reference,creation_step) values (?,?,?,?,?,?,?) on conflict(intent_id) do nothing",c.id(),c.account().value(),c.site().value(),c.policy(),c.digest(),reference,stepId);
        return Optional.of(configuration(c.id())).filter(v->reference.equals(v.reference())&&stepId.equals(v.step())).orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);
    }
    private Configuration configuration(UUID id){return jdbc.query("select i.*,b.configuration_reference,b.creation_step from stripe_portal_configuration_intents i left join stripe_portal_configurations b on b.intent_id=i.id where i.id=?",this::mapConfiguration,id).stream().findFirst().orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);}
    private Configuration mapConfiguration(ResultSet r,int n)throws SQLException{return new Configuration(r.getObject("id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),new dev.genericpayments.platform.sites.domain.SiteId(r.getObject("site_id",UUID.class)),r.getLong("policy_version"),r.getString("policy_digest"),r.getObject("owner_operation",UUID.class),r.getString("operation_key"),r.getString("fingerprint"),r.getString("configuration_reference"),r.getObject("creation_step",UUID.class));}
    private static boolean sameConfigurationIntent(Configuration a,Configuration b){return a.id().equals(b.id())&&a.account().equals(b.account())&&a.site().equals(b.site())&&a.policy()==b.policy()&&a.digest().equals(b.digest())&&a.owner().equals(b.owner())&&a.key().equals(b.key())&&a.fingerprint().equals(b.fingerprint());}
    public Session session(Session proposed){
        requireScope(proposed.scope(),true);var c=configuration(proposed.configuration().id());requireConfiguration(c);
        if(!c.equals(proposed.configuration())||!proposed.operation().equals(proposed.scope().operation().id())||!proposed.fingerprint().equals(StripeBillingPortalAdapter.sessionFingerprint(proposed.scope(),c))
            ||proposed.reference()!=null||proposed.step()!=null||proposed.expiresAt()!=null)throw conflict();
        var o=proposed.scope().operation();jdbc.update("insert into stripe_portal_session_intents(operation_id,site_id,customer_id,subscription_id,payment_account_id,configuration_id,configuration_reference,fingerprint,descriptor) values (?,?,?,?,?,?,?,?,?::jsonb) on conflict(operation_id) do nothing",o.id(),o.siteId().value(),o.customerId().value(),o.subscriptionId(),proposed.scope().account().id().value(),c.id(),c.reference(),proposed.fingerprint(),json.writeValueAsString(proposed.scope()));
        var found=session(proposed.operation());if(!found.fingerprint().equals(proposed.fingerprint())||!found.configuration().equals(c))throw conflict();return found;
    }
    private Session session(UUID operation){return jdbc.query("select i.*,r.session_reference,r.creation_step,r.expires_at from stripe_portal_session_intents i left join stripe_portal_session_receipts r on r.operation_id=i.operation_id where i.operation_id=?",(r,n)->{
        var expiry=r.getTimestamp("expires_at");return new Session(operation,configuration(r.getObject("configuration_id",UUID.class)),json.readValue(r.getString("descriptor"),Scope.class),r.getString("fingerprint"),r.getString("session_reference"),r.getObject("creation_step",UUID.class),expiry==null?null:expiry.toInstant());},operation).stream().findFirst().orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);}
    public Session record(Session expected,UUID stepId,String reference,Instant expiry){
        var saved=session(expected.operation());if(!saved.fingerprint().equals(expected.fingerprint())||!saved.scope().equals(expected.scope())||!saved.configuration().equals(expected.configuration())||expiry==null)throw conflict();
        var o=saved.scope().operation();var step=steps.findById(stepId).orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);
        requireStep(step,saved.scope().account().id(),ProviderOperation.Kind.PORTAL_SESSION_CREATE,o.id(),o.providerOperationKey()+":SESSION",saved.fingerprint(),reference);
        requireConfiguration(saved.configuration());
        jdbc.update("insert into stripe_portal_session_receipts(operation_id,payment_account_id,configuration_id,configuration_reference,session_reference,creation_step,expires_at) values (?,?,?,?,?,?,?) on conflict(operation_id) do nothing",o.id(),saved.scope().account().id().value(),saved.configuration().id(),saved.configuration().reference(),reference,stepId,Timestamp.from(expiry));
        return Optional.of(session(o.id())).filter(v->reference.equals(v.reference())&&stepId.equals(v.step())&&expiry.equals(v.expiresAt())).orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requirePublication(PaymentAccount a,Subscription s,SubscriptionOperation o,String returnUrl,Instant expiry){
        var receipt=session(o.id());if(!receipt.scope().account().equals(a)||!receipt.scope().operation().equals(o)||!receipt.scope().returnUrl().equals(returnUrl)||!receipt.scope().members().contains(s)
            ||!Objects.equals(expiry,receipt.expiresAt())||receipt.reference()==null||receipt.step()==null
            ||!Boolean.TRUE.equals(jdbc.queryForObject("select ?::timestamptz>clock_timestamp()",Boolean.class,Timestamp.from(expiry))))throw conflict();
        requireScope(receipt.scope(),true);requireConfiguration(receipt.configuration());
        requireStep(steps.findById(receipt.step()).orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict),a.id(),ProviderOperation.Kind.PORTAL_SESSION_CREATE,o.id(),o.providerOperationKey()+":SESSION",receipt.fingerprint(),receipt.reference());
        if(!receipt.fingerprint().equals(StripeBillingPortalAdapter.sessionFingerprint(receipt.scope(),receipt.configuration())))throw conflict();
    }
    private void requireConfiguration(Configuration c){
        if(c.reference()==null||c.step()==null||!configuration(c.id()).equals(c))throw conflict();
        requireStep(steps.findById(c.step()).orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict),c.account(),ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE,c.id(),c.key(),c.fingerprint(),c.reference());
    }
    private void requireStep(ProviderOperation step,PaymentAccountId account,ProviderOperation.Kind kind,UUID resource,String key,String fp,String ref){
        var a=account(account);
        if(step.status()!=ProviderOperation.Status.ACKNOWLEDGED||step.kind()!=kind||!step.accountId().equals(account)||!step.resourceId().equals(resource)||!step.operationKey().equals(key)||!step.fingerprint().equals(fp)
            ||!Objects.equals(step.providerReference(),ref)||!step.externalAccountId().equals(a.externalAccountId())||!step.credentialsRef().equals(a.credentialsRef())||step.firstDispatchedAt()==null||step.retryBefore()==null)throw conflict();
    }
    private PaymentAccount account(PaymentAccountId id){return jdbc.query("select * from payment_accounts where id=?",(r,n)->new PaymentAccount(id,r.getString("account_key"),PaymentProviderType.valueOf(r.getString("provider")),PaymentAccountMode.valueOf(r.getString("account_mode")),PaymentAccountStatus.valueOf(r.getString("account_status")),r.getString("external_account_id"),r.getString("credentials_ref")),id.value()).stream().findFirst().orElseThrow(StripePortalConfigurationPersistenceAdapter::conflict);}
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("SUBSCRIPTION_PORTAL_UNAVAILABLE",409,"Portal unavailable");}
}
