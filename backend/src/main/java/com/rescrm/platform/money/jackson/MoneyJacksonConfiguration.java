package com.rescrm.platform.money.jackson;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link MoneyModule} with the application's object mapper so money and rates
 * serialize as strings everywhere, without any endpoint having to opt in.
 */
@Configuration
public class MoneyJacksonConfiguration {

    @Bean
    public MoneyModule moneyModule() {
        return new MoneyModule();
    }
}
