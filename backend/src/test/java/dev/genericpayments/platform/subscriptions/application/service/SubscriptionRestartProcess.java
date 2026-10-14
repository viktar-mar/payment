package dev.genericpayments.platform.subscriptions.application.service;

import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import java.time.*;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
/** Test-owned JVM. Configuration arrives through stdin and is never logged by this harness. */
public final class SubscriptionRestartProcess {
    static Instant at;
    @TestConfiguration(proxyBeanMethods=false)
    static class Configuration {
        @Bean @Primary java.time.Clock restartClock(){return java.time.Clock.fixed(at,java.time.ZoneOffset.UTC);}
    }
    public static void main(String[] args)throws Exception {
        var input=new Properties();input.load(System.in);at=Instant.parse(input.getProperty("test.at"));
        input.forEach((k,v)->{if(!k.toString().startsWith("test."))System.setProperty(k.toString(),v.toString());});
        try(var app=new SpringApplicationBuilder(PaymentPlatformApplication.class,Configuration.class).run()) {
            var jdbc=app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            if(input.getProperty("test.mode").equals("produce-future")) {
                var id=UUID.fromString(input.getProperty("test.operation"));
                var tx=app.getBean(SubscriptionTransactionPort.class);var operations=app.getBean(SubscriptionOperationStore.class);
                var o=tx.required(()->operations.lock(id));var s=app.getBean(SubscriptionStore.class).find(o.siteId(),o.subscriptionId()).orElseThrow();
                var account=app.getBean(LoadPaymentAccountPort.class).findById(s.paymentAccountId()).orElseThrow();
                var provider=app.getBean(SubscriptionProviderRegistry.class).forAccount(account);
                provider.create(new Command(account,o,s.billingPrice(),null,null,null,null,true,CreateSubscriptionFingerprint.startsTrial(o.fingerprint())));
                // Lost receipt: durable local UNKNOWN retains no provider reference.
                app.getBean(SubscriptionCommandTransaction.class).recordReply(o.id(),new Reply(Outcome.UNKNOWN,null,null));
                var json=app.getBean(tools.jackson.databind.ObjectMapper.class);
                var period=new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(at.plusSeconds(60),at.plusSeconds(3600));
                var state=json.readValue(jdbc.queryForObject("select state::text from fake_subscription_objects where subscription_id=?",String.class,s.id()),dev.genericpayments.platform.provider.adapter.out.fake.FakeSubscriptionStateAdapter.State.class);
                state.period=period;
                jdbc.update("update fake_subscription_objects set state=?::jsonb where subscription_id=?",json.writeValueAsString(state),s.id());
                var invoice=json.readValue(jdbc.queryForObject("select invoice::text from fake_subscription_invoices where subscription_id=?",String.class,s.id()),Invoice.class);
                jdbc.update("update fake_subscription_invoices set invoice=?::jsonb where subscription_id=?",json.writeValueAsString(new Invoice(invoice.reference(),invoice.kind(),invoice.price(),invoice.previousPrice(),period,invoice.amountDueMinor(),invoice.currency(),invoice.settlement(),invoice.cashObjects())),s.id());
            } else {
                if(input.getProperty("test.mode").startsWith("resolve-")) {
                    var tx=app.getBean(SubscriptionTransactionPort.class);var operations=app.getBean(SubscriptionOperationStore.class);
                    var o=tx.required(()->operations.lock(UUID.fromString(input.getProperty("test.operation"))));
                    var s=app.getBean(SubscriptionStore.class).find(o.siteId(),o.subscriptionId()).orElseThrow();
                    var controls=app.getBean(FakeSubscriptionControlPort.class);boolean success=input.getProperty("test.mode").equals("resolve-success");
                    controls.scenario(s.paymentAccountId(),s.id(),success?FakeSubscriptionControlPort.Scenario.SUCCESS:FakeSubscriptionControlPort.Scenario.DECLINE);
                    controls.advance(s.paymentAccountId(),at);
                    if(success&&o.kind()==dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Kind.SETUP)controls.completeAction(s.paymentAccountId(),s.id());
                }
                app.getBean(SubscriptionSweepWorker.class).runOnce();
            }
            System.out.println("TEST_RESTART_COMPLETE:"+ProcessHandle.current().pid());
        }
    }
}
