package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.ActorProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestActorResolverTest {

    @Mock
    private HttpServletRequest request;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void prefersRemoteUserWhenTrustedProxyHeadersEnabled() {
        RequestActorResolver resolver = new RequestActorResolver(new ActorProperties(true));
        when(request.getHeader("Remote-User")).thenReturn("jsolarin");

        assertEquals("jsolarin", resolver.resolve(request));
    }

    @Test
    void prefersRemoteEmailWhenRemoteUserMissing() {
        RequestActorResolver resolver = new RequestActorResolver(new ActorProperties(true));
        when(request.getHeader("Remote-User")).thenReturn(" ");
        when(request.getHeader("Remote-Email")).thenReturn("user@example.com");

        assertEquals("user@example.com", resolver.resolve(request));
    }

    @Test
    void ignoresProxyHeadersWhenTrustDisabledEvenIfPresent() {
        RequestActorResolver resolver = new RequestActorResolver(new ActorProperties(false));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "streammux",
            "secret",
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));

        assertEquals("streammux", resolver.resolve(request));
    }

    @Test
    void fallsBackToSecurityPrincipalWhenProxyHeadersDisabled() {
        RequestActorResolver resolver = new RequestActorResolver(new ActorProperties(false));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "streammux",
            "secret",
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));

        assertEquals("streammux", resolver.resolve(request));
    }
}
