package dev.genericpayments.platform.subscriptions.adapter.in.web;
import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
/** Exact route-family admission; early mode retains no-store even for authentication/CSRF errors. */
public final class SubscriptionRequestPolicyFilter extends OncePerRequestFilter {
 private final ObjectMapper mapper;private final boolean early;
 private static final ObjectMapper STRICT=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 public SubscriptionRequestPolicyFilter(ObjectMapper mapper,boolean early){this.mapper=mapper;this.early=early;}
 public static boolean matches(String path){return path.matches("/api/v1/(customer|partner|admin)/(subscriptions(?:/.*)?|subscription-products/[^/]+/offers|customers/[^/]+/products/[^/]+/(subscription-offers|access)|sites/[^/]+/(subscription-policy|products/[^/]+/subscription-transitions|customers/[^/]+/products/[^/]+/subscription-offers))");}
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return !matches(r.getServletPath().isEmpty()?r.getRequestURI().substring(r.getContextPath().length()):r.getServletPath());}
 @Override protected String getAlreadyFilteredAttributeName(){return getClass().getName()+"."+early;}
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
  response.setHeader("Cache-Control","no-store");
  if(early){chain.doFilter(r,response);return;}
  try{
   String path=r.getRequestURI().substring(r.getContextPath().length());boolean admin=path.startsWith("/api/v1/admin/");
   for(String segment:path.substring("/api/v1/".length()).split("/"))if(segment.indexOf('-')>=0&&segment.matches("[0-9a-fA-F-]+"))AbstractSubscriptionController.requiredUuid(segment);
   if(!Set.of("GET","POST","OPTIONS").contains(r.getMethod()))throw invalid();
   for(String h:List.of("Idempotency-Key","Content-Type","Origin","Authorization","Content-Length","X-XSRF-TOKEN"))if(Collections.list(r.getHeaders(h)).size()>1)throw invalid();
   if(!admin&&(r.getCookies()!=null&&r.getCookies().length>0||r.getHeader("Cookie")!=null))throw invalid();
   if(path.startsWith("/api/v1/partner/")&&(r.getHeader("Origin")!=null||r.getHeader("Sec-Fetch-Site")!=null))throw invalid();
   if(admin&&r.getHeader("Authorization")!=null)throw invalid();
   Set<String> queries=new HashSet<>();
   boolean collection=path.matches("/api/v1/(customer|partner|admin)/subscriptions");
   boolean page=collection||path.endsWith("/events")||path.endsWith("/billing-records");
   if(page&&r.getMethod().equals("GET")){queries.addAll(Set.of("limit","cursor"));if(collection)queries.addAll(Set.of("product_id","state"));}
   if(admin&&path.startsWith("/api/v1/admin/subscriptions")&&!(collection&&r.getMethod().equals("POST")))queries.add("site_id");
   if(collection&&!path.startsWith("/api/v1/customer/")&&r.getMethod().equals("GET"))queries.add("customer_id");
   for(var e:r.getParameterMap().entrySet()){if(!queries.contains(e.getKey())||e.getValue().length!=1||e.getValue()[0].isEmpty()||e.getValue()[0].length()>(e.getKey().equals("cursor")?4096:255))throw invalid();if(e.getKey().endsWith("_id"))AbstractSubscriptionController.requiredUuid(e.getValue()[0]);}
   HttpServletRequest request=r;
   if(r.getMethod().equals("POST")){
    boolean preview=path.endsWith("/change-preview");String key=r.getHeader("Idempotency-Key");
    if(!preview&&(key==null||!key.matches("[A-Za-z0-9_.:/-]{1,255}"))||key!=null&&!key.matches("[A-Za-z0-9_.:/-]{1,255}"))throw invalid();
    if(r.getContentType()==null||!r.getContentType().toLowerCase(Locale.ROOT).matches("application/json(?:;.*)?"))throw invalid();
    byte[] bytes=r.getInputStream().readNBytes(8193);if(bytes.length>8192)throw new PaymentApplicationException("REQUEST_TOO_LARGE",413,"Subscription request too large");
    var node=STRICT.readTree(bytes);if(node==null||!node.isObject())throw invalid();
    var fields=allowed(path,collection,admin);
    for(var prop:node.properties()){
     String n=prop.getKey();var value=prop.getValue();if(!fields.contains(n))throw invalid();
     if(n.endsWith("_id")||n.equals("confirmation")){if(!value.isNull()){if(!value.isString())throw invalid();AbstractSubscriptionController.requiredUuid(value.asString());}}
     else if(Set.of("recurring_consent","enabled","allow_immediate_cancellation","portal_enabled","defer_upgrades","immediate_allowed").contains(n)){if(!value.isBoolean())throw invalid();}
     else if(Set.of("version","policy_version","grace_days").contains(n)){if(!value.isIntegralNumber())throw invalid();}
     else if(n.equals("portal_return_urls")){if(!value.isArray()||value.size()>100)throw invalid();for(var url:value)if(!url.isString()||url.asString().length()>2048)throw invalid();}
     else if(!value.isNull()&&!value.isString())throw invalid();
    }
    if(path.endsWith("/subscription-policy")||path.endsWith("/subscription-transitions"))for(String field:fields)if(!node.has(field))throw invalid();
    request=new Body(r,bytes);
   }else if(r.getContentLengthLong()>0)throw invalid();
   var bounded=new Response(response,page||path.endsWith("/offers")||path.endsWith("/subscription-offers")||path.endsWith("/subscription-transitions")?131072:32768);
   chain.doFilter(request,bounded);bounded.finish();
  }catch(PaymentApplicationException e){response.resetBuffer();ApiProblem.write(r,response,mapper,e.status(),e.code(),e.title());}
   catch(tools.jackson.core.JacksonException|IllegalArgumentException e){response.resetBuffer();ApiProblem.write(r,response,mapper,400,"INVALID_REQUEST","Invalid subscription request");}
 }
 private static Set<String> allowed(String path,boolean collection,boolean admin){
  if(collection){var s=new HashSet<>(Set.of("price_id","recurring_consent"));if(!path.startsWith("/api/v1/customer/"))s.add("customer_id");if(admin)s.add("site_id");return s;}
  if(path.endsWith("/change-preview"))return Set.of("target_price_id","timing");
  if(path.endsWith("/changes"))return Set.of("quote_id");
  if(path.endsWith("/cancellation"))return Set.of("mode","confirmation");
  if(path.endsWith("/method-setups")||path.endsWith("/resumptions"))return Set.of("recurring_consent");
  if(path.startsWith("/api/v1/customer/subscriptions/")&&path.contains("/confirmation-attempts/"))return path.endsWith("/confirm")?Set.of("capability","confirmation_token"):Set.of();
  if(path.startsWith("/api/v1/customer/subscriptions/")&&path.endsWith("/confirmation-session"))return Set.of("action_token");
  if(path.startsWith("/api/v1/customer/subscriptions/")&&path.endsWith("/client-session"))return Set.of("action_token");
  if(path.endsWith("/action-confirmations"))return Set.of("action_token");
  if(path.endsWith("/recoveries"))return Set.of("billing_record_id");
  if(path.endsWith("/portal-sessions"))return Set.of("return_url");
  if(path.endsWith("/subscription-policy"))return Set.of("version","enabled","grace_days","allow_immediate_cancellation","portal_enabled","defer_upgrades","portal_return_urls");
  if(path.endsWith("/subscription-transitions"))return Set.of("policy_version","from_price_id","to_price_id","direction","immediate_allowed");
  if(path.endsWith("/fake-controls"))return Set.of("action","scenario","until");
  return Set.of();
 }
 private static PaymentApplicationException invalid(){return new PaymentApplicationException("INVALID_REQUEST",400,"Invalid subscription request");}
 private static final class Body extends HttpServletRequestWrapper {
  private final byte[] bytes;Body(HttpServletRequest r,byte[] bytes){super(r);this.bytes=bytes;}
  @Override public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){throw new UnsupportedOperationException();}};}
  @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
  @Override public int getContentLength(){return bytes.length;}@Override public long getContentLengthLong(){return bytes.length;}
 }
 private static final class Response extends HttpServletResponseWrapper {
  private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private final int max;private PrintWriter writer;
  Response(HttpServletResponse r,int max){super(r);this.max=max;}
  private void put(int b){if(bytes.size()>=max)throw new PaymentApplicationException("RESPONSE_TOO_LARGE",413,"Subscription response too large");bytes.write(b);}
  @Override public ServletOutputStream getOutputStream(){return new ServletOutputStream(){public void write(int b){put(b);}public boolean isReady(){return true;}public void setWriteListener(WriteListener l){throw new UnsupportedOperationException();}};}
  @Override public PrintWriter getWriter(){if(writer==null)writer=new PrintWriter(new OutputStreamWriter(getOutputStream(),StandardCharsets.UTF_8));return writer;}
  @Override public void flushBuffer(){if(writer!=null)writer.flush();}
  @Override public void sendRedirect(String location){throw invalid();}
  void finish()throws IOException{flushBuffer();if(getStatus()>=300&&getStatus()<400)throw invalid();getResponse().getOutputStream().write(bytes.toByteArray());}
 }
}
