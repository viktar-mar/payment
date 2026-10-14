package dev.genericpayments.platform.donations.adapter.out.persistence;

import dev.genericpayments.platform.customers.application.port.out.CustomerDataRedactionPort;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.payments.application.port.in.CustomerDonationQuery;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Erases only Donation-owned material, atomically with the caller's Customer anonymization. */
@Repository
public class DonationCustomerDataRedactionAdapter implements CustomerDataRedactionPort {
    private final CustomerDonationQuery payments;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    public DonationCustomerDataRedactionAdapter(CustomerDonationQuery payments,JdbcTemplate jdbc,EntityManager entities) {
        this.payments=payments;this.jdbc=jdbc;this.entities=entities;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void redactCustomerData(SiteId siteId,CustomerId customerId,Instant now) {
        // Flush pending Customer/session changes before native SQL or detachment. Nothing commits here.
        entities.flush();
        var ids=payments.findDonationIds(siteId,customerId);
        if(ids.isEmpty())return;
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{
            var array=connection.createArrayOf("uuid",ids.stream().map(id->id.value()).toArray());
            try(var statement=connection.prepareStatement("update donations set message_state='REDACTED',message_ciphertext=null,message_nonce=null,message_key_version=null,updated_at=greatest(updated_at,?) where site_id=? and id=any(?) and message_state='PRESENT'")) {
                statement.setTimestamp(1,Timestamp.from(now));statement.setObject(2,siteId.value());statement.setArray(3,array);
                statement.executeUpdate();
            } finally {array.free();}
            return null;
        });
        // Native bulk SQL must not leave readable ciphertext in the first-level cache.
        // DonationStore refreshes under lock before writes, preventing stale message resurrection.
        entities.clear();
    }
}
