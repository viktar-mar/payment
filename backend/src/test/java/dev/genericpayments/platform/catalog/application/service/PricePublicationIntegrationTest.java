package dev.genericpayments.platform.catalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import dev.genericpayments.platform.catalog.application.port.in.PublishPriceUseCase;
import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.catalog.application.model.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.catalog.application.port.out.CatalogProviderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.TestPropertySource;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.*;

@TestPropertySource(properties = {"payment.admin.bootstrap.username=admin", "payment.admin.bootstrap.password=local-password"})
class PricePublicationIntegrationTest extends CheckoutTestFixture {
    @Autowired PublishPriceUseCase publication;
    @Autowired LoadPricePort loadPrices;
    @Autowired ProviderCatalogMappingPort mappings;
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean(name = "catalogProviderPort") CatalogProviderPort fakeCatalog;

    @Test void publicationCallsProviderOutsideDatabaseTransaction() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(fakeCatalog).ensureProduct(any(), any());
        var draft = catalogPrice("publish-probe", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        assertThat(publication.publish(draft.id()).status()).isEqualTo(PriceStatus.PUBLISHED);
    }

    @Test void adminHttpPublicationRunsProviderWithoutTransactionAndKeepsAuditContext() throws Exception {
        bootstrap.initialize();
        var api = new AdminApiClient(context);
        api.login();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(fakeCatalog).createPrice(any(), any(), any());
        var draft = catalogPrice("admin-publish", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        api.postJson("/api/admin/v1/prices/" + draft.id().value() + "/publish", "")
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        assertThat(jdbc.queryForMap("select actor_type,actor_id,resource_type,resource_id,request_id,source_ip,details->>'price_key' as price_key,details->>'version' as version from audit_log where action='PRICE_PUBLISHED'"))
            .containsEntry("actor_type", "ADMIN").containsEntry("resource_type", "price")
            .containsEntry("resource_id", draft.id().value().toString()).containsEntry("request_id", "catalog-test")
            .containsEntry("source_ip", "127.0.0.1").containsEntry("price_key", "admin-publish").containsEntry("version", "1")
            .containsEntry("actor_id", jdbc.queryForObject("select id::text from admin_users", String.class));
    }

    @Test void adminAuditFailureRollsBackPublicationAndBothMappings() throws Exception {
        bootstrap.initialize();
        var api = new AdminApiClient(context);
        api.login();
        var draft = catalogPrice("audit-fails", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        jdbc.execute("create function reject_price_publication_audit() returns trigger language plpgsql as $$ begin if new.action='PRICE_PUBLISHED' then raise exception 'injected audit failure' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger reject_price_publication_audit before insert on audit_log for each row execute function reject_price_publication_audit()");
        api.postJson("/api/admin/v1/prices/" + draft.id().value() + "/publish", "").andExpect(status().isConflict());
        assertThat(loadPrices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
        assertThat(mappings.findProduct(account.id(), product)).isEmpty();
        assertThat(mappings.findPrice(account.id(), draft.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='PRICE_PUBLISHED'", Integer.class)).isZero();
    }

    @Test void archivedProductCannotBePublished() {
        var draft = catalogPrice("archived-product", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        jdbc.update("update products set status='ARCHIVED' where id=?", product.value());
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOf(RuntimeException.class);
        assertThat(loadPrices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
        assertThat(count("provider_price_mappings")).isZero();
    }

    @Test void ambientCallerTransactionIsRejectedBeforeProviderWork() {
        var draft = catalogPrice("ambient", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> publication.publish(draft.id())))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(loadPrices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
        assertThat(count("provider_price_mappings")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"payment_accounts", "sites", "products", "prices"})
    void changedThenRestoredPersistenceVersionCannotPublishStaleSnapshot(String table) {
        var draft = catalogPrice("version-race", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        var id = switch (table) {
            case "payment_accounts" -> account.id().value();
            case "sites" -> site.value();
            case "products" -> product.value();
            default -> draft.id().value();
        };
        doAnswer(call -> {
            jdbc.update("update " + table + " set version=version+2 where id=?", id);
            return call.callRealMethod();
        }).when(fakeCatalog).ensureProduct(any(), any());
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOf(RuntimeException.class);
        assertThat(loadPrices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
        assertThat(count("provider_price_mappings")).isZero();
    }

    @Test void concurrentIdenticalCompletionsReturnSamePublishedPrice() throws Exception {
        var draft = catalogPrice("concurrent", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        var ready = new CyclicBarrier(2);
        doAnswer(call -> {
            ready.await(10, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(fakeCatalog).ensureProduct(any(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> publication.publish(draft.id()));
            var second = executor.submit(() -> publication.publish(draft.id()));
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(loadPrices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.PUBLISHED);
        assertThat(count("provider_product_mappings")).isEqualTo(1);
        assertThat(count("provider_price_mappings")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"sites", "payment_accounts"})
    void disabledSiteOrAccountCannotStartPublication(String table) {
        var draft = catalogPrice("disabled-before", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        disable(table);
        doAnswer(call -> { throw new AssertionError("provider must not run for disabled publication"); })
            .when(fakeCatalog).ensureProduct(any(), any());
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOf(RuntimeException.class);
        assertThat(count("provider_price_mappings")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"sites", "payment_accounts"})
    void disableDuringProviderWorkPreventsLocalPublication(String table) {
        var draft = catalogPrice("disabled-during", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        doAnswer(call -> { disable(table); return call.callRealMethod(); })
            .when(fakeCatalog).ensureProduct(any(), any());
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOf(RuntimeException.class);
        assertThat(loadPrices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
        assertThat(count("provider_product_mappings")).isZero();
        assertThat(count("provider_price_mappings")).isZero();
    }

    @Test void conflictingMappingCreatedDuringProviderWorkIsNeverOverwritten() {
        var draft = catalogPrice("mapping-conflict", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        doAnswer(call -> {
            mappings.save(new ProviderCatalogMapping(account.id(), product, new ProviderProductRef("existing_product"),
                draft.id(), new ProviderPriceRef("existing_price"), INITIAL_TIME));
            return call.callRealMethod();
        }).when(fakeCatalog).createPrice(any(), any(), any());
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOfSatisfying(PaymentApplicationException.class,
            e -> assertThat(e.code()).isEqualTo("PRICE_PUBLICATION_CONFLICT"));
        assertThat(loadPrices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
        assertThat(mappings.findProduct(account.id(), product).orElseThrow().value()).isEqualTo("existing_product");
        assertThat(mappings.findPrice(account.id(), draft.id()).orElseThrow().value()).isEqualTo("existing_price");
    }

    private void disable(String table) {
        if (table.equals("sites")) jdbc.update("update sites set status='DISABLED',version=version+1 where id=?", site.value());
        else jdbc.update("update payment_accounts set account_status='DISABLED',version=version+1 where id=?", account.id().value());
    }

    @AfterEach void removeAuditFailure() {
        jdbc.execute("drop trigger if exists reject_price_publication_audit on audit_log");
        jdbc.execute("drop function if exists reject_price_publication_audit()");
    }
}
