package dev.genericpayments.platform.customers.adapter.in.configuration;

import dev.genericpayments.platform.customers.adapter.out.mail.SmtpCustomerMailAdapter;
import dev.genericpayments.platform.customers.adapter.out.mail.ConfiguredCustomerMailTemplateAdapter;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.application.service.EmailOtpProviderSelector;
import dev.genericpayments.platform.customers.adapter.out.security.SecureOtpCodeGeneratorAdapter;
import dev.genericpayments.platform.customers.application.port.out.OtpCodeGeneratorPort;
import dev.genericpayments.platform.customers.application.service.CustomerMailCapability;
import java.util.Arrays;
import org.springframework.boot.health.contributor.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(CustomerEmailOtpProperties.class)
public class CustomerEmailOtpConfiguration {
    @Bean OtpCodeGeneratorPort customerOtpCodeGenerator(){return new SecureOtpCodeGeneratorAdapter();}
    @Bean SmtpCustomerMailAdapter customerMailDeliveryPort(JavaMailSender sender,CustomerEmailOtpProperties properties){
        return new SmtpCustomerMailAdapter(sender,properties.smtp());
    }
    @Bean CustomerMailTemplatePort customerMailTemplatePort(CustomerEmailOtpProperties properties){
        return new ConfiguredCustomerMailTemplateAdapter(properties);
    }
    @Bean CustomerMailCapability customerMailCapability(CustomerEmailOtpProperties properties,Environment environment) {
        boolean development=Arrays.stream(environment.getActiveProfiles()).allMatch(profile -> profile.equals("local")||profile.equals("test"))
            &&environment.getActiveProfiles().length>0;
        return senderRef -> properties.smtp().configured(development)&&properties.smtp().senderRef().equals(senderRef);
    }
    @Bean(name="customerEmailOtpReadiness") HealthIndicator customerEmailOtpReadiness(JdbcTemplate jdbc,CustomerMailCapability capability,
            CustomerMailTemplatePort templates,IdentityProviderConfigurationStore providers,EmailOtpProviderSelector selector) {
        return () -> {
            var siteIds=jdbc.queryForList("""
                select site.id from sites site
                join site_authentication_modes mode on mode.site_id=site.id and mode.authentication_mode='EMAIL_OTP'
                where site.status='ACTIVE' order by site.id
                """,java.util.UUID.class);
            if(siteIds.isEmpty()) return Health.up().withDetail("capability","not-required").build();
            boolean ready=siteIds.stream().allMatch(id -> selector.select(providers.list(new dev.genericpayments.platform.sites.domain.SiteId(id)))
                .filter(provider -> {
                    var email=(dev.genericpayments.platform.customers.domain.IdentityProviderConfiguration.EmailOtpConfiguration)provider.configuration();
                    return capability.available(email.senderRef())&&templates.available(email.templateKey());
                }).isPresent());
            return ready
                ?Health.up().withDetail("capability","ready").build()
                :Health.down().withDetail("capability","configuration-failure").build();
        };
    }
}
