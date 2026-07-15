package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DefinitionJsonConfiguration {

    @Bean
    JsonMapper workDefinitionJsonMapper() {
        return JsonMapper.builder().build();
    }
}
