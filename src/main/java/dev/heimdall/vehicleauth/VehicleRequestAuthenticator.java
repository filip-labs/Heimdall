package dev.heimdall.vehicleauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class VehicleRequestAuthenticator implements HandlerInterceptor {
    public static final String IDENTITY = "authenticatedVehicleId";
    private final VehicleTokenService tokens;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || !method.hasMethodAnnotation(VehicleAuthenticated.class)) {
            return true;
        }
        var headers = request.getHeaders("Authorization");
        if (!headers.hasMoreElements()) {
            throw new VehicleAuthenticationException();
        }
        String header = headers.nextElement();
        // Exactly one header, one space, one compact JWS. Scheme is case insensitive.
        if (headers.hasMoreElements()
                || !header.matches("(?i:Bearer) [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw new VehicleAuthenticationException();
        }
        request.setAttribute(IDENTITY, tokens.verify(header.substring(7)));
        return true;
    }
}
