package com.example.testinglearning;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application's single source of "now" (see docs/testing-strategy.md §9). Code that needs the
 * current time takes a {@link Clock} and calls {@code Instant.now(clock)}, never plain
 * {@code Instant.now()}. A test can then pass {@code Clock.fixed(...)} and place "now" exactly
 * where it wants, e.g. one second before or after a deadline, with no sleeping and no flakiness.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
