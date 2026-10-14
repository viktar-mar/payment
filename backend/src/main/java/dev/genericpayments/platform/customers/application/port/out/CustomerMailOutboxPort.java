package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.CustomerMailJob;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import java.time.*;
import java.util.*;

public interface CustomerMailOutboxPort {
    record Claim(CustomerMailJob job,EncryptedValue encryptedPayload) {
        public UUID id(){return job.id();}
        public String owner(){return job.leaseOwner();}
        public long generation(){return job.version();}
    }
    CustomerMailJob save(CustomerMailJob job,EncryptedValue encryptedPayload);
    List<Claim> claimReady(String owner,Instant now,int batchSize,Duration lease);
    boolean renewLease(Claim claim,Instant renewedAt,Instant leaseExpiresAt);
    boolean markSent(Claim claim,Instant now);
    boolean markFailure(Claim claim,CustomerMailJob.ErrorCategory category,Instant now,Instant nextAttemptAt);
    int destroyTerminalPayloads(Instant completedBefore,Instant now,int maximumRows);
    int deleteTerminalMetadata(Instant completedBefore,int maximumRows);
}
