package com.ctd.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.session.FlushMode;

import static org.assertj.core.api.Assertions.assertThat;

/** Bind the shipped properties using Boot's actual session configuration type. */
class RedisSessionConfigurationTests {
    @Test
    void productionPropertiesSelectIndexedSessionsAndApplicationNamespace() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(SessionDataRedisProperties.class);
                    assertThat(properties.getRepositoryType())
                            .isEqualTo(SessionDataRedisProperties.RepositoryType.INDEXED);
                    assertThat(properties.getNamespace()).isEqualTo("messenger:session");
                    assertThat(properties.getFlushMode()).isEqualTo(FlushMode.ON_SAVE);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @PropertySource("classpath:application.properties")
    @EnableConfigurationProperties(SessionDataRedisProperties.class)
    static class PropertiesConfiguration { }
}
