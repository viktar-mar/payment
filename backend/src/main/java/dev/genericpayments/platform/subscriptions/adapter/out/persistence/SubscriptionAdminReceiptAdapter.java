package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAdminReceiptPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.*;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
@Repository
public class SubscriptionAdminReceiptAdapter implements SubscriptionAdminReceiptPort {
 private final JdbcTemplate jdbc;private final ObjectMapper mapper;
 public SubscriptionAdminReceiptAdapter(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
 @Transactional(propagation=Propagation.MANDATORY)
 public <T>T once(SiteId site,UUID admin,String kind,String key,String fingerprint,Class<T> type,Supplier<T> mutation){
   if(key==null||!key.matches("[A-Za-z0-9_.:/-]{1,255}"))throw new PaymentApplicationException("INVALID_REQUEST",400,"Invalid idempotency key");
   String digest=digest(key),fp=digest(fingerprint);
   var rows=jdbc.queryForList("select fingerprint,response from subscription_admin_receipts where site_id=? and admin_id=? and kind=? and key_digest=?",site.value(),admin,kind,digest);
   if(!rows.isEmpty()){var row=rows.getFirst();if(!fp.equals(row.get("fingerprint")))throw new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");return mapper.readValue((String)row.get("response"),type);}
   T result=mutation.get();String response=mapper.writeValueAsString(result);if(response.length()>32768)throw new IllegalStateException("Receipt exceeds bound");
   jdbc.update("insert into subscription_admin_receipts(site_id,admin_id,kind,key_digest,fingerprint,response) values (?,?,?,?,?,?)",site.value(),admin,kind,digest,fp,response);return result;
 }
 private static String digest(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
