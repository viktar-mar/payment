package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.port.in.CustomerDonationQuery;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.donations.domain.DonationId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class CustomerDonationQueryAdapter implements CustomerDonationQuery {
    private final JdbcTemplate jdbc;
    public CustomerDonationQueryAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<DonationId> findDonationIds(SiteId siteId,CustomerId customerId) {
        return jdbc.query("select donation_id from payments where site_id=? and customer_id=? and source='DONATION' and donation_id is not null order by donation_id",
            (row,index)->new DonationId(row.getObject("donation_id",UUID.class)),siteId.value(),customerId.value());
    }
}
