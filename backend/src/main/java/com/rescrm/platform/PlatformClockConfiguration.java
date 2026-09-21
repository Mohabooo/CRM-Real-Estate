package com.rescrm.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application's clock, as a bean.
 *
 * <p>Injected rather than called statically so that anything time-dependent — invitation
 * expiry above all — can be tested by advancing a fixed clock instead of sleeping. Doc 22
 * section 9 stores event timestamps in UTC, which is what this returns.
 */
@Configuration
public class PlatformClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
