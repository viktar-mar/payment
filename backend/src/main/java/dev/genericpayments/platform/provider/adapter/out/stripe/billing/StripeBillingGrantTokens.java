package dev.genericpayments.platform.provider.adapter.out.stripe.billing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
/** Dedicated deterministic capabilities. Never uses provider or Customer identity credentials. */
public final class StripeBillingGrantTokens {
 private final boolean enabled;private final String active;private final Map<String,byte[]> keys;
 public StripeBillingGrantTokens(boolean enabled,String active,Map<String,String> supplied) {
  this.enabled=enabled;
  if(!enabled){this.active=null;this.keys=Map.of();return;}
  try{
   if(active==null||supplied==null||supplied.isEmpty()||supplied.size()>20||!supplied.containsKey(active))throw invalid();
   var decoded=new HashMap<String,byte[]>();var unique=new HashSet<String>();
   for(var e:supplied.entrySet()){
    if(!e.getKey().matches("v[1-9][0-9]{0,8}")||e.getValue()==null)throw invalid();
    var bytes=Base64.getDecoder().decode(e.getValue());if(bytes.length!=32||!unique.add(Base64.getEncoder().encodeToString(bytes)))throw invalid();decoded.put(e.getKey(),bytes.clone());
   }
   this.active=active;this.keys=Map.copyOf(decoded);
  }catch(RuntimeException bad){throw invalid();}
 }
 public static StripeBillingGrantTokens from(Environment env,boolean stripeEnabled){
  if(!stripeEnabled)return new StripeBillingGrantTokens(false,null,null);
  try{
   String enabled=property(env,"enabled","ENABLED","false");if(!Set.of("true","false").contains(enabled))throw invalid();
   if(!Boolean.parseBoolean(enabled))return new StripeBillingGrantTokens(false,null,null);
   var versions=property(env,"key-versions","KEY_VERSIONS","").split(",",-1);if(versions.length>20)throw invalid();
   Map<String,String> values=new HashMap<>();for(String v:versions){if(!v.matches("v[1-9][0-9]{0,8}")||values.containsKey(v))throw invalid();values.put(v,property(env,"keys."+v,"KEY_"+v.toUpperCase(Locale.ROOT),""));}
   return new StripeBillingGrantTokens(true,property(env,"active-key-version","ACTIVE_KEY_VERSION",""),values);
  }catch(RuntimeException bad){throw invalid();}
 }
 private static String property(Environment e,String p,String variable,String fallback){return e.getProperty("payment.stripe.billing-actions."+p,e.getProperty("PAYMENT_STRIPE_BILLING_ACTIONS_"+variable,fallback));}
 public boolean enabled(){return enabled;}
 public String activeVersion(){if(!enabled)throw unavailable();return active;}
 public String token(String version,String descriptor){
  if(!enabled||!keys.containsKey(version)||descriptor==null)throw unavailable();
  try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(keys.get(version),"HmacSHA256"));
   for(String value:List.of("gp:stripe-billing-action:v1",version,descriptor)){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());mac.update(bytes);}
   return "bga1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
  }catch(GeneralSecurityException bad){throw unavailable();}
 }
 public static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException bad){throw unavailable();}}
 public static boolean matches(String token,String hash){
  if(token==null||!token.matches("bga1_[A-Za-z0-9_-]{43}")||hash==null||!hash.matches("[0-9a-f]{64}"))return false;
  return MessageDigest.isEqual(HexFormat.of().parseHex(hash(token)),HexFormat.of().parseHex(hash));
 }
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid Stripe Billing action configuration");}
 private static RuntimeException unavailable(){return StripeBillingOwnership.unsupported();}
 @Override public String toString(){return "StripeBillingGrantTokens[redacted]";}
}
