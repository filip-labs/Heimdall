package dev.heimdall.vehicleauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VehicleRequestAuthenticatorTest {
    static class Endpoint {
        @VehicleAuthenticated public void protectedOperation() { }
        public void administrativeRead() { }
    }

    @Test void rejectsWhitespaceAndAmbiguousHeadersBeforeTokenVerification() throws Exception {
        var tokens = mock(VehicleTokenService.class);
        var auth = new VehicleRequestAuthenticator(tokens);
        var handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("protectedOperation"));
        for (String header : new String[]{" Bearer a.b.c", "Bearer a.b.c ", "Bearer  a.b.c",
                "Bearer\ta.b.c", "Bearer a.b.c extra", "Basic a.b.c", "Bearer "}) {
            var request = new MockHttpServletRequest();
            request.addHeader("Authorization", header);
            assertThatThrownBy(() -> auth.preHandle(request, new MockHttpServletResponse(), handler))
                    .isInstanceOf(VehicleAuthenticationException.class)
                    .hasMessage("Vehicle authentication failed");
        }
        verifyNoInteractions(tokens);
    }

    @Test void acceptsUnambiguousCaseInsensitiveBearerAndLeavesAdminOperationsUnprotected() throws Exception {
        var tokens = mock(VehicleTokenService.class);
        UUID vehicle = UUID.randomUUID();
        when(tokens.verify("a.b.c")).thenReturn(vehicle);
        var auth = new VehicleRequestAuthenticator(tokens);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "bearer a.b.c");
        auth.preHandle(request, new MockHttpServletResponse(),
                new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("protectedOperation")));
        assertThat(request.getAttribute(VehicleRequestAuthenticator.IDENTITY)).isEqualTo(vehicle);
        assertThat(auth.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("administrativeRead")))).isTrue();
        verify(tokens, times(1)).verify("a.b.c");
    }
}
