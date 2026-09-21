package com.rescrm.platform.tenancy;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wraps whatever {@link DataSource} the application ends up with, rather than defining one.
 *
 * <p>A bean post-processor is used so that Spring Boot still builds and configures the pool
 * from {@code spring.datasource.*}, and so that a test slice substituting its own DataSource
 * is wrapped too. Declaring a replacement DataSource bean here would silently disable Boot's
 * configuration and would be easy to bypass by accident.
 */
@Configuration
public class TenantDataSourceConfiguration {

    @Bean
    public static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource
                        && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
