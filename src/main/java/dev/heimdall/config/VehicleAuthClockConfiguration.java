package dev.heimdall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class VehicleAuthClockConfiguration {
    @Bean
    public Clock vehicleAuthClock() {
        return Clock.systemUTC();
    }
}
