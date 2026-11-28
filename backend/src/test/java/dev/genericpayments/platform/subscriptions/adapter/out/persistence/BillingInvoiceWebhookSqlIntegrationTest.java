package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BillingInvoiceWebhookSqlIntegrationTest extends PostgresIntegrationTest {
    @Autowired DataSource source;

    @Test void exactNativeSelectProvesEveryStageAndRefusesContradictoryRows() throws Exception {
        var dir=Path.of("../infrastructure");
        try (var connection=source.getConnection(); var sql=connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                sql.execute(Files.readString(dir.resolve("fixtures/stripe-billing-cancellation-proof-rows.sql")));
                sql.execute(Files.readString(dir.resolve("fixtures/stripe-billing-invoice-webhook-proof-rows.sql")));
                sql.execute("prepare invoice_webhook_proof as "+Files.readString(dir.resolve("stripe-billing-invoice-webhook-proof.sql")));
                sql.execute(Files.readString(dir.resolve("fixtures/stripe-billing-invoice-webhook-proof-stages.sql")));
            } finally {
                connection.rollback();
            }
        }
    }
}
