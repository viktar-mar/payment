package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.model.DonationPaymentViews.*;
import dev.genericpayments.platform.payments.application.port.out.DonationPaymentReadPort;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository @Transactional(readOnly=true)
public class DonationPaymentReadAdapter implements DonationPaymentReadPort {
    private static final String COLUMNS="donation_id,id,site_id,customer_id,donation_campaign_id,snapshot_campaign_key,snapshot_campaign_version,amount_minor,currency,status,created_at,updated_at";
    private final JdbcTemplate jdbc;
    public DonationPaymentReadAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Page list(Query q){
        var where=new StringBuilder(" where source='DONATION'");var args=new ArrayList<Object>();
        filter(where,args,"site_id",q.siteId());filter(where,args,"donation_campaign_id",q.campaignId());
        filter(where,args,"customer_id",q.customerId());filter(where,args,"donation_id",q.donationId());filter(where,args,"id",q.paymentId());
        filter(where,args,"status",q.status()==null?null:q.status().name());
        if(q.createdFrom()!=null){where.append(" and created_at>=?");args.add(Timestamp.from(q.createdFrom()));}
        if(q.createdTo()!=null){where.append(" and created_at<?");args.add(Timestamp.from(q.createdTo()));}
        long total=jdbc.queryForObject("select count(*) from payments"+where,Long.class,args.toArray());
        args.add(q.size());args.add((long)q.page()*q.size());
        return new Page(jdbc.query("select "+COLUMNS+" from payments"+where+" order by created_at desc,id desc limit ? offset ?",this::item,args.toArray()),total,q.page(),q.size());
    }
    public Optional<Item> find(UUID siteId,UUID donationId){
        var sql=new StringBuilder("select "+COLUMNS+" from payments where source='DONATION' and donation_id=?");
        var args=new ArrayList<Object>();args.add(donationId);filter(sql,args,"site_id",siteId);
        return jdbc.query(sql.toString(),this::item,args.toArray()).stream().findFirst();
    }
    public List<Item> history(SiteId site,UUID customer,Instant before,UUID beforeId,int limit){
        Objects.requireNonNull(site);Objects.requireNonNull(customer);
        if(limit<1||limit>101||(before==null)!=(beforeId==null))throw new IllegalArgumentException("Invalid history boundary");
        var sql=new StringBuilder("select "+COLUMNS+" from payments where source='DONATION' and site_id=? and customer_id=?");
        var args=new ArrayList<Object>(List.of(site.value(),customer));
        if(before!=null){sql.append(" and (created_at,id)<(?,?)");args.add(Timestamp.from(before));args.add(beforeId);}
        args.add(limit);return jdbc.query(sql+" order by created_at desc,id desc limit ?",this::item,args.toArray());
    }
    private Item item(ResultSet r,int row)throws SQLException{return new Item(r.getObject("donation_id",UUID.class),r.getObject("id",UUID.class),r.getObject("site_id",UUID.class),r.getObject("customer_id",UUID.class),r.getObject("donation_campaign_id",UUID.class),r.getString("snapshot_campaign_key"),r.getLong("snapshot_campaign_version"),r.getLong("amount_minor"),r.getString("currency"),PaymentStatus.valueOf(r.getString("status")),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}
    private static void filter(StringBuilder sql,List<Object> args,String column,Object value){if(value!=null){sql.append(" and ").append(column).append("=?");args.add(value);}}
}
