package dev.genericpayments.platform.customers.adapter.out.mail;

import dev.genericpayments.platform.customers.adapter.in.configuration.CustomerEmailOtpProperties;
import dev.genericpayments.platform.customers.application.port.out.CustomerMailTemplatePort;
import java.time.Duration;

public final class ConfiguredCustomerMailTemplateAdapter implements CustomerMailTemplatePort {
    private final CustomerEmailOtpProperties properties;
    public ConfiguredCustomerMailTemplateAdapter(CustomerEmailOtpProperties properties){this.properties=properties;}
    @Override public boolean available(String templateKey){return properties.templates().containsKey(templateKey);}
    @Override public Rendered render(String templateKey,String code,Duration ttl) {
        var template=properties.templates().get(templateKey);
        if(template==null) throw new IllegalArgumentException("Unknown mail template");
        String minutes=Long.toString(Math.max(1,ttl.toMinutes()));
        return new Rendered(replace(template.subject(),code,minutes),replace(template.body(),code,minutes));
    }
    private static String replace(String value,String code,String minutes) {
        return value.replace("{{code}}",code).replace("{{ttl_minutes}}",minutes);
    }
}
