package dev.matchline.spring.demo;

import dev.matchline.http.DemoSessions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Spring and legacy adapters deliberately reuse the same session policy. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpringDemoProperties.class)
public class SpringDemoConfiguration {
    @Bean(destroyMethod = "close")
    DemoSessions demoSessions(SpringDemoProperties properties) {
        properties.validate();
        return new DemoSessions();
    }
}
