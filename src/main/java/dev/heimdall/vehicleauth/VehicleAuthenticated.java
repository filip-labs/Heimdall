package dev.heimdall.vehicleauth;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface VehicleAuthenticated {
}
