package dev.heimdall.config;

import dev.heimdall.vehicleauth.VehicleRequestAuthenticator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class VehicleAuthConfiguration implements WebMvcConfigurer {
    private final VehicleRequestAuthenticator authenticator;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticator);
    }
}
