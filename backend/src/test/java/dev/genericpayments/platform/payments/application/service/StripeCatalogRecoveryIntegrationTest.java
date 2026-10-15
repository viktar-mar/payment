package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.application.port.in.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCatalogProviderAdapter;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.model.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class StripeCatalogRecoveryIntegrationTest extends StripeInboxFixture {
    @Autowired SaveProductPort products;
    @Autowired SavePricePort prices;
    @Autowired LoadPricePort loadPrices;
    @Autowired StripeCatalogProviderAdapter catalog;
    @Autowired StripeRecoveryService recovery;
    @Autowired ProviderOperationStore operations;
    @Autowired ProviderCatalogMappingPort mappings;
    @Autowired PublishPriceUseCase publication;
    @Autowired dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort pending;
    Product product(){return products.save(Product.create(new ProductId(UUID.randomUUID()),site,new ProductKey("recovery-product"),"Recovery Product",null,INITIAL_TIME));}
    void productAck(Product product){identity();http.enqueue("POST","/v1/products",200,"{\"id\":\"prod_recovery\",\"object\":\"product\",\"livemode\":false}");catalog.ensureProduct(currentAccount(),product);}
    ProviderOperation productOperation(Product product){return operations.find(account.id(),"catalog_product_"+account.id().value()+"_"+product.id().value()).orElseThrow();}
    @Test void catalogAcknowledgementsRequireVisibleAuditedAdminPublication() {
        var product=product();productAck(product);
        var price=prices.save(Price.draft(new PriceId(UUID.randomUUID()),site,product.id(),new PriceKey("recover-price"),1,PriceType.ONE_TIME,Money.of(2000,"EUR"),null,0,TaxMode.NONE,null,INITIAL_TIME));
        identity();http.enqueue("POST","/v1/prices",200,"{\"id\":\"price_recovery\",\"object\":\"price\",\"livemode\":false}");catalog.createPrice(currentAccount(),product,price);
        var priceOp=operations.find(account.id(),"catalog_price_"+account.id().value()+"_"+price.id().value()+"_v1").orElseThrow();
        recovery.recover(productOperation(product).id());recovery.recover(priceOp.id());
        assertThat(loadPrices.findById(price.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
        assertThat(mappings.findProduct(account.id(),product.id())).isEmpty();assertThat(mappings.findPrice(account.id(),price.id())).isEmpty();
        assertThat(jdbc.queryForList("select safe_error_code from provider_operations where kind in ('PRODUCT_CREATE','PRICE_CREATE')",String.class)).containsOnly("PUBLICATION_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='PRICE_PUBLISHED'",Integer.class)).isZero();
        int posts=(int)http.calls().stream().filter(c->c.method().equals("POST")).count();identity();identity();
        publication.publish(price.id(),new PricePublicationAudit(UUID.fromString("30000000-0000-0000-0000-000000000001"),"recovery-publication","127.0.0.1"));
        assertThat(loadPrices.findById(price.id()).orElseThrow().status()).isEqualTo(PriceStatus.PUBLISHED);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='PRICE_PUBLISHED'",Integer.class)).isEqualTo(1);
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(posts);
        jdbc.update("update provider_operations set next_attempt_at=clock_timestamp() where kind in ('PRODUCT_CREATE','PRICE_CREATE')");
        int calls=http.calls().size();recovery.recover(productOperation(product).id());recovery.recover(priceOp.id());
        assertThat(jdbc.queryForList("select manual_review_required from provider_operations where kind in ('PRODUCT_CREATE','PRICE_CREATE')",Boolean.class)).containsOnly(false);
        assertThat(http.calls()).hasSize(calls);assertThat(jdbc.queryForObject("select count(*) from audit_log where action='PRICE_PUBLISHED'",Integer.class)).isEqualTo(1);
    }
    record PublishedCatalog(Product product,Price price,UUID productOperation,UUID priceOperation) {}
    PublishedCatalog publishedCatalog() {
        var product=product();var price=prices.save(Price.draft(new PriceId(UUID.randomUUID()),site,product.id(),new PriceKey("completed-price"),1,PriceType.ONE_TIME,Money.of(2000,"EUR"),null,0,TaxMode.NONE,null,INITIAL_TIME));
        identity();http.enqueue("POST","/v1/products",200,"{\"id\":\"prod_complete\",\"object\":\"product\",\"livemode\":false}");identity();http.enqueue("POST","/v1/prices",200,"{\"id\":\"price_complete\",\"object\":\"price\",\"livemode\":false}");
        publication.publish(price.id(),new PricePublicationAudit(UUID.fromString("30000000-0000-0000-0000-000000000001"),"normal-publication","127.0.0.1"));
        return new PublishedCatalog(product,loadPrices.findById(price.id()).orElseThrow(),productOperation(product).id(),operations.find(account.id(),"catalog_price_"+account.id().value()+"_"+price.id().value()+"_v1").orElseThrow().id());
    }
    @Test void normallyPublishedCatalogAcknowledgementsAreNotSelectedOrFlagged() {
        var done=publishedCatalog();int calls=http.calls().size();
        assertThat(pending.claim(done.productOperation())).isEmpty();assertThat(pending.claim(done.priceOperation())).isEmpty();
        recovery.recover(done.productOperation());recovery.recover(done.priceOperation());
        assertThat(jdbc.queryForList("select manual_review_required from provider_operations where kind in ('PRODUCT_CREATE','PRICE_CREATE')",Boolean.class)).containsOnly(false);
        assertThat(http.calls()).hasSize(calls);
    }
    @Test void completedCatalogCleanupIsOneRowPerAcquisitionAndPreservesHistoryAfterArchivalAndRoutingChange() {
        var done=publishedCatalog();
        jdbc.update("update provider_operations set manual_review_required=true,safe_error_code=case when kind in ('PRODUCT_CREATE','PRICE_CREATE') then 'PUBLICATION_REQUIRED' else 'MANUAL_PROOF_REQUIRED' end,recovery_attempts=12");
        jdbc.update("update prices set status='ARCHIVED' where id=?",done.price().id().value());
        var other=UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'catalog-history-account','FAKE','SANDBOX','ACTIVE','other','other-ref',?,0)",other,java.sql.Timestamp.from(INITIAL_TIME));jdbc.update("update sites set payment_account_id=? where id=?",other,site.value());
        int calls=http.calls().size();assertThat(pending.claimNext()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind in ('PRODUCT_CREATE','PRICE_CREATE') and manual_review_required",Integer.class)).isEqualTo(1);
        assertThat(pending.claimNext()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind in ('PRODUCT_CREATE','PRICE_CREATE') and manual_review_required",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select recovery_attempts from provider_operations",Integer.class)).containsOnly(12);
        assertThat(operations.findById(done.productOperation()).orElseThrow().providerReference()).isEqualTo("prod_complete");assertThat(operations.findById(done.priceOperation()).orElseThrow().providerReference()).isEqualTo("price_complete");
        assertThat(http.calls()).hasSize(calls);assertThat(count("audit_log")).isEqualTo(1);assertThat(loadPrices.findById(done.price().id()).orElseThrow().status()).isEqualTo(PriceStatus.ARCHIVED);
    }
    @ParameterizedTest @ValueSource(strings={"wrong-product-reference","wrong-price-reference","wrong-version","fake","changed-account-binding"})
    void catalogCleanupRequiresExactCommittedOriginalPublication(String invalid) {
        var done=publishedCatalog();jdbc.update("update provider_operations set manual_review_required=true,safe_error_code='PUBLICATION_REQUIRED',recovery_attempts=12 where kind in ('PRODUCT_CREATE','PRICE_CREATE')");
        switch(invalid){case "wrong-product-reference"->jdbc.update("update provider_product_mappings set provider_product_id='prod_other'");case "wrong-price-reference"->jdbc.update("update provider_price_mappings set provider_price_id='price_other'");case "wrong-version"->jdbc.update("update provider_operations set operation_key=replace(operation_key,'_v1','_v2') where kind='PRICE_CREATE'");case "fake"->jdbc.update("update payment_accounts set provider='FAKE'");default->jdbc.update("update payment_accounts set external_account_id='acct_other'");}
        int calls=http.calls().size();recovery.recover(done.productOperation());recovery.recover(done.priceOperation());
        assertThat(jdbc.queryForList("select manual_review_required from provider_operations where kind in ('PRODUCT_CREATE','PRICE_CREATE')",Boolean.class)).containsOnly(true);
        assertThat(jdbc.queryForList("select recovery_attempts from provider_operations where kind in ('PRODUCT_CREATE','PRICE_CREATE')",Integer.class)).containsOnly(12);assertThat(http.calls()).hasSize(calls);
    }
    @Test void exactCatalogMappingsWithoutPublishedPriceDoNotCloseFollowUp() {
        var product=product();productAck(product);var price=prices.save(Price.draft(new PriceId(UUID.randomUUID()),site,product.id(),new PriceKey("mapped-draft"),1,PriceType.ONE_TIME,Money.of(2000,"EUR"),null,0,TaxMode.NONE,null,INITIAL_TIME));
        identity();http.enqueue("POST","/v1/prices",200,"{\"id\":\"price_draft\",\"object\":\"price\",\"livemode\":false}");catalog.createPrice(currentAccount(),product,price);
        mappings.save(new dev.genericpayments.platform.catalog.application.model.ProviderCatalogMapping(account.id(),product.id(),new dev.genericpayments.platform.catalog.application.model.ProviderProductRef("prod_recovery"),price.id(),new dev.genericpayments.platform.catalog.application.model.ProviderPriceRef("price_draft"),INITIAL_TIME));
        jdbc.update("update provider_operations set manual_review_required=true,safe_error_code='PUBLICATION_REQUIRED' where kind in ('PRODUCT_CREATE','PRICE_CREATE')");
        recovery.recover(productOperation(product).id());recovery.recover(operations.find(account.id(),"catalog_price_"+account.id().value()+"_"+price.id().value()+"_v1").orElseThrow().id());
        assertThat(jdbc.queryForList("select manual_review_required from provider_operations where kind in ('PRODUCT_CREATE','PRICE_CREATE')",Boolean.class)).containsOnly(true);
        assertThat(loadPrices.findById(price.id()).orElseThrow().publishedAt()).isNull();assertThat(count("audit_log")).isZero();
    }
    @Test void unknownCatalogReplayOnlyAcknowledgesOriginalReceiptWithoutPublishing() {
        var product=product();productAck(product);var op=productOperation(product);
        jdbc.update("update provider_operations set status='UNKNOWN',provider_reference=null where id=?",op.id());
        identity();http.enqueue("POST","/v1/products",200,"{\"id\":\"prod_recovery\",\"object\":\"product\",\"livemode\":false}");recovery.recover(op.id());
        assertThat(operations.findById(op.id()).orElseThrow().providerReference()).isEqualTo("prod_recovery");assertThat(mappings.findProduct(account.id(),product.id())).isEmpty();
        assertThat(http.calls().stream().filter(c->c.path().equals("/v1/products"))).hasSize(2).allSatisfy(c->assertThat(c.header("Idempotency-Key")).isEqualTo(op.operationKey()));
    }
    @Test void manualCatalogProofAuditsBindingButCannotPublishOrCreateMapping() {
        var product=product();productAck(product);var op=productOperation(product);
        jdbc.update("update provider_operations set status='UNKNOWN',provider_reference=null where id=?",op.id());
        identity();http.enqueue("GET","/v1/events/evt_productcreated",200,"{\"id\":\"evt_productcreated\",\"object\":\"event\",\"created\":"+(op.firstDispatchedAt().getEpochSecond()+1)+",\"type\":\"product.created\",\"livemode\":false,\"api_version\":\"2026-09-30.endive\",\"request\":{\"idempotency_key\":\""+op.operationKey()+"\"},\"data\":{\"object\":{\"id\":\"prod_recovery\",\"object\":\"product\"}}}");
        http.enqueue("GET","/v1/products/prod_recovery",200,"{\"id\":\"prod_recovery\",\"object\":\"product\",\"livemode\":false,\"name\":\"Recovery Product\",\"metadata\":{\"local_product_id\":\""+product.id().value()+"\"}}");
        var command=new RecoverProviderOperationCommand(op.id(),UUID.fromString("30000000-0000-0000-0000-000000000001"),"prod_recovery","evt_productcreated","manual-product","manual-product");
        recovery.recover(command);recovery.recover(command);
        assertThat(operations.findById(op.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(mappings.findProduct(account.id(),product.id())).isEmpty();assertThat(count("audit_log")).isEqualTo(1);assertThat(count("idempotency_records")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select safe_error_code from provider_operations where id=?",String.class,op.id())).isEqualTo("PUBLICATION_REQUIRED");
    }
    @ParameterizedTest @ValueSource(strings={"valid","wrong-amount","wrong-version","wrong-product"})
    void manualPriceProofRequiresOriginalProductAmountAndCatalogVersion(String variant) {
        var product=product();productAck(product);
        var price=prices.save(Price.draft(new PriceId(UUID.randomUUID()),site,product.id(),new PriceKey("manual-price"),3,PriceType.ONE_TIME,Money.of(2000,"EUR"),null,0,TaxMode.NONE,null,INITIAL_TIME));
        identity();http.enqueue("POST","/v1/prices",200,"{\"id\":\"price_manual\",\"object\":\"price\",\"livemode\":false}");catalog.createPrice(currentAccount(),product,price);
        var op=operations.find(account.id(),"catalog_price_"+account.id().value()+"_"+price.id().value()+"_v3").orElseThrow();
        jdbc.update("update provider_operations set status='UNKNOWN',provider_reference=null where id=?",op.id());
        identity();http.enqueue("GET","/v1/events/evt_pricecreated",200,"{\"id\":\"evt_pricecreated\",\"object\":\"event\",\"created\":"+(op.firstDispatchedAt().getEpochSecond()+1)+",\"type\":\"price.created\",\"livemode\":false,\"api_version\":\"2026-09-30.endive\",\"request\":{\"idempotency_key\":\""+op.operationKey()+"\"},\"data\":{\"object\":{\"id\":\"price_manual\",\"object\":\"price\"}}}");
        String remote="{\"id\":\"price_manual\",\"object\":\"price\",\"livemode\":false,\"product\":\"prod_recovery\",\"unit_amount\":2000,\"currency\":\"eur\",\"tax_behavior\":\"inclusive\",\"type\":\"one_time\",\"metadata\":{\"local_price_id\":\""+price.id().value()+"\",\"local_price_version\":\"3\"}}";
        remote=switch(variant){case "wrong-amount"->remote.replace("2000","1999");case "wrong-version"->remote.replace("\"3\"","\"4\"");case "wrong-product"->remote.replace("prod_recovery","prod_foreign");default->remote;};
        http.enqueue("GET","/v1/prices/price_manual",200,remote);
        var command=new RecoverProviderOperationCommand(op.id(),UUID.fromString("30000000-0000-0000-0000-000000000001"),"price_manual","evt_pricecreated","manual-price","manual-price");
        if(variant.equals("valid")){recovery.recover(command);assertThat(operations.findById(op.id()).orElseThrow().providerReference()).isEqualTo("price_manual");assertThat(count("audit_log")).isEqualTo(1);}
        else {assertThatThrownBy(()->recovery.recover(command)).isInstanceOf(RuntimeException.class);assertThat(operations.findById(op.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(count("audit_log")).isZero();assertThat(count("idempotency_records")).isZero();}
        assertThat(loadPrices.findById(price.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);assertThat(mappings.findPrice(account.id(),price.id())).isEmpty();
    }
}
