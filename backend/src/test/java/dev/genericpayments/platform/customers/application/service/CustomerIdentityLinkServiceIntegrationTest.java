package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

import dev.genericpayments.platform.customers.application.model.*;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.partners.domain.SiteApiKeyId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import dev.genericpayments.platform.customers.adapter.out.persistence.CustomerPersistenceAdapter;

@Import(CustomerAuthenticationRotationIntegrationTest.RotationDigestConfiguration.class)
class CustomerIdentityLinkServiceIntegrationTest extends PostgresIntegrationTest {
    private static final String ORIGIN="https://shop.example.test";
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerStore customers;
    @Autowired CustomerIdentityStore identities;
    @Autowired IdentityLinkProofStore proofs;
    @Autowired IdentityLinkProofService proofIssuer;
    @Autowired KeyedDigestPort digests;
    @Autowired LinkCustomerIdentityUseCase links;
    @Autowired LinkPartnerCustomerIdentityUseCase partnerLinks;
    @Autowired IdentityProviderConfigurationStore providers;
    @Autowired CustomerAuthenticationRotationIntegrationTest.RotationDigestPort rotation;
    @Autowired CustomerTransactionPort transactions;
    @Autowired CustomerAuthenticationCompletionService authentication;
    @MockitoSpyBean CustomerPersistenceAdapter persistence;
    private SiteId site;
    private SiteId otherSite;
    private Customer customer;

    @Test void loginAndBrowserSelfLinkHaveCompatibleDatabaseLockOrder() throws Exception {
        assertLoginAndSelfLinkComplete(false);
    }

    @Test void loginAndPartnerSelfLinkHaveCompatibleDatabaseLockOrder() throws Exception {
        assertLoginAndSelfLinkComplete(true);
    }

