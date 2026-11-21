package com.example.ajs.config;

import com.example.ajs.repository.AjsRepository;
import com.example.ajs.service.AjsObservabilityService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the read-only view service over the active repository implementation. */
@Configuration
public class AjsServiceConfig {

    @Bean
    public AjsObservabilityService ajsObservabilityService(AjsRepository repository) {
        return new AjsObservabilityService(repository);
    }
}
