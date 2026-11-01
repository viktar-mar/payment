package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import com.stripe.net.*;
import com.stripe.exception.*;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.support.*;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort;
import dev.genericpayments.platform.webhooks.application.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

/** Hosted only: genuine original confirmation, both durable cash facts and signed callback delivery. */
@SpringBootTest(classes=PaymentPlatformApplication.class)
@Import(StripeBillingFirstRenewalCompositionFixture.RenewalTimeConfiguration.class)
class OwnedBillingFirstRenewalScenarioIntegrationTest extends OwnedBillingFirstPaymentScenarioFixture {
    private final Deque<String> renewalReplies=new ArrayDeque<>();
    private final List<String> delivered=new ArrayList<>();
    private long end,next;
    private String failure;
    private boolean sampledBeforeReady;
    private java.util.concurrent.CountDownLatch stageEntered,releaseStage;
    @Override protected OwnedBillingFirstPaymentRuntime.Operations createScenario(OwnedBillingFirstPaymentRuntime.Admission admission) {
        return new OwnedBillingFirstRenewalScenario(admission,this::runtimeBean,Clock.systemUTC(),Instant.now().plusSeconds(120));
    }
    @Override protected Object runtimeBean(Class<?> type) {
        if(type==dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.class){
            var delegate=application.getBean(dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.class);
            return (dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase)command->{refreshObservationClock();delegate.apply(command);};
        }
        return super.runtimeBean(type);
    }
    private OwnedBillingFirstRenewalScenario combined(){return (OwnedBillingFirstRenewalScenario)scenario;}
    @Override protected StripeResponse renewalResponse(StripeRequest request) throws StripeException {
        if(renewalReplies.isEmpty())return null;
        if(stageEntered!=null){
            stageEntered.countDown();
            try{if(!releaseStage.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("inert stage release timed out");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ApiConnectionException("inert interrupted");}
        }
        if(!sampledBeforeReady){
            sampledBeforeReady=true;
            assertThatThrownBy(()->application.getBean(dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase.class).get(createCommand.context().actor(),site,UUID.fromString(prepared.get("subscription_id").getAsString()))).isInstanceOf(RuntimeException.class);
        }
        if("lost_ack".equals(failure)&&request.url().getPath().endsWith("/advance")&&request.method().name().equals("POST"))throw new ApiConnectionException("inert lost acknowledgement");
        if("stale_fence".equals(failure)&&request.url().getPath().equals("/v1/subscriptions/sub_owned")){
            jdbc.update("update subscriptions set revision=revision+1 where id=?",UUID.fromString(prepared.get("subscription_id").getAsString()));failure=null;
        }
        return new StripeResponse(200,HttpHeaders.of(Map.of()),renewalReplies.removeFirst());
    }
    private void initial() throws Exception {
        // Outbox rows retain their configured endpoint at financial event creation.
        callbacks.configure(site.value(),"http://callback-receiver:8080/callback",true);
        prepare();noMoney();scenario.confirm(confirmRequest());noMoney();paidGraph();
        var settled=observe();assertThat(settled.get("phase").getAsString()).isEqualTo("SETTLED_LOCAL");
        end=Instant.parse(settled.get("paid_through").getAsString()).getEpochSecond();next=end+31*86400;
        deliver();verifyOwnedInitialSql();assertThat(count("payments")).isEqualTo(1);assertThat(posts()).isEqualTo(4);
    }
    private String clock(long frozen,String status){var c=JsonParser.parseString(clockJson(owner)).getAsJsonObject();c.addProperty("frozen_time",frozen);c.addProperty("status",status);return c.toString();}
    private void advanceReplies(boolean finalization){long before=finalization?end:FROZEN,target=finalization?end+3600:end;renewalReplies.add(clock(before,"ready"));renewalReplies.add(clock(before,"advancing"));renewalReplies.add(clock(target,"ready"));}
    private void graphReplies(boolean finalization,boolean paid) {
        long at=finalization?end+3600:end;
        var renewal=graph.invoice.deepCopy();renewal.addProperty("id","in_renewal");renewal.addProperty("billing_reason","subscription_cycle");renewal.addProperty("status",paid?"paid":"draft");renewal.addProperty("amount_paid",paid?2000:0);renewal.addProperty("amount_remaining",paid?0:2000);renewal.addProperty("amount_paid_off_stripe",0);
        var line=graph.line.deepCopy();line.addProperty("id","il_renewal");line.addProperty("invoice","in_renewal");line.addProperty("subscription","sub_owned");line.addProperty("subscription_item","si_owned");line.getAsJsonObject("period").addProperty("start",end);line.getAsJsonObject("period").addProperty("end",next);
        graph.item().addProperty("current_period_start",end);graph.item().addProperty("current_period_end",next);
        Collections.addAll(renewalReplies,clock(at,"ready"),customerJson("clock_owned"),graph.remote.toString(),StripeBillingOwnedSubscriptionFixture.page(graph.invoice,renewal),StripeBillingOwnedSubscriptionFixture.page(graph.line),StripeBillingOwnedSubscriptionFixture.page(graph.allocation),graph.pi.toString(),graph.charge.toString(),StripeBillingOwnedSubscriptionFixture.page(graph.allocation),StripeBillingOwnedSubscriptionFixture.page(line));
        if(paid){
            var allocation=graph.allocation.deepCopy();allocation.addProperty("id","inpay_renewal");allocation.addProperty("invoice","in_renewal");allocation.getAsJsonObject("payment").addProperty("payment_intent","pi_renewal");allocation.getAsJsonObject("status_transitions").addProperty("paid_at",end+11);
            var pi=graph.pi.deepCopy();pi.addProperty("id","pi_renewal");pi.addProperty("latest_charge","ch_renewal");var charge=graph.charge.deepCopy();charge.addProperty("id","ch_renewal");charge.addProperty("payment_intent","pi_renewal");
            Collections.addAll(renewalReplies,StripeBillingOwnedSubscriptionFixture.page(allocation),pi.toString(),charge.toString(),StripeBillingOwnedSubscriptionFixture.page(allocation));
        }else renewalReplies.add(StripeBillingOwnedSubscriptionFixture.page());
        renewalReplies.add(clock(at,"ready"));
    }
    private JsonObject stage(String path) throws Exception {
        refreshObservationClock();var x=new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange("POST",path,"{}",true);
        new OwnedBillingFirstRenewalRuntime.Handler(combined(),"q".repeat(43)).handle(x);assertThat(x.code).as(x.output.toString()).isEqualTo(200);return JsonParser.parseString(x.output.toString()).getAsJsonObject();
    }
    private void deliver() {
        var claims=outbox.claimAutomatic("combined",10,OwnedBillingFirstPaymentRuntimeTest.callbackClaimUtc(Instant.now()),Duration.ofSeconds(30));assertThat(claims).hasSize(2);
        for(var claim:claims)new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,bytes)->{
            assertThat(endpoint).as("combined signed callback receiver binding").isEqualTo("http://callback-receiver:8080/callback");
            long timestamp=Long.parseLong(headers.get("Payment-Timestamp"));assertThat(Instant.ofEpochSecond(timestamp)).isBetween(began,Instant.now());
            assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret())),timestamp,bytes));
            var json=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();assertThat(json.get("subscription_id").getAsString()).isEqualTo(prepared.get("subscription_id").getAsString());delivered.add(json.toString());return CallbackHttpPort.Result.success(204);
        },Clock.systemUTC()).deliver(claim);
    }
    @Test void genuineInitialAndExactlyOneRenewalPreserveOriginalLineageAndDeliverFourSignedCallbacks() throws Exception {
        publishCatalogOperations();initial();var original=jdbc.queryForList("select id,payload::text from outbox_events order by id");
        advanceReplies(false);graphReplies(false,false);var b=stage("/first-renewal/boundary");
        assertThat(b.keySet()).containsExactlyInAnyOrder("phase","code","run_id","account_id","site_id","customer_id","price_id","subscription_id","operation_id","renewal_admission_id","boundary_time","period_start","period_end");
        assertThat(b.get("phase").getAsString()).isEqualTo("RENEWAL_BOUNDARY");assertThat(b.get("boundary_time")).isEqualTo(b.get("period_start"));
        assertThat(count("payments")).isEqualTo(1);assertThat(jdbc.queryForList("select id,payload::text from outbox_events order by id")).isEqualTo(original);
        assertThat(jdbc.queryForObject("select paid_through from subscriptions",java.sql.Timestamp.class).toInstant()).isEqualTo(Instant.ofEpochSecond(end));
        advanceReplies(true);graphReplies(true,true);var done=stage("/first-renewal/finalization");
        assertThat(done.keySet()).containsExactlyInAnyOrder("phase","code","run_id","account_id","site_id","customer_id","price_id","subscription_id","operation_id","renewal_admission_id","publication_id","period_start","paid_through","access_evaluated_at","access_valid_until");
        assertThat(done.get("phase").getAsString()).isEqualTo("RENEWAL_SETTLED_LOCAL");assertThat(done.get("renewal_admission_id")).isEqualTo(b.get("renewal_admission_id"));assertThat(done.get("publication_id")).isEqualTo(done.get("renewal_admission_id"));
        assertThat(done.get("paid_through").getAsString()).isEqualTo(Instant.ofEpochSecond(next).toString());assertThat(done.get("access_evaluated_at").getAsString()).isEqualTo(Instant.ofEpochSecond(end+3600).toString());assertThat(done.get("access_valid_until").getAsString()).isEqualTo(Instant.ofEpochSecond(end+3900).toString());
        assertThat(jdbc.queryForList("select amount_minor from payments",Long.class)).containsExactlyInAnyOrder(2000L,2000L);assertThat(jdbc.queryForObject("select sum(amount_minor) from payments",Long.class)).isEqualTo(4000);
        assertThat(jdbc.queryForList("select provider_payment_reference from payments",String.class)).containsExactlyInAnyOrder("pi_owned","pi_renewal");
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);assertThat(count("stripe_billing_first_renewal_boundary")).isEqualTo(1);assertThat(count("stripe_billing_first_renewal_publications")).isEqualTo(1);
        assertThat(jdbc.queryForList("select target from stripe_billing_first_renewal_steps order by ordinal",Long.class)).containsExactly(end,end+3600);
        assertThat(count("payment_events")).isEqualTo(2);assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).containsExactly("PERIOD_PAID","PERIOD_PAID");
        assertThat(requests.stream().filter(r->r.method().name().equals("POST")&&r.url().getPath().endsWith("/advance")).map(r->Long.parseLong(r.params().get("frozen_time").toString())).toList()).containsExactly(end,end+3600);
        assertThat(posts()).isEqualTo(6);assertThat(renewalReplies).isEmpty();deliver();assertThat(delivered).hasSize(4);assertThat(new HashSet<>(delivered)).hasSize(4);verifyFinalSqlAndSignedUnion(b,done);assertThat(jdbc.queryForList("select status from outbox_events",String.class)).containsOnly("DELIVERED");
        assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(posts()).isEqualTo(6);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"lost_ack","stale_fence","authority_drift"})
    void failedBoundaryCannotFinalizeOrAdvanceAgain(String fault) throws Exception {
        initial();failure=fault;advanceReplies(false);graphReplies(false,false);
        if(fault.equals("authority_drift"))jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",billing.id().value());
        assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");var posts=posts();
        assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");
        assertThat(posts()).isEqualTo(posts);assertThat(posts).isLessThanOrEqualTo(5);assertThat(count("payments")).isEqualTo(1);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
    }
    @Test void concurrentFinalizationBurnsBoundaryBeforeAnyAdvancePostAndStopsDurableAuthority() throws Exception {
        initial();advanceReplies(false);graphReplies(false,false);stageEntered=new java.util.concurrent.CountDownLatch(1);releaseStage=new java.util.concurrent.CountDownLatch(1);
        var result=new java.util.concurrent.FutureTask<JsonObject>(combined()::boundary);Thread.ofVirtual().start(result);
        try{
            assertThat(stageEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");
        }finally{releaseStage.countDown();}
        assertThatThrownBy(()->result.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(combined().status().get("phase").getAsString()).isEqualTo("STOPPED");assertThat(posts()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select stopped_at is not null from stripe_billing_first_renewals",Boolean.class)).isTrue();
        assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(count("payments")).isEqualTo(1);
    }
    @Test void failedOriginalConfirmationCannotEnterRenewal() {
        prepare();outcome="disconnect";assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(posts()).isEqualTo(4);assertThat(count("stripe_billing_first_renewals")).isZero();noMoney();
    }
    @Test void pendingFinalizationConsumesAuthorityAndCannotThirdAdvance() throws Exception {
        initial();advanceReplies(false);graphReplies(false,false);stage("/first-renewal/boundary");advanceReplies(true);for(int i=0;i<3;i++)graphReplies(true,false);
        assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(posts()).isEqualTo(6);assertThat(count("payments")).isEqualTo(1);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
    }
    /** Read the shipped query on a real read-only JDBC transaction, without rewriting its predicates. */
    private JsonObject finalSql(java.sql.Connection connection, JsonObject boundary) throws Exception {
        var sql=java.nio.file.Files.readString(java.nio.file.Path.of("../infrastructure/stripe-billing-first-renewal-proof.sql")).replaceAll("\\$[0-9]+","?");
        var sha=java.security.MessageDigest.getInstance("SHA-256");
        Object[] args={billing.id().value(),site.value(),customer.value(),UUID.fromString(prepared.get("subscription_id").getAsString()),UUID.fromString(prepared.get("operation_id").getAsString()),UUID.fromString(prepared.getAsJsonObject("session").get("attempt_id").getAsString()),jdbc.queryForObject("select product_id from prices where id=?",UUID.class,createCommand.priceId().value()),createCommand.priceId().value(),HexFormat.of().formatHex(sha.digest("original-create".getBytes(java.nio.charset.StandardCharsets.UTF_8))),HexFormat.of().formatHex(sha.digest("sdk-generated-pay-key".getBytes(java.nio.charset.StandardCharsets.UTF_8))),"https://shop.test",run,"a".repeat(40),FROZEN,UUID.fromString(boundary.get("renewal_admission_id").getAsString())};
        try(var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
            try(var rows=statement.executeQuery()) {
                assertThat(rows.next()).as("one committed final SQL row").isTrue();var row=new JsonObject();var meta=rows.getMetaData();
                for(int i=1;i<=meta.getColumnCount();i++) {var name=meta.getColumnLabel(i);if(Set.of("initial","renewal","proof").contains(name))row.add(name,JsonParser.parseString(rows.getString(i)));else row.addProperty(name,rows.getString(i));}
                assertThat(rows.next()).isFalse();return row;
            }
        }
    }
    private void verifyFinalSqlAndSignedUnion(JsonObject boundary,JsonObject settlement) throws Exception {
        JsonObject row;
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try {row=finalSql(connection,boundary);connection.commit();}finally {connection.rollback();}
        }
        for(var key:List.of("owned","cash","original","journal","renewal","operations"))assertThat(row.getAsJsonObject("proof").get(key).getAsBoolean()).as("final SQL proof.%s",key).isTrue();
        var scope=new JsonObject();scope.addProperty("runId",run.toString());scope.addProperty("expectedSource","a".repeat(40));scope.addProperty("accountId",billing.id().value().toString());scope.addProperty("siteId",site.value().toString());scope.addProperty("customerId",customer.value().toString());scope.addProperty("priceId",createCommand.priceId().value().toString());scope.addProperty("productId",jdbc.queryForObject("select product_id::text from prices where id=?",String.class,createCommand.priceId().value()));
        scope.add("subscriptionId",prepared.get("subscription_id"));scope.add("operationId",prepared.get("operation_id"));
        var config=new JsonObject();config.addProperty("runId",run.toString());config.addProperty("expectedSource","a".repeat(40));config.addProperty("frozenTime",FROZEN);
        var input=new JsonObject();input.add("scope",scope);input.add("row",row);input.add("configuration",config);input.add("boundary",boundary);input.add("settlement",settlement);var received=new JsonArray();delivered.forEach(value->received.add(JsonParser.parseString(value)));input.add("delivered",received);
        // Hosted-only inert consumer: actual production validator, actual rows, signature-checked callback bytes.
        var builder=new ProcessBuilder("node","../infrastructure/fixtures/stripe-billing-first-renewal-row-consumer.mjs").redirectError(ProcessBuilder.Redirect.DISCARD);
        var path=System.getenv("PATH");builder.environment().clear();builder.environment().put("PATH",Objects.requireNonNull(path));builder.environment().put("LANG","C");builder.environment().put("LC_ALL","C");
        var bytes=input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);assertThat(bytes.length).isLessThanOrEqualTo(262144);
        OwnedBillingFirstRenewalEvidenceConsumer.verify(bytes,builder::start,Duration.ofSeconds(10));
        verifyOperationMutations(boundary);
    }
    private void verifyOperationMutations(JsonObject boundary) throws Exception {
        Map<String,List<String>> baseline;
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){baseline=financialEvidence(connection);}
        var postsBefore=posts();
        for(var mutation:List.of(
            "delete from provider_operations where kind='PRODUCT_CREATE'",
            "update provider_operations set resource_id='99999999-2222-4333-8444-555555555555' where kind='PRODUCT_CREATE'",
            "update provider_operations set provider_reference='prod_foreign' where kind='PRODUCT_CREATE'",
            "update provider_operations set operation_key='foreign_catalog_key' where kind='PRODUCT_CREATE'",
            "update provider_operations set status='UNKNOWN',provider_reference=null where kind='PRODUCT_CREATE'",
            "update provider_operations set recovery_attempts=1 where kind='PRODUCT_CREATE'",
            "update provider_operations set manual_review_required=true where kind='PRODUCT_CREATE'",
            "update provider_operations set external_account_id='acct_foreign' where kind='PRODUCT_CREATE'",
            "update provider_operations set credentials_ref='foreign-ref' where kind='PRODUCT_CREATE'",
            "insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,status,provider_reference,first_dispatched_at,retry_before) select '99999999-2222-4333-8444-555555555555',payment_account_id,external_account_id,credentials_ref,kind,'99999999-2222-4333-8444-555555555555','foreign_extra',fingerprint,'ACKNOWLEDGED','prod_extra',first_dispatched_at,retry_before from provider_operations where kind='PRODUCT_CREATE'",
            "insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,status,first_dispatched_at,retry_before) select '99999999-2222-4333-8444-555555555555',payment_account_id,external_account_id,credentials_ref,kind,'99999999-2222-4333-8444-555555555555','foreign_extra',fingerprint,'UNKNOWN',first_dispatched_at,retry_before from provider_operations where kind='PRODUCT_CREATE'")) {
            try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try(var statement=connection.createStatement()){statement.setQueryTimeout(5);assertThat(statement.executeUpdate(mutation)).isEqualTo(1);}
                    assertThat(finalSql(connection,boundary).getAsJsonObject("proof").get("operations").getAsBoolean()).as("mutated operation set must fail: %s",mutation).isFalse();
                    assertThat(financialEvidence(connection)).as("financial and immutable evidence unchanged inside mutation transaction").isEqualTo(baseline);
                }finally {connection.rollback();}
            }
            try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){assertThat(financialEvidence(connection)).as("financial and immutable evidence unchanged after rollback").isEqualTo(baseline);}
            assertThat(posts()).isEqualTo(postsBefore);
        }
    }
    private Map<String,List<String>> financialEvidence(java.sql.Connection connection) throws Exception {
        var result=new LinkedHashMap<String,List<String>>();
        // Fixed evidence relations only; deliberately excludes the negatively mutated operation table.
        for(var table:List.of("payments","subscription_billing_records","payment_events","subscription_events","subscriptions","outbox_events","outbox_deliveries","stripe_billing_clock_intents","stripe_billing_clock_ready","stripe_billing_owned_subscriptions","stripe_billing_owned_subscription_receipts","subscription_confirmation_chains","subscription_confirmation_attempts","stripe_billing_first_renewals","stripe_billing_first_renewal_publications","stripe_billing_first_renewal_steps","stripe_billing_first_renewal_acks","stripe_billing_first_renewal_ready","stripe_billing_first_renewal_read_claims","stripe_billing_first_renewal_boundary")) {
            var values=new ArrayList<String>();
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(5);
                try(var rows=statement.executeQuery("select row_to_json(e)::text from "+table+" e order by row_to_json(e)::text")){while(rows.next())values.add(rows.getString(1));}
            }
            result.put(table,values);
        }
        return result;
    }
    /** Catalog provider operations are real reservations/claims/ACKs; only SDK HTTP is inert. */
    private void publishCatalogOperations() {
        var price=application.getBean(dev.genericpayments.platform.catalog.application.port.out.LoadPricePort.class).findById(createCommand.priceId()).orElseThrow();
        var product=application.getBean(dev.genericpayments.platform.catalog.application.port.out.LoadProductPort.class).findById(price.productId()).orElseThrow();
        var mappings=application.getBean(dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort.class);
        var catalogRequests=new ArrayList<String>();
        var catalogClients=new dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory(){
            @Override public com.stripe.StripeClient create(dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentials ignored) {
                return com.stripe.StripeClient.builder().setApiKey("sk_test_INERT").setMaxNetworkRetries(0).setHttpClient(new HttpClient(){
                    @Override public StripeResponse request(StripeRequest request) {
                        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        assertThat(request.method().name()).isEqualTo("POST");var path=request.url().getPath();assertThat(path).isIn("/v1/products","/v1/prices");catalogRequests.add(path);
                        var key=path.equals("/v1/products")?"catalog_product_"+billing.id().value()+"_"+product.id().value():"catalog_price_"+billing.id().value()+"_"+price.id().value()+"_v1";
                        assertThat(request.headers().map().get("Idempotency-Key")).containsExactly(key);
                        assertThat(jdbc.queryForObject("select status from provider_operations where payment_account_id=? and operation_key=?",String.class,billing.id().value(),key)).isEqualTo("DISPATCHING");
                        var json=new JsonObject();json.addProperty("id",path.equals("/v1/products")?"prod_owned":"price_first");json.addProperty("object",path.equals("/v1/products")?"product":"price");json.addProperty("livemode",false);
                        return new StripeResponse(200,HttpHeaders.of(Map.of()),json.toString());
                    }
                }).build();
            }
        };
        var adapter=new dev.genericpayments.platform.provider.adapter.out.stripe.StripeCatalogProviderAdapter(credentials,catalogClients,operations,mappings,digest,verification);
        assertThat(adapter.ensureProduct(billing,product)).isEqualTo(mappings.findProduct(billing.id(),product.id()).orElseThrow());
        assertThat(adapter.createPrice(billing,product,price)).isEqualTo(mappings.findPrice(billing.id(),price.id()).orElseThrow());
        assertThat(catalogRequests).containsExactly("/v1/products","/v1/prices");
        assertThat(jdbc.queryForList("select status from provider_operations where kind in ('PRODUCT_CREATE','PRICE_CREATE')",String.class)).containsExactlyInAnyOrder("ACKNOWLEDGED","ACKNOWLEDGED");
    }
}
