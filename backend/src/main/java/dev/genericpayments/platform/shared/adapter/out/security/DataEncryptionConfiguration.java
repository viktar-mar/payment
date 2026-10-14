package dev.genericpayments.platform.shared.adapter.out.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataEncryptionProperties.class)
public class DataEncryptionConfiguration {
    @Bean
    AesGcmEncryptedSecretAdapter encryptedSecretPort(DataEncryptionProperties properties) {
        return new AesGcmEncryptedSecretAdapter(properties);
    }
}
