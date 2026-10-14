package dev.genericpayments.platform.webhooks.adapter.out.persistence;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
@Repository
public class OutboxPersistenceAdapter implements OutboxPort {
    private final JdbcTemplate jdbc;private final Stage2TelemetryPort telemetry;
    private static final String FACTS="""
        select e.*,s.secret_version,s.ciphertext,s.nonce,s.master_key_version,st.site_key,a.provider
        from outbox_events e join site_callback_secret_versions s on s.id=e.secret_version_id
        join sites st on st.id=e.site_id join payment_accounts a on a.id=e.payment_account_id
        """;
    public OutboxPersistenceAdapter(JdbcTemplate jdbc,Stage2TelemetryPort telemetry){this.jdbc=jdbc;this.telemetry=telemetry;}
    private static Timestamp ts(Instant now){return Timestamp.from(now);}
    private void bounds(String owner,int batch,Instant now,Duration lease){if(owner==null||owner.isBlank()||owner.length()>128||owner.chars().anyMatch(Character::isISOControl)||batch<1||batch>100||now==null||lease==null||lease.compareTo(Duration.ofSeconds(1))<0||lease.compareTo(Duration.ofMinutes(10))>0)throw new IllegalArgumentException("Invalid claim");}
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW) public List<Claim> claimAutomatic(String owner,int batch,Instant now,Duration lease) {
        bounds(owner,batch,now,lease);
        var rows=jdbc.query(FACTS+" where e.status='PENDING' and e.next_attempt_at<=? and (e.lease_owner is null or e.lease_expires_at<=?) order by e.next_attempt_at,e.created_at,e.id limit ? for update of e skip locked",(r,n)->fact(r,null,owner,now,Mode.AUTOMATIC),ts(now),ts(now),batch);
        var claimed=new ArrayList<Claim>();
        for(var row:rows){telemetry.requireContext(row.context());
            if(row.attempt()>=6){jdbc.update("update outbox_events set status='DEAD',lease_owner=null,lease_expires_at=null,updated_at=?,version=version+1 where id=?",ts(now),row.eventId());after(()->{telemetry.outbox(row.context(),Stage2TelemetryPort.OutboxOutcome.DEAD);telemetry.outboxPending(row.context(),pending(row.siteId(),row.context().provider()));});continue;}
            jdbc.update("update outbox_events set attempt_count=attempt_count+1,lease_owner=?,lease_expires_at=?,updated_at=?,version=version+1 where id=?",owner,ts(now.plus(lease)),ts(now),row.eventId());
            claimed.add(withAttempt(row,row.attempt()+1));
            after(()->telemetry.outboxPending(row.context(),pending(row.siteId(),row.context().provider())));
        }return List.copyOf(claimed);
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW) public List<Claim> claimManual(String owner,int batch,Instant now,Duration lease) {
        bounds(owner,batch,now,lease);
        var requests=jdbc.queryForList("select * from outbox_retry_requests where status='PENDING' or (status='PROCESSING' and lease_expires_at<=?) order by created_at,id limit ? for update skip locked",ts(now),batch);
        var claimed=new ArrayList<Claim>();
        for(var req:requests){UUID event=(UUID)req.get("outbox_event_id"),id=(UUID)req.get("id");
            var row=jdbc.query(FACTS+" where e.id=? for update of e",(r,n)->fact(r,id,owner,now,Mode.valueOf((String)req.get("delivery_mode"))),event).getFirst();telemetry.requireContext(row.context());
            int attempt=row.attempt()+1;jdbc.update("update outbox_events set attempt_count=?,updated_at=?,version=version+1 where id=?",attempt,ts(now),event);
            jdbc.update("update outbox_retry_requests set status='PROCESSING',lease_owner=?,lease_expires_at=?,attempt_number=? where id=?",owner,ts(now.plus(lease)),attempt,id);
            var secret=jdbc.queryForMap("select * from site_callback_secret_versions where id=? and site_id=? and destroyed_at is null for update",req.get("secret_version_id"),row.siteId());
            claimed.add(new Claim(event,id,row.siteId(),(String)req.get("endpoint_url"),((Number)secret.get("secret_version")).intValue(),encrypted(secret),row.payload(),attempt,owner,now,row.createdAt(),row.mode(),row.context()));
        }return List.copyOf(claimed);
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW) public boolean complete(Claim c,CallbackHttpPort.Result result,Instant now) {
        telemetry.requireContext(c.context());
        if(now.isBefore(c.startedAt()))throw new IllegalArgumentException("Invalid completion time");
        if(c.requestId()==null){
            var live=jdbc.queryForList("select id from outbox_events where id=? and status='PENDING' and lease_owner=? and lease_expires_at>? and attempt_count=? for update",c.eventId(),c.owner(),ts(now),c.attempt());if(live.isEmpty())return false;
        }else{
            var live=jdbc.queryForList("select id from outbox_retry_requests where id=? and status='PROCESSING' and lease_owner=? and lease_expires_at>? and attempt_number=? for update",c.requestId(),c.owner(),ts(now),c.attempt());if(live.isEmpty())return false;
            jdbc.queryForList("select id from outbox_events where id=? for update",c.eventId());
        }
        jdbc.update("insert into outbox_deliveries(id,outbox_event_id,attempt_number,delivery_mode,status,http_status,error_category,started_at,finished_at) values (?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),c.eventId(),c.attempt(),c.mode().name(),result.succeeded()?"SUCCEEDED":"FAILED",result.httpStatus(),result.error()==null?null:result.error().name(),ts(c.startedAt()),ts(now));
        if(c.requestId()==null){
            String status=result.succeeded()?"DELIVERED":c.attempt()>=6?"DEAD":"PENDING";
            long delay=switch(c.attempt()){case 1->60;case 2->300;case 3->1800;case 4->7200;case 5->43200;default->0;};
            jdbc.update("update outbox_events set status=?,next_attempt_at=?,lease_owner=null,lease_expires_at=null,updated_at=?,version=version+1 where id=?",status,ts(now.plusSeconds(delay)),ts(now),c.eventId());
            after(()->{if(result.succeeded()){telemetry.outbox(c.context(),Stage2TelemetryPort.OutboxOutcome.DELIVERED);telemetry.outboxLag(c.context(),Duration.between(c.createdAt(),now));}else telemetry.outbox(c.context(),c.attempt()>=6?Stage2TelemetryPort.OutboxOutcome.DEAD:Stage2TelemetryPort.OutboxOutcome.RETRIED);telemetry.outboxPending(c.context(),pending(c.siteId(),c.context().provider()));});
        }else{
            jdbc.update("update outbox_retry_requests set status='PROCESSED',lease_owner=null,lease_expires_at=null,completed_at=?,result=? where id=?",ts(now),result.succeeded()?"SUCCEEDED":"FAILED",c.requestId());
            boolean transitioned=result.succeeded()&&jdbc.update("update outbox_events set status='DELIVERED',updated_at=?,version=version+1 where id=? and status='DEAD'",ts(now),c.eventId())==1;
            after(()->{if(result.succeeded()){telemetry.outbox(c.context(),Stage2TelemetryPort.OutboxOutcome.DELIVERED);if(transitioned)telemetry.outboxLag(c.context(),Duration.between(c.createdAt(),now));}telemetry.outboxPending(c.context(),pending(c.siteId(),c.context().provider()));});
        }return true;
    }
    @Override @Transactional(readOnly=true) public UUID siteFor(UUID event){return jdbc.query("select site_id from outbox_events where id=?",(r,n)->r.getObject(1,UUID.class),event).stream().findFirst().orElseThrow(()->new PaymentApplicationException("CALLBACK_DELIVERY_FAILED",404,"Callback event not found"));}
    @Override @Transactional public Retry enqueue(UUID event,Mode mode,Instant now) {
        if(mode==Mode.AUTOMATIC)throw new IllegalArgumentException("Invalid retry mode");
        var row=jdbc.query(FACTS+" where e.id=? for update of e",(r,n)->fact(r,null,"",now,mode),event).stream().findFirst().orElseThrow(()->new PaymentApplicationException("CALLBACK_DELIVERY_FAILED",404,"Callback event not found"));
        telemetry.requireContext(row.context());
        String state=jdbc.queryForObject("select status from outbox_events where id=?",String.class,event);
        if(state.equals("PENDING"))throw new PaymentApplicationException("PAYMENT_STATE_CONFLICT",409,"Automatic callback delivery unfinished");
        String endpoint=row.endpoint();UUID secret=jdbc.queryForObject("select secret_version_id from outbox_events where id=?",UUID.class,event);
        if(mode==Mode.CURRENT_CONFIG_RETRY){var configs=jdbc.queryForList("select endpoint_url,active_secret_version_id from site_callback_configs where site_id=? and enabled for share",row.siteId());if(configs.isEmpty())throw new PaymentApplicationException("CALLBACK_CONFIG_INVALID",409,"Enabled callback configuration required");endpoint=(String)configs.getFirst().get("endpoint_url");secret=(UUID)configs.getFirst().get("active_secret_version_id");}
        if(jdbc.queryForList("select id from site_callback_secret_versions where id=? and site_id=? and destroyed_at is null for update",secret,row.siteId()).isEmpty())throw new PaymentApplicationException("CALLBACK_CONFIG_INVALID",409,"Callback secret unavailable");
        UUID id=UUID.randomUUID();jdbc.update("insert into outbox_retry_requests(id,outbox_event_id,site_id,delivery_mode,endpoint_url,secret_version_id,created_at) values (?,?,?,?,?,?,?)",id,event,row.siteId(),mode.name(),endpoint,secret,ts(now));
        after(()->{telemetry.outbox(row.context(),Stage2TelemetryPort.OutboxOutcome.RETRIED);telemetry.outboxPending(row.context(),pending(row.siteId(),row.context().provider()));});return new Retry(id,event,row.siteId(),mode,"PENDING");
    }
    @Override @Transactional(readOnly=true) public Retry retry(UUID id){return jdbc.query("select * from outbox_retry_requests where id=?",(r,n)->new Retry(id,r.getObject("outbox_event_id",UUID.class),r.getObject("site_id",UUID.class),Mode.valueOf(r.getString("delivery_mode")),r.getString("status")),id).stream().findFirst().orElseThrow(()->new PaymentApplicationException("CALLBACK_DELIVERY_FAILED",404,"Callback retry not found"));}
    @Override @Transactional(readOnly=true) public long pending(UUID site){return jdbc.queryForObject("select (select count(*) from outbox_events where site_id=? and status='PENDING')+(select count(*) from outbox_retry_requests where site_id=? and status<>'PROCESSED')",Long.class,site,site);}
    @Override public boolean ambientTransaction(){return TransactionSynchronizationManager.isActualTransactionActive();}
    private long pending(UUID site,Stage2TelemetryPort.Provider provider){return jdbc.queryForObject("""
        select
          (select count(*) from outbox_events e join payment_accounts a on a.id=e.payment_account_id
            where e.site_id=? and a.provider=? and e.status='PENDING') +
          (select count(*) from outbox_retry_requests r join outbox_events e on e.id=r.outbox_event_id
            join payment_accounts a on a.id=e.payment_account_id
            where e.site_id=? and a.provider=? and r.status<>'PROCESSED')
        """,Long.class,site,provider.name(),site,provider.name());}
    private Claim fact(ResultSet r,UUID request,String owner,Instant now,Mode mode)throws SQLException {
        var encrypted=r.getBytes("ciphertext")==null?null:new EncryptedSecretPort.EncryptedValue(r.getBytes("ciphertext"),r.getBytes("nonce"),r.getString("master_key_version"));
        return new Claim(r.getObject("id",UUID.class),request,r.getObject("site_id",UUID.class),r.getString("endpoint_url"),r.getInt("secret_version"),encrypted,r.getString("payload"),r.getInt("attempt_count"),owner,now,r.getTimestamp("created_at").toInstant(),mode,new Stage2TelemetryPort.Context(r.getString("site_key"),Stage2TelemetryPort.Provider.valueOf(r.getString("provider"))));
    }
    private static EncryptedSecretPort.EncryptedValue encrypted(Map<String,Object> row){return new EncryptedSecretPort.EncryptedValue((byte[])row.get("ciphertext"),(byte[])row.get("nonce"),(String)row.get("master_key_version"));}
    private static Claim withAttempt(Claim c,int attempt){return new Claim(c.eventId(),c.requestId(),c.siteId(),c.endpoint(),c.secretVersion(),c.secret(),c.payload(),attempt,c.owner(),c.startedAt(),c.createdAt(),c.mode(),c.context());}
    private static void after(Runnable run){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){run.run();}});}
}
