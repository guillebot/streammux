package io.github.guillebot.streammux.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, EntraAuthProperties.class})
@ConditionalOnProperty(name = "streammux.auth.enabled", havingValue = "true")
public class SessionSecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(LocalUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setHideUserNotFoundExceptions(false);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain sessionSecurityFilterChain(
        HttpSecurity http,
        ObjectProvider<OidcUserService> oidcUserService,
        SecurityProperties props,
        UserRepository userRepository,
        AuthAuditService audit
    ) throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .securityMatcher("/**")
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers(request -> request.getRequestURI().startsWith("/api/config-studio/webhooks/")))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auths -> auths
                .requestMatchers(
                    "/login",
                    "/api/auth/**",
                    "/actuator/health",
                    "/actuator/info",
                    "/actuator/prometheus",
                    "/oauth2/**",
                    "/login/oauth2/**",
                    "/error",
                    "/v3/api-docs/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/api/config-studio/webhooks/**"
                ).permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .httpBasic(basic -> {})
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> {
                    if (auth != null) {
                        audit.record(auth.getName(), "LOGOUT", req.getRemoteAddr(), req.getHeader("User-Agent"), Map.of());
                    }
                    res.setStatus(HttpStatus.NO_CONTENT.value());
                }))
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((req, res, ex) -> res.setStatus(HttpStatus.UNAUTHORIZED.value()))
                .accessDeniedHandler((req, res, ex) -> res.setStatus(HttpStatus.FORBIDDEN.value())));

        if (props.localEnabled()) {
            http.formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .successHandler(jsonSuccessHandler(props))
                .failureHandler(jsonFailureHandler(userRepository, props, audit))
                .permitAll());
        }

        if (props.oidcEnabled()) {
            OidcUserService oidc = oidcUserService.getIfAvailable();
            if (oidc != null) {
                http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidc))
                    .defaultSuccessUrl("/", true));
            }
        }

        return http.build();
    }

    private static AuthenticationSuccessHandler jsonSuccessHandler(SecurityProperties props) {
        return (HttpServletRequest req, HttpServletResponse res, Authentication auth) -> {
            boolean remember = applyRememberMeOnSuccess(req, props);
            res.setStatus(HttpStatus.OK.value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"username\":\"" + auth.getName() + "\",\"rememberMe\":" + remember + "}");
        };
    }

    static boolean applyRememberMeOnSuccess(HttpServletRequest req, SecurityProperties props) {
        boolean remember = parseRememberMe(req.getParameter("remember-me"));
        HttpSession session = req.getSession(false);
        if (session != null) {
            long seconds = remember ? props.rememberMeDuration().toSeconds() : props.sessionTimeout().toSeconds();
            if (seconds > Integer.MAX_VALUE) {
                seconds = Integer.MAX_VALUE;
            }
            session.setMaxInactiveInterval((int) seconds);
        }
        if (remember) {
            req.setAttribute(SecurityProperties.REMEMBER_ME_REQUEST_ATTRIBUTE, true);
        }
        return remember;
    }

    static boolean parseRememberMe(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim().toLowerCase();
        return value.equals("true") || value.equals("on") || value.equals("yes") || value.equals("1");
    }

    private static AuthenticationFailureHandler jsonFailureHandler(
        UserRepository repo,
        SecurityProperties props,
        AuthAuditService audit
    ) {
        return (HttpServletRequest req, HttpServletResponse res, org.springframework.security.core.AuthenticationException ex) -> {
            String username = req.getParameter("username");
            if (username != null && !username.isBlank()) {
                repo.registerLoginFailure(username, props.lockoutThreshold(), props.lockoutDuration());
                audit.record(username, "LOGIN_FAILURE", req.getRemoteAddr(), req.getHeader("User-Agent"), Map.of("reason", ex.getClass().getSimpleName()));
            }
            res.setStatus(HttpStatus.UNAUTHORIZED.value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"error\":\"authentication_failed\"}");
        };
    }

    @Bean
    public org.springframework.context.ApplicationListener<AuthenticationSuccessEvent> authSuccessListener(
        UserRepository repo,
        AuthAuditService audit
    ) {
        return event -> {
            Authentication auth = event.getAuthentication();
            String name = auth.getName();
            if (name != null) {
                repo.registerLoginSuccess(name);
                ClientContext ctx = resolveClientContext(auth);
                audit.record(name, "LOGIN_SUCCESS", ctx.ip(), ctx.userAgent(), Map.of());
            }
        };
    }

    @Bean
    public org.springframework.context.ApplicationListener<AbstractAuthenticationFailureEvent> authFailureListener(
        AuthAuditService audit
    ) {
        return event -> {
            Authentication auth = event.getAuthentication();
            Object principal = auth.getPrincipal();
            String name = principal == null ? null : principal.toString();
            if (name != null) {
                ClientContext ctx = resolveClientContext(auth);
                audit.record(name, "LOGIN_FAILURE", ctx.ip(), ctx.userAgent(), Map.of("type", event.getClass().getSimpleName()));
            }
        };
    }

    static ClientContext resolveClientContext(Authentication auth) {
        String ip = null;
        Object details = auth == null ? null : auth.getDetails();
        if (details instanceof WebAuthenticationDetails wad) {
            ip = wad.getRemoteAddress();
        }
        String userAgent = null;
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletRequestAttributes) {
            HttpServletRequest req = servletRequestAttributes.getRequest();
            if (ip == null || ip.isBlank()) {
                ip = req.getRemoteAddr();
            }
            userAgent = req.getHeader("User-Agent");
        }
        if (ip != null && ip.isBlank()) {
            ip = null;
        }
        if (userAgent != null && userAgent.isBlank()) {
            userAgent = null;
        }
        return new ClientContext(ip, userAgent);
    }

    record ClientContext(String ip, String userAgent) {}
}
