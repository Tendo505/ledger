package io.github.tendo505.ledger.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time comes from an injectable clock, so tests can make holds expire without waiting.
 * It ticks in microseconds, the precision databases store, so a timestamp in a
 * response is identical to the same timestamp read back later.
 */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
