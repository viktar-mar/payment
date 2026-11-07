package dev.genericpayments.platform.provider.adapter.out.persistence;

import java.util.List;
import java.lang.reflect.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** No DataSource, context, network or provider calls. */
class OwnedMethodBrowserStoreTransactionTest {
    @Test void unextendedStoreCannotAuthorizePreSetupInspection() {
        var store=(OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},
            (proxy,method,args)->{if(!method.isDefault())throw new AssertionError("Unexpected ordinary store call");return InvocationHandler.invokeDefault(proxy,method,args);});
        assertThatThrownBy(()->store.requireBrowserSetupEnrollmentInTransaction(null,null,null))
            .isInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
    }
    @Test void unextendedStorePortsDenyEveryBrowserEntry() {
        var store=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},
            (proxy,method,args)->{if(!method.isDefault())throw new AssertionError("Unexpected ordinary store call");return InvocationHandler.invokeDefault(proxy,method,args);});
        var browser=List.of("reserveBrowserInTransaction","requireBrowserSetupEnrollmentInTransaction","requireBrowserPendingInTransaction","browserRootInTransaction","insertBrowserRootInTransaction",
            "requireBrowserActionInTransaction","startBrowserSelectionInTransaction","finishBrowserSelectionInTransaction","consumeBrowserFirstInTransaction");
        var methods=java.util.Arrays.stream(OwnedFailedRenewalMethodStore.class.getMethods()).filter(m->browser.contains(m.getName())).toList();
        assertThat(methods).hasSize(9);
        for(var method:methods)assertThatThrownBy(()->{try{method.invoke(store,new Object[method.getParameterCount()]);}catch(InvocationTargetException e){throw e.getCause();}})
            .isInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
    }
    @Test void everyBrowserEntryRequiresCallerTransactionBeforeSql() {
        var s=new JdbcOwnedFailedRenewalMethodStore(new JdbcTemplate());
        List<Runnable> calls=List.of(()->s.reserveBrowserInTransaction(null,null,null,null,null,null,null),
            ()->s.requireBrowserSetupEnrollmentInTransaction(null,null,null),()->s.requireBrowserPendingInTransaction(null,null,null),()->s.browserRootInTransaction(null),
            ()->s.insertBrowserRootInTransaction(null,null),()->s.requireBrowserActionInTransaction(null,null,null),
            ()->s.startBrowserSelectionInTransaction(null,null,null,null),()->s.finishBrowserSelectionInTransaction(null,null,null,null),
            ()->s.consumeBrowserFirstInTransaction(null,null,null,null,null));
        for(var call:calls)assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class).hasMessage("Owned method transition requires caller transaction");
    }
}
