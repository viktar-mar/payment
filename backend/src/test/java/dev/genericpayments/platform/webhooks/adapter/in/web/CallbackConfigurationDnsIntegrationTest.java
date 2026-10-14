package dev.genericpayments.platform.webhooks.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.webhooks.adapter.out.http.CallbackUrlPolicy;
import dev.genericpayments.platform.webhooks.application.port.in.ConfigureCallbackUseCase;
import java.net.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

@Import(CallbackConfigurationDnsIntegrationTest.ResolverConfiguration.class)
class CallbackConfigurationDnsIntegrationTest extends PartnerTestFixture {
    @Autowired WebApplicationContext context;@Autowired AdminBootstrap bootstrap;
    @Autowired ProbeResolver resolver;@Autowired ConfigureCallbackUseCase configure;
    AdminApiClient client;
    @BeforeEach void login()throws Exception{resolver.fail=false;resolver.calls=0;bootstrap.initialize();client=new AdminApiClient(context);client.login();}
    @Test void authenticatedConfigurationResolvesBeforeItsMutationAndAuditTransaction()throws Exception {
        request().andExpect(status().isOk()).andExpect(jsonPath("$.endpoint_url").value("https://callback.example/path"));
        assertThat(resolver.calls).isEqualTo(1);assertThat(count("site_callback_configs")).isEqualTo(1);assertThat(count("site_callback_secret_versions")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='SITE_CALLBACK_CONFIGURED'",Integer.class)).isEqualTo(1);
    }
    @Test void applicationConfigurationAlsoResolvesBeforeThePersistenceProxyOpensATransaction(){
        configure.configure(site.value(),"https://callback.example/path",true);
        assertThat(resolver.calls).isEqualTo(1);assertThat(count("site_callback_configs")).isEqualTo(1);
    }
    @Test void resolverFailureCreatesNoSecretConfigurationOrSuccessAudit()throws Exception {
        resolver.fail=true;request().andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CALLBACK_CONFIG_INVALID"));
        assertThat(resolver.calls).isEqualTo(1);assertThat(count("site_callback_configs")).isZero();assertThat(count("site_callback_secret_versions")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='SITE_CALLBACK_CONFIGURED'",Integer.class)).isZero();
    }
    private org.springframework.test.web.servlet.ResultActions request()throws Exception{return client.mvc.perform(put("/api/v1/admin/sites/"+site.value()+"/callback").cookie(client.session,client.xsrf).header("X-XSRF-TOKEN",client.xsrf.getValue()).contentType("application/json").content("{\"endpoint_url\":\"HTTPS://CALLBACK.EXAMPLE/path\",\"enabled\":true}"));}
    static final class ProbeResolver implements CallbackUrlPolicy.Resolver {
        volatile boolean fail;volatile int calls;
        @Override public List<InetAddress> resolve(String host)throws UnknownHostException {
            calls++;assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("DNS must precede configuration transaction").isFalse();
            if(fail)throw new UnknownHostException("opaque resolver failure");return List.of(InetAddress.getByName("8.8.8.8"));
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class ResolverConfiguration {
        @Bean ProbeResolver probeResolver(){return new ProbeResolver();}
        @Bean @Primary CallbackUrlPolicy probeCallbackUrlPolicy(ProbeResolver resolver){return new CallbackUrlPolicy(false,resolver);}
    }
}
