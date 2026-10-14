package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Shared typed snapshot mapping; SQL identifiers are only application-owned constants. */
final class SubscriptionPersistenceValues {
    private SubscriptionPersistenceValues() {}
    static Timestamp time(Instant v){return v==null?null:Timestamp.from(v);}
    static Instant instant(ResultSet r,String key)throws SQLException {var t=r.getTimestamp(key);return t==null?null:t.toInstant();}
    static UUID uuid(ResultSet r,String key)throws SQLException{return r.getObject(key,UUID.class);}
    static Subscription.Period period(ResultSet r,String prefix)throws SQLException {var start=instant(r,prefix+"_start");return start==null?null:new Subscription.Period(start,instant(r,prefix+"_end"));}
    static void period(Map<String,Object> m,String prefix,Subscription.Period p){m.put(prefix+"_start",p==null?null:time(p.start()));m.put(prefix+"_end",p==null?null:time(p.end()));}
    static void terms(Map<String,Object> m,String p,SubscriptionPriceTerms t) {
        m.put(p+"_price_id",t==null?null:t.priceId().value());m.put(p+"_price_key",t==null?null:t.priceKey());
        m.put(p+"_price_version",t==null?null:t.version());m.put(p+"_amount_minor",t==null?null:t.regularAmount().minorUnits());
        m.put(p+"_interval",t==null?null:t.interval().name());m.put(p+"_trial_days",t==null?null:t.trialDays());
        m.put(p+"_trial_policy",t==null?null:t.trialPolicy().name());m.put(p+"_tax_mode",t==null?null:t.taxMode().name());
    }
    static SubscriptionPriceTerms terms(ResultSet r,String p)throws SQLException {
        UUID id=uuid(r,p+"_price_id");if(id==null)return null;
        return new SubscriptionPriceTerms(new PriceId(id),r.getString(p+"_price_key"),r.getInt(p+"_price_version"),
            new ProductId(uuid(r,"product_id")),Money.of(r.getLong(p+"_amount_minor"),r.getString("currency")),
            BillingInterval.valueOf(r.getString(p+"_interval")),r.getInt(p+"_trial_days"),TrialPaymentMethodPolicy.valueOf(r.getString(p+"_trial_policy")),TaxMode.valueOf(r.getString(p+"_tax_mode")));
    }
    static int insert(JdbcTemplate jdbc,String table,Map<String,Object> values,String suffix) {
        return new NamedParameterJdbcTemplate(jdbc).update("insert into "+table+" ("+String.join(",",values.keySet())+") values ("+values.keySet().stream().map(k->":"+k).collect(java.util.stream.Collectors.joining(","))+") "+suffix,values);
    }
    static int update(JdbcTemplate jdbc,String table,Map<String,Object> values,String where,Map<String,Object> keys) {
        String assignments=values.keySet().stream().map(k->k+"=:"+k).collect(java.util.stream.Collectors.joining(","));
        var params=new LinkedHashMap<>(values);params.putAll(keys);
        return new NamedParameterJdbcTemplate(jdbc).update("update "+table+" set "+assignments+" where "+where,params);
    }
}
