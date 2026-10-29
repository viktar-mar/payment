package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionConfirmationStore;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionRuleErrors;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository @Transactional(propagation=Propagation.MANDATORY)
public class SubscriptionConfirmationPersistenceAdapter implements SubscriptionConfirmationStore {
 private final JdbcTemplate jdbc;
 public SubscriptionConfirmationPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Optional<SubscriptionConfirmationChain> chain(UUID id){return jdbc.query("select * from subscription_confirmation_chains where id=? for update",(r,n)->chain(r),id).stream().findFirst();}
 public Optional<SubscriptionConfirmationChain> current(UUID operation){return jdbc.query("select * from subscription_confirmation_chains where operation_id=? for update",(r,n)->chain(r),operation).stream().findFirst();}
 public SubscriptionConfirmationAttempt attempt(UUID id){return jdbc.query("select * from subscription_confirmation_attempts where id=? for update",(r,n)->attempt(r),id).stream().findFirst().orElseThrow(SubscriptionRuleErrors::conflict);}
 public SubscriptionConfirmationAttempt latest(UUID chain){return jdbc.query("select * from subscription_confirmation_attempts where chain_id=? and successor is null order by issued_at desc for update",(r,n)->attempt(r),chain).stream().findFirst().orElseThrow(SubscriptionRuleErrors::conflict);}
 public void reserve(SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt initial){
  jdbc.update("insert into subscription_confirmation_chains(id,operation_id,root_digest,session_id,origin,expires_at,policy_version,subscription_revision) values (?,?,?,?,?,?,?,?)",root.id(),root.operation(),root.tokenDigest(),chain.sessionId(),chain.origin(),Timestamp.from(chain.expiresAt()),chain.policyVersion(),chain.subscriptionRevision());insert(initial);
 }
 public void insert(SubscriptionConfirmationAttempt a){jdbc.update("insert into subscription_confirmation_attempts(id,chain_id,predecessor,key_version,issued_at,expires_at,state,provider_key) values(?,?,?,?,?,?,?,?)",a.id(),a.chain(),a.predecessor(),a.keyVersion(),Timestamp.from(a.issuedAt()),Timestamp.from(a.expiresAt()),a.state().name(),a.providerKey());}
 public void save(SubscriptionConfirmationAttempt a,long previous,java.time.Instant at){
  var r=a.receipt();int changed=jdbc.update("update subscription_confirmation_attempts set claimed_at=case when state='READY' then ? else claimed_at end,state=?,confirm_key=?,token_digest=?,fingerprint=?,request_id=?,charge_reference=?,decline_code=?,successor=?,retry_key=?,version=? where id=? and version=?",Timestamp.from(at),a.state().name(),a.confirmKey(),a.tokenDigest(),a.fingerprint(),r==null?null:r.requestId(),r==null?null:r.charge(),r==null?null:r.declineCode(),a.successor(),a.retryKey(),a.version(),a.id(),previous);
  if(changed!=1)throw SubscriptionRuleErrors.conflict();
  if(a.state()==SubscriptionConfirmationAttempt.State.DISPATCHING)jdbc.update("update subscription_action_capabilities set consumed_at=? where id=? and consumed_at is null",Timestamp.from(at),a.chain());
 }
 private static SubscriptionConfirmationChain chain(ResultSet r)throws SQLException{return new SubscriptionConfirmationChain(r.getObject("id",UUID.class),r.getString("root_digest"),r.getObject("session_id",UUID.class),r.getString("origin"),r.getTimestamp("expires_at").toInstant(),r.getLong("policy_version"),r.getLong("subscription_revision"));}
 private static SubscriptionConfirmationAttempt attempt(ResultSet r)throws SQLException{return new SubscriptionConfirmationAttempt(r.getObject("id",UUID.class),r.getObject("chain_id",UUID.class),r.getObject("predecessor",UUID.class),r.getString("key_version"),r.getTimestamp("issued_at").toInstant(),r.getTimestamp("expires_at").toInstant(),SubscriptionConfirmationAttempt.State.valueOf(r.getString("state")),r.getString("confirm_key"),r.getString("token_digest"),r.getString("fingerprint"),r.getString("request_id")==null?null:new SubscriptionConfirmationAttempt.Receipt(r.getString("request_id"),r.getString("charge_reference"),r.getString("decline_code")),r.getObject("successor",UUID.class),r.getString("retry_key"),r.getLong("version"));}
}
