package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.*;
import dev.genericpayments.platform.partners.application.port.in.AuthenticatedSite;
import dev.genericpayments.platform.partners.application.port.out.SiteApiKeyPort;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.sql.Timestamp;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SubscriptionAuthorityIntegrationTest extends PostgresIntegrationTest {
    static final String ORIGIN="https://subscriptions.example.test";
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerSessionService sessions;
    @Autowired CustomerStore customers;
    @Autowired SubscriptionPolicyPort policies;
    @Autowired SubscriptionAuthorityPort authority;
    @Autowired SubscriptionTransactionPort tx;
    @Autowired SiteApiKeyPort keys;
    SiteId site;
    Customer customer;
    @BeforeEach void fixture() {
        clearDatabase();site=new SiteId(UUID.randomUUID());var account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'sub-authority','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,'sub-authority','Subscriptions',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),account);
        jdbc.update("insert into site_allowed_origins values (?,?)",site.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')",site.value());
        customer=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
    }
    void enable() {policies.save(new SubscriptionPolicy(site,0,true,3,false,false,false,Set.of()),0);}
    SubscriptionCommandContext context(SubscriptionActor actor,String origin){return new SubscriptionCommandContext(actor,site,customer.id(),origin,"key","request");}
    void check(SubscriptionCommandContext context){tx.required(()->{authority.revalidate(context,true);return null;});}
    void denied(SubscriptionCommandContext context){assertThatThrownBy(()->check(context)).isInstanceOf(PaymentApplicationException.class);}
    @Test void customerAuthorityChecksCurrentScopesRevocationExactOriginAndOwner() {
        var legacy=sessions.resolveRequired(sessions.issue(site,customer.id(),ORIGIN).rawToken(),ORIGIN);enable();
        denied(context(new SubscriptionActor.Customer(legacy),ORIGIN));
        var current=sessions.resolveRequired(sessions.issue(site,customer.id(),ORIGIN).rawToken(),ORIGIN);
        var valid=context(new SubscriptionActor.Customer(current),ORIGIN);check(valid);
        denied(context(new SubscriptionActor.Customer(current),ORIGIN+"/"));
        var other=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        denied(new SubscriptionCommandContext(valid.actor(),site,other.id(),ORIGIN,"key","request"));
        denied(new SubscriptionCommandContext(valid.actor(),new SiteId(UUID.randomUUID()),customer.id(),ORIGIN,"key","request"));
        sessions.logout(current);
        denied(valid);
        assertThatThrownBy(()->authority.revalidate(valid,true)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void partnerAuthorityRejectsLegacyAlteredRevokedAndExpiredKeys() {
        var legacy=new SiteApiKey(new SiteApiKeyId(UUID.randomUUID()),site,"yzabcdefghij","$argon2id$test",Set.of(PartnerScope.PAYMENTS_READ),SiteApiKey.Status.ACTIVE,INITIAL_TIME,null,null,null);
        keys.insert(legacy);enable();
        assertThat(keys.findByPrefix(legacy.prefix()).orElseThrow().scopes()).isEqualTo(legacy.scopes());
        denied(context(new SubscriptionActor.Partner(new AuthenticatedSite(site,legacy.id(),legacy.scopes())),null));
        var id=new SiteApiKeyId(UUID.randomUUID());
        var key=new SiteApiKey(id,site,"abcdefghijkl","$argon2id$test",Set.of(PartnerScope.SUBSCRIPTIONS_MANAGE),SiteApiKey.Status.ACTIVE,INITIAL_TIME,null,null,null);
        keys.insert(key);
        var valid=context(new SubscriptionActor.Partner(new AuthenticatedSite(site,id,key.scopes())),null);check(valid);
        denied(context(new SubscriptionActor.Partner(new AuthenticatedSite(site,id,Set.of(PartnerScope.SUBSCRIPTIONS_READ))),null));
        tx.required(()->{keys.update(key.revoke(INITIAL_TIME));return null;});denied(valid);
        var expired=new SiteApiKey(new SiteApiKeyId(UUID.randomUUID()),site,"mnopqrstuvwx","$argon2id$test",key.scopes(),SiteApiKey.Status.ACTIVE,INITIAL_TIME.minusSeconds(10),INITIAL_TIME,null,null);
        keys.insert(expired);denied(context(new SubscriptionActor.Partner(new AuthenticatedSite(site,expired.id(),expired.scopes())),null));
    }
    @Test void adminAuthorityChecksCurrentUserSessionBindingRevocationAndBothExpiries() {
        var user=new AdminUserId(UUID.randomUUID());var session=new AdminSessionId(UUID.randomUUID());
        jdbc.update("insert into admin_users(id,username,password_hash,status,created_at) values (?,'sub-admin','unused','ACTIVE',?)",user.value(),Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into admin_sessions(id,admin_user_id,token_hash,created_at,last_seen_at,expires_at,absolute_expires_at) values (?,?,'not-a-token',?,?,?,?)",session.value(),user.value(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME.plusSeconds(100)),Timestamp.from(INITIAL_TIME.plusSeconds(200)));
        var valid=context(new SubscriptionActor.Admin(new AuthenticatedAdmin(user,"sub-admin",session,INITIAL_TIME.plusSeconds(100))),null);check(valid);
        denied(context(new SubscriptionActor.Admin(new AuthenticatedAdmin(new AdminUserId(UUID.randomUUID()),"sub-admin",session,INITIAL_TIME.plusSeconds(100))),null));
        var other=new AdminUserId(UUID.randomUUID());
        jdbc.update("insert into admin_users(id,username,password_hash,status,created_at) values (?,'other-admin','unused','ACTIVE',?)",other.value(),Timestamp.from(INITIAL_TIME));
        denied(context(new SubscriptionActor.Admin(new AuthenticatedAdmin(other,"other-admin",session,INITIAL_TIME.plusSeconds(100))),null));
        jdbc.update("update admin_users set status='DISABLED' where id=?",user.value());denied(valid);
        jdbc.update("update admin_users set status='ACTIVE' where id=?",user.value());
        jdbc.update("update admin_sessions set revoked_at=? where id=?",Timestamp.from(INITIAL_TIME),session.value());denied(valid);
        jdbc.update("update admin_sessions set revoked_at=null,expires_at=? where id=?",Timestamp.from(INITIAL_TIME),session.value());denied(valid);
        jdbc.update("update admin_sessions set expires_at=?,absolute_expires_at=? where id=?",Timestamp.from(INITIAL_TIME.plusSeconds(100)),Timestamp.from(INITIAL_TIME),session.value());denied(valid);
    }
    @Test void newAuthenticationGainsEnabledFeatureButOldSessionDoesNot() {
        var legacy=sessions.issue(site,customer.id(),ORIGIN);enable();
        assertThat(sessions.resolveRequired(legacy.rawToken(),ORIGIN).scopes()).extracting(Enum::name).doesNotContain("SUBSCRIPTION_MANAGE");
        assertThat(sessions.issue(site,customer.id(),ORIGIN).scopes()).extracting(Enum::name).contains("SUBSCRIPTION_READ","SUBSCRIPTION_MANAGE");
    }
    @Test void currentSiteAndCustomerMustRemainActive() {
        enable();
        var current=sessions.resolveRequired(sessions.issue(site,customer.id(),ORIGIN).rawToken(),ORIGIN);
        var valid=context(new SubscriptionActor.Customer(current),ORIGIN);check(valid);
        jdbc.update("update sites set status='DISABLED' where id=?",site.value());denied(valid);
        jdbc.update("update sites set status='ACTIVE' where id=?",site.value());
        customers.save(customer.anonymize(INITIAL_TIME));denied(valid);
    }
}
