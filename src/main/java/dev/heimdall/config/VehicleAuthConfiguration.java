package dev.heimdall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class VehicleAuthConfiguration {
    @Bean
    public Clock vehicleAuthClock() {
        return Clock.systemUTC();
    }
}