    private void assertLoginAndSelfLinkComplete(boolean partner) throws Exception {
        String subject="self-link-lock-order";
        var existing=identity(customer,subject);
        String raw=issueProof(subject);
        var evidence=new VerifiedIdentityEvidence(subject,"site-provider",Optional.empty(),Optional.empty(),INITIAL_TIME,INITIAL_TIME.plusSeconds(300));
        var customerLocked=new CountDownLatch(1);
        var releaseLink=new CountDownLatch(1);
        var linkPid=new java.util.concurrent.atomic.AtomicInteger();
        var loginPid=new java.util.concurrent.atomic.AtomicInteger();
        CustomerPersistenceAdapter target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(persistence);
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            if(Thread.currentThread().getName().equals("self-link-lock-holder")) {
                customerLocked.countDown();
                if(!releaseLink.await(15,TimeUnit.SECONDS)) throw new AssertionError("Customer-lock gate was not released");
            }
            return result;
        }).when(target).findByIdForUpdate(site,customer.id());
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<Object> link=executor.submit(() -> {
                Thread.currentThread().setName("self-link-lock-holder");
                return transactions.required(() -> {
                    boundedTransaction(linkPid);
                    return partner?partnerLinks.link(partnerCommand(customer,subject,"self-link-lock-key")):
                        links.link(command(customer,site,ORIGIN,raw));
                });
            });
            assertThat(customerLocked.await(10,TimeUnit.SECONDS)).as("link holds the real Customer row lock").isTrue();
            Future<CustomerAuthenticationResult.AuthenticatedCustomer> login=executor.submit(() -> transactions.required(() -> {
                boundedTransaction(loginPid);
                return authentication.authenticate(site,CustomerAuthenticationMode.SITE_TOKEN,evidence,INITIAL_TIME,ORIGIN);
            }));
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(loginPid.get()).isPositive();
                assertThat(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",Boolean.class,linkPid.get(),loginPid.get()))
                    .as("login is blocked by the actual link transaction before releasing its Customer lock").isTrue();
            });
            releaseLink.countDown();
            Object linked=link.get(20,TimeUnit.SECONDS);
            if(partner) assertThat((LinkPartnerCustomerIdentityUseCase.Result)linked)
                .satisfies(result -> { assertThat(result.outcome()).isEqualTo(LinkPartnerCustomerIdentityUseCase.Outcome.ALREADY_LINKED); assertThat(result.identityId()).isEqualTo(existing.id()); });
            else assertThat((LinkCustomerIdentityUseCase.Result)linked)
                .satisfies(result -> { assertThat(result.outcome()).isEqualTo(LinkCustomerIdentityUseCase.Outcome.ALREADY_LINKED); assertThat(result.identityId()).isEqualTo(existing.id()); });
            assertThat(login.get(20,TimeUnit.SECONDS).customerId()).isEqualTo(customer.id());
            assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isOne();
            assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        } finally {
            releaseLink.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20,TimeUnit.SECONDS)).isTrue();
        }
    }

    private void boundedTransaction(java.util.concurrent.atomic.AtomicInteger pid) {
        jdbc.execute("set local lock_timeout='10s'");
        jdbc.execute("set local statement_timeout='15s'");
        pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
    }

    @BeforeEach void fixture() {
        clearDatabase();
        rotation.useV1();
        site=createSite("link-site"); otherSite=createSite("other-link-site");
        customer=createCustomer(site,false);
        providers.save(IdentityProviderConfiguration.create(site,"site-provider",
            new IdentityProviderConfiguration.SiteTokenConfiguration("https://issuer.example.test",Set.of("customer"),
                new JwksSource.Remote(URI.create("https://issuer.example.test/jwks")),Set.of("RS256"))));
    }

    @Test void unownedIdentityIsLinkedAndProofAndAuditContainNoRawMaterial() {
        String raw="A".repeat(43),subject="raw-subject-never-persist";
        proof(site,ORIGIN,raw,subject,INITIAL_TIME.plusSeconds(300),null);

        var result=links.link(command(customer,site,ORIGIN,raw));

        assertThat(result.outcome()).isEqualTo(LinkCustomerIdentityUseCase.Outcome.LINKED);
        assertThat(identities.list(site,customer.id())).extracting(CustomerIdentity::id).containsExactly(result.identityId());
        assertThat(jdbc.queryForObject("select consumed_at is not null from identity_link_proofs",Boolean.class)).isTrue();
        String persisted=jdbc.queryForList("select * from identity_link_proofs").toString()+jdbc.queryForList("select * from customer_identities");
        String audit=jdbc.queryForList("select * from audit_log").toString();
        assertThat(persisted).doesNotContain(raw,subject);
        assertThat(audit).contains("CUSTOMER_IDENTITY_LINKED","LINKED").doesNotContain(raw,subject,"same-email-digest");
    }

    @Test void sameOwnerIsIdempotentWithFreshProofButAConsumedProofIsUniformlyRejected() {
        String subject="same-owner-subject";
        String first="B".repeat(43); proof(site,ORIGIN,first,subject,INITIAL_TIME.plusSeconds(300),null);
        var linked=links.link(command(customer,site,ORIGIN,first));
        String second="C".repeat(43); proof(site,ORIGIN,second,subject,INITIAL_TIME.plusSeconds(300),null);

        var repeated=links.link(command(customer,site,ORIGIN,second));

        assertThat(repeated.outcome()).isEqualTo(LinkCustomerIdentityUseCase.Outcome.ALREADY_LINKED);
        assertThat(repeated.identityId()).isEqualTo(linked.identityId());
        assertThatThrownBy(() -> links.link(command(customer,site,ORIGIN,second))).isInstanceOfSatisfying(
            PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isEqualTo(2);
    }

    @Test void differentOwnerConflictsConsumesProofAndNeverMergesEqualEmailCustomers() {
        Customer owner=createCustomer(site,false);
        byte[] ciphertext=new byte[24],nonce=new byte[12];
        Arrays.fill(ciphertext,(byte)7); Arrays.fill(nonce,(byte)3);
        jdbc.update("update customers set email_ciphertext=?,email_nonce=?,email_key_version='test-v1',email_digest='same-email-digest',email_digest_key_version='v1' where id in (?,?)",
            ciphertext,nonce,customer.id().value(),owner.id().value());
        String subject="owned-subject";
        var existing=CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,owner.id(),
            CustomerAuthenticationMode.SITE_TOKEN,"site-provider",reference(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject),INITIAL_TIME);
        identities.create(existing,List.of(existing.subjectDigest()));
        String raw="D".repeat(43); proof(site,ORIGIN,raw,subject,INITIAL_TIME.plusSeconds(300),null);

        assertThatThrownBy(() -> links.link(command(customer,site,ORIGIN,raw))).isInstanceOfSatisfying(
            PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("IDENTITY_LINK_CONFLICT"));

        assertThat(jdbc.queryForObject("select consumed_at is not null from identity_link_proofs",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isEqualTo(2);
        assertThat(identities.list(site,owner.id())).hasSize(1);
        assertThat(identities.list(site,customer.id())).isEmpty();
        assertThat(jdbc.queryForList("select * from audit_log").toString()).contains("CONFLICT").doesNotContain(subject);
    }

    @Test void invalidProofAndInactiveTargetShareSafeFailureWithoutConsumptionOrAudit() {
        List<Runnable> invalid=new ArrayList<>();
        invalid.add(() -> links.link(command(customer,site,ORIGIN,"short")));
        invalid.add(() -> links.link(command(customer,site,ORIGIN,"Z".repeat(43))));
        String expired="E".repeat(43); proof(site,ORIGIN,expired,"expired",INITIAL_TIME,null);
        invalid.add(() -> links.link(command(customer,site,ORIGIN,expired)));
        String consumed="F".repeat(43); proof(site,ORIGIN,consumed,"consumed",INITIAL_TIME.plusSeconds(300),INITIAL_TIME);
        invalid.add(() -> links.link(command(customer,site,ORIGIN,consumed)));
        String wrongOrigin="G".repeat(43); proof(site,"https://other.example.test",wrongOrigin,"origin",INITIAL_TIME.plusSeconds(300),null);
        invalid.add(() -> links.link(command(customer,site,ORIGIN,wrongOrigin)));
        String wrongSite="H".repeat(43); proof(otherSite,ORIGIN,wrongSite,"site",INITIAL_TIME.plusSeconds(300),null);
        invalid.add(() -> links.link(command(customer,site,ORIGIN,wrongSite)));
        Customer anonymized=createCustomer(site,true);
        String inactive="I".repeat(43); proof(site,ORIGIN,inactive,"inactive",INITIAL_TIME.plusSeconds(300),null);
        invalid.add(() -> links.link(command(anonymized,site,ORIGIN,inactive)));

        for(Runnable action:invalid) assertThatThrownBy(action::run).isInstanceOfSatisfying(
            PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isZero();
    }

    @Test void concurrentConsumersCommitOneOutcomeAndOneConsumption() throws Exception {
        String raw="J".repeat(43); proof(site,ORIGIN,raw,"concurrent",INITIAL_TIME.plusSeconds(300),null);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            List<Future<Object>> calls=new ArrayList<>();
            for(int i=0;i<2;i++) calls.add(executor.submit(() -> { start.await(); try{return links.link(command(customer,site,ORIGIN,raw));}catch(RuntimeException e){return e;} }));
            start.countDown();
            List<Object> results=calls.stream().map(call -> {try{return call.get(10,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}).toList();
            assertThat(results.stream().filter(LinkCustomerIdentityUseCase.Result.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(PaymentApplicationException.class::isInstance).map(PaymentApplicationException.class::cast)
                .map(PaymentApplicationException::code)).containsExactly("AUTHENTICATION_FAILED");
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isOne();
    }

    @Test void retainedLinkProofDigestWorksDuringRotationAndRetiredDigestFailsUniformly() {
        String retained="K".repeat(43),retired="L".repeat(43);
        proof(site,ORIGIN,retained,"retained-proof-subject",INITIAL_TIME.plusSeconds(300),null);
        proof(site,ORIGIN,retired,"retired-proof-subject",INITIAL_TIME.plusSeconds(300),null);

        rotation.useV2RetainingV1();
        assertThat(links.link(command(customer,site,ORIGIN,retained)).outcome())
            .isEqualTo(LinkCustomerIdentityUseCase.Outcome.LINKED);
        rotation.useV2Only();
        assertThatThrownBy(() -> links.link(command(customer,site,ORIGIN,retired))).isInstanceOfSatisfying(
            PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED"));

        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs where consumed_at is not null",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isOne();
    }

    @Test void partnerLinkStoresAllRetainedSubjectAliasesAndSurvivesOldKeyRemoval() {
        String subject="partner-rotation-subject";
        rotation.useV2RetainingV1();
        var linked=partnerLinks.link(partnerCommand(customer,subject,"rotation-link"));

        assertThat(linked.outcome()).isEqualTo(LinkPartnerCustomerIdentityUseCase.Outcome.LINKED);
        assertThat(jdbc.queryForList("select subject_digest_key_version from customer_identity_subject_aliases order by subject_digest_key_version",String.class))
            .containsExactly("v1","v2");

        rotation.useV2Only();
        var repeated=partnerLinks.link(partnerCommand(customer,subject,"rotation-replay"));
        assertThat(repeated.identityId()).isEqualTo(linked.identityId());
        assertThat(repeated.outcome()).isEqualTo(LinkPartnerCustomerIdentityUseCase.Outcome.ALREADY_LINKED);
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isEqualTo(2);
    }

    @Test void exactPartnerReplaySurvivesActiveSubjectKeyRotationWithRetainedOldKey() {
        var exact=partnerCommand(customer,"partner-fingerprint-rotation","retained-fingerprint-key");
        var first=partnerLinks.link(exact);

        rotation.useV2RetainingV1();
        var replayed=partnerLinks.link(exact);

        assertThat(replayed).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_idempotency_records",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isOne();
    }

    @Test void completedPartnerSuccessReplayPrecedesDisabledProviderValidationWhileNewKeyRejects() {
        var exact=partnerCommand(customer,"disabled-provider-success","provider-success-key");
        var first=partnerLinks.link(exact);
        providers.save(providers.find(site,"site-provider").orElseThrow().disable());

        assertThat(partnerLinks.link(exact)).isEqualTo(first);
        assertThatThrownBy(() -> partnerLinks.link(withIdempotencyKey(exact,"provider-success-new-key")))
            .isInstanceOf(DomainValidationException.class);

        assertThat(jdbc.queryForObject("select count(*) from customer_idempotency_records",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isOne();
    }

    @Test void completedPartnerConflictReplayPrecedesDisabledProviderValidation() {
        String subject="disabled-provider-conflict";
        identity(createCustomer(site,false),subject);
        var exact=partnerCommand(customer,subject,"provider-conflict-key");
        assertIdentityConflict(exact);
        providers.save(providers.find(site,"site-provider").orElseThrow().disable());

        assertIdentityConflict(exact);

        assertThat(jdbc.queryForObject("select count(*) from customer_idempotency_records",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isOne();
    }

    @Test void completedPartnerSuccessReplayPrecedesAnonymizedCustomerValidationWhileNewKeyRejects() {
        var exact=partnerCommand(customer,"anonymized-customer-success","customer-success-key");
        var first=partnerLinks.link(exact);
        customer=customers.save(customer.anonymize(INITIAL_TIME.plusSeconds(1)));

        assertThat(partnerLinks.link(exact)).isEqualTo(first);
        assertThatThrownBy(() -> partnerLinks.link(withIdempotencyKey(exact,"customer-success-new-key")))
            .isInstanceOfSatisfying(PaymentApplicationException.class,
                error -> assertThat(error.code()).isEqualTo("CUSTOMER_NOT_AVAILABLE"));

        assertThat(jdbc.queryForObject("select count(*) from customer_idempotency_records",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isOne();
    }

    @Test void completedPartnerConflictReplayPrecedesAnonymizedCustomerValidation() {
        String subject="anonymized-customer-conflict";
        identity(createCustomer(site,false),subject);
        var exact=partnerCommand(customer,subject,"customer-conflict-key");
        assertIdentityConflict(exact);
        customer=customers.save(customer.anonymize(INITIAL_TIME.plusSeconds(1)));

        assertIdentityConflict(exact);

        assertThat(jdbc.queryForObject("select count(*) from customer_idempotency_records",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isOne();
    }

    @Test void browserAndPartnerPathsApplyTheSameOwnershipRules() {
        String subject="equivalent-ownership-subject";
        var partnerLinked=partnerLinks.link(partnerCommand(customer,subject,"equivalence-partner"));
        Customer other=createCustomer(site,false);
        String conflictProof="M".repeat(43);
        proof(site,ORIGIN,conflictProof,subject,INITIAL_TIME.plusSeconds(300),null);

        assertThatThrownBy(() -> links.link(command(other,site,ORIGIN,conflictProof))).isInstanceOfSatisfying(
            PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("IDENTITY_LINK_CONFLICT"));
        String sameOwnerProof="N".repeat(43);
        proof(site,ORIGIN,sameOwnerProof,subject,INITIAL_TIME.plusSeconds(300),null);
        var browserRepeated=links.link(command(customer,site,ORIGIN,sameOwnerProof));

        assertThat(browserRepeated.outcome()).isEqualTo(LinkCustomerIdentityUseCase.Outcome.ALREADY_LINKED);
        assertThat(browserRepeated.identityId()).isEqualTo(partnerLinked.identityId());
        assertThat(identities.list(site,other.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs where consumed_at is not null",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isEqualTo(3);
    }

    @Test void proofIssuedAfterSubjectRotationFindsV1OwnerAndBackfillsV2Alias() {
        String subject="browser-retained-owner";
        var existing=identity(customer,subject);
        rotation.useV2RetainingV1();

        String raw=issueProof(subject);
        var linked=links.link(command(customer,site,ORIGIN,raw));

        assertThat(linked.identityId()).isEqualTo(existing.id());
        assertThat(linked.outcome()).isEqualTo(LinkCustomerIdentityUseCase.Outcome.ALREADY_LINKED);
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(aliasVersions("customer_identity_subject_aliases")).containsExactly("v1","v2");
        assertThat(aliasVersions("identity_link_proof_subject_aliases")).containsExactly("v1","v2");
    }

    @Test void retainedProofAliasesCommitConflictAgainstV1OwnerInsteadOfDuplicatingUnderV2() {
        String subject="browser-retained-conflict";
        Customer owner=createCustomer(site,false);
        identity(owner,subject);
        rotation.useV2RetainingV1();

        String raw=issueProof(subject);
        assertThatThrownBy(() -> links.link(command(customer,site,ORIGIN,raw))).isInstanceOfSatisfying(
            PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("IDENTITY_LINK_CONFLICT"));

        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs where consumed_at is not null",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where details->>'outcome'='CONFLICT'",Integer.class)).isOne();
        assertThat(aliasVersions("identity_link_proof_subject_aliases")).containsExactly("v1","v2");
    }

    @Test void concurrentRotatedProofsCannotSplitAExistingV1IdentityAcrossCustomers() throws Exception {
        String subject="browser-retained-concurrent-conflict";
        Customer owner=createCustomer(site,false);
        identity(owner,subject);
        Customer first=createCustomer(site,false),second=createCustomer(site,false);
        rotation.useV2RetainingV1();
        String firstProof=issueProof(subject),secondProof=issueProof(subject);
        var start=new CountDownLatch(1);

        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var firstCall=executor.submit(() -> captureConflict(first,firstProof,start));
            var secondCall=executor.submit(() -> captureConflict(second,secondProof,start));
            start.countDown();
            assertThat(List.of(firstCall.get(10,TimeUnit.SECONDS),secondCall.get(10,TimeUnit.SECONDS)))
                .containsOnly("IDENTITY_LINK_CONFLICT");
        }

        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(distinct identity_id) from customer_identity_subject_aliases",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs where consumed_at is not null",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where details->>'outcome'='CONFLICT'",Integer.class)).isEqualTo(2);
    }

    @Test void issuedProofKeepsVerifiedSubjectAliasesAfterSubjectKeyRemovalButTokenLookupStillUsesRetainedProofKeys() {
        String persistedSubject="persisted-proof-aliases";
        Customer owner=createCustomer(site,false);
        identity(owner,persistedSubject);
        rotation.useV2RetainingV1();
        String v2Token=issueProof(persistedSubject);
        rotation.useV2Only();

        var repeated=links.link(command(owner,site,ORIGIN,v2Token));
        assertThat(repeated.outcome()).isEqualTo(LinkCustomerIdentityUseCase.Outcome.ALREADY_LINKED);
        assertThat(aliasVersions("identity_link_proof_subject_aliases")).containsExactly("v1","v2");

        rotation.useV1();
        String v1OnlyToken=issueProof("issuance-bounded-aliases");
        rotation.useV2RetainingV1();
        var linked=links.link(command(customer,site,ORIGIN,v1OnlyToken));
        assertThat(linked.outcome()).isEqualTo(LinkCustomerIdentityUseCase.Outcome.LINKED);
        assertThat(jdbc.queryForList("""
            select alias.subject_digest_key_version from identity_link_proof_subject_aliases alias
            join identity_link_proofs proof on proof.site_id=alias.site_id and proof.id=alias.proof_id
            where proof.token_digest_key_version='v1' order by alias.subject_digest_key_version
            """,String.class)).containsExactly("v1");

        rotation.useV1();
        String retiredToken=issueProof("retired-proof-key");
        rotation.useV2Only();
        assertThatThrownBy(() -> links.link(command(customer,site,ORIGIN,retiredToken))).isInstanceOfSatisfying(
            PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED"));
    }

    private LinkCustomerIdentityUseCase.Command command(Customer target,SiteId commandSite,String origin,String raw) {
        var session=new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),commandSite,target.id(),origin,
            Set.of(CustomerSessionScope.IDENTITY_LINK),INITIAL_TIME.plusSeconds(1800));
        return new LinkCustomerIdentityUseCase.Command(session,raw,"task-7-request","203.0.113.7");
    }
    private LinkPartnerCustomerIdentityUseCase.Command partnerCommand(Customer target,String subject,String idempotencyKey) {
        return new LinkPartnerCustomerIdentityUseCase.Command(site,new SiteApiKeyId(UUID.randomUUID()),target.id(),
            CustomerAuthenticationMode.SITE_TOKEN,"site-provider",subject,idempotencyKey,"task-7-partner-request","203.0.113.8");
    }
    private LinkPartnerCustomerIdentityUseCase.Command withIdempotencyKey(LinkPartnerCustomerIdentityUseCase.Command command,String key) {
        return new LinkPartnerCustomerIdentityUseCase.Command(command.siteId(),command.actorId(),command.customerId(),command.mode(),
            command.issuerKey(),command.subject(),key,command.requestId(),command.sourceIp());
    }
    private void assertIdentityConflict(LinkPartnerCustomerIdentityUseCase.Command command) {
        assertThatThrownBy(() -> partnerLinks.link(command)).isInstanceOfSatisfying(PaymentApplicationException.class,
            error -> assertThat(error.code()).isEqualTo("IDENTITY_LINK_CONFLICT"));
    }
    private String issueProof(String subject) {
        var evidence=new VerifiedIdentityEvidence(subject,"site-provider",Optional.empty(),Optional.empty(),INITIAL_TIME,INITIAL_TIME.plusSeconds(300));
        return ((CustomerAuthenticationResult.LinkProofIssued)proofIssuer.issue(
            site,ORIGIN,CustomerAuthenticationMode.SITE_TOKEN,evidence,INITIAL_TIME)).proof();
    }
    private CustomerIdentity identity(Customer owner,String subject) {
        String digest=reference(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject);
        return identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,owner.id(),
            CustomerAuthenticationMode.SITE_TOKEN,"site-provider",digest,INITIAL_TIME),List.of(digest));
    }
    private String captureConflict(Customer target,String raw,CountDownLatch start) throws InterruptedException {
        start.await();
        try { links.link(command(target,site,ORIGIN,raw)); return "unexpected-success"; }
        catch(PaymentApplicationException failure) { return failure.code(); }
    }
    private List<String> aliasVersions(String table) {
        return jdbc.queryForList("select subject_digest_key_version from "+table+" order by subject_digest_key_version",String.class);
    }
    private void proof(SiteId proofSite,String origin,String raw,String subject,Instant expiry,Instant consumed) {
        Instant created=expiry.isAfter(INITIAL_TIME)?INITIAL_TIME:INITIAL_TIME.minusSeconds(300);
        String subjectReference=reference(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject);
        proofs.save(new IdentityLinkProof(UUID.randomUUID(),proofSite,origin,CustomerAuthenticationMode.SITE_TOKEN,"site-provider",
            subjectReference,List.of(subjectReference),reference(KeyedDigestPort.Purpose.LINK_PROOF,raw),
            created,created,expiry,consumed,0));
    }
    private String reference(KeyedDigestPort.Purpose purpose,String value) {
        var digest=digests.digest(purpose,value); return digest.keyVersion()+":"+digest.value();
    }
    private Customer createCustomer(SiteId customerSite,boolean anonymized) {
        var id=new CustomerId(UUID.randomUUID());
        Customer value=Customer.create(id,customerSite,null,null,INITIAL_TIME);
        if(anonymized)value=value.anonymize(INITIAL_TIME);
        return customers.save(value);
    }
    private SiteId createSite(String key) {
        UUID account=UUID.randomUUID(); SiteId id=new SiteId(UUID.randomUUID());
        jdbc.update("insert into payment_accounts values (?,?,'FAKE','SANDBOX','ACTIVE','fake','ref',?,0)",account,key+"-account",java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into sites values (?,?,?,?,'ACTIVE','AUTOMATIC',?,?,0)",id.value(),key,key,account,java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",id.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'SITE_TOKEN')",id.value());
        return id;
    }
}
