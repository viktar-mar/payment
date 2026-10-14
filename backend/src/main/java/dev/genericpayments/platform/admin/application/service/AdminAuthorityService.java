package dev.genericpayments.platform.admin.application.service;
import dev.genericpayments.platform.admin.application.port.in.*;
import dev.genericpayments.platform.admin.application.port.out.LockAdminAuthorityPort;
import dev.genericpayments.platform.admin.domain.AdminUser;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class AdminAuthorityService implements RevalidateAdminUseCase {
    private final LockAdminAuthorityPort locks;
    private final Clock clock;
    public AdminAuthorityService(LockAdminAuthorityPort locks,Clock clock){this.locks=locks;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void revalidate(AuthenticatedAdmin admin) {
        if(admin==null||admin.id()==null||admin.sessionId()==null)throw denied();
        var user=locks.lockUser(admin.id()).orElseThrow(AdminAuthorityService::denied);
        var session=locks.lockSession(admin.sessionId()).orElseThrow(AdminAuthorityService::denied);
        if(user.status()!=AdminUser.Status.ACTIVE||!user.username().equals(admin.username())
            ||!session.adminUserId().equals(user.id())||!session.isActiveAt(clock.instant())
            ||admin.expiresAt()==null||!clock.instant().isBefore(admin.expiresAt()))throw denied();
    }
    private static PaymentApplicationException denied(){return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");}
}
