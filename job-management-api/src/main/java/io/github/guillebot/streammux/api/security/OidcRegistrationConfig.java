/*
 * (c) Optimum 2026
 * Guillermo Schimmel
 */

package io.github.guillebot.streammux.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Registers the Azure Entra OIDC client only when {@code streammux.security.oidc-enabled=true},
 * so the rest of the API can boot without OIDC credentials configured.
 */
@Configuration
@ConditionalOnProperty(name = "streammux.security.oidc-enabled", havingValue = "true")
public class OidcRegistrationConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${OIDC_CLIENT_ID:}") String clientId,
            @Value("${OIDC_CLIENT_SECRET:}") String clientSecret,
            @Value("${OIDC_ISSUER_URI:}") String issuerUri,
            @Value("${OIDC_TENANT_ID:}") String tenantId,
            @Value("${streammux.ui.url:}") String uiUrl,
            @Value("${streammux.auth.entra.fetch-avatar:false}") boolean fetchAvatar) {

        String resolvedIssuer = resolveIssuerUri(issuerUri, tenantId);

        if (clientId == null || clientId.isBlank() || resolvedIssuer == null || resolvedIssuer.isBlank()) {
            throw new IllegalStateException(
                    "OIDC enabled but OIDC_CLIENT_ID or OIDC_ISSUER_URI/OIDC_TENANT_ID is missing");
        }

        ClientRegistration azure = ClientRegistrations.fromIssuerLocation(resolvedIssuer)
                .registrationId("azure")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(resolveRedirectUri(uiUrl))
                .scope(resolveScopes(fetchAvatar))
                .clientName("Azure Entra")
                // Entra tenants/users do not always emit preferred_username.
                // Using sub avoids callback-time crashes in DefaultOAuth2UserService;
                // our custom OidcUserService still derives persisted usernames with
                // preferred_username -> upn -> email -> sub fallback logic.
                .userNameAttributeName("sub")
                .build();

        return new InMemoryClientRegistrationRepository(azure);
    }

    /**
     * Resolve the OIDC issuer URI. An explicit {@code OIDC_ISSUER_URI} always
     * wins; otherwise derive the standard Entra v2.0 issuer from
     * {@code OIDC_TENANT_ID}. This lets deployment templates that only pass
     * {@code OIDC_TENANT_ID} work without also computing the issuer URL.
     */
    /**
     * OIDC redirect URI for the authorization-code callback. When
     * {@code STREAMMUX_UI_URL} is set (all deployed environments), pin the
     * public origin explicitly so a mis-set {@code X-Forwarded-Proto} behind
     * the UI nginx hop cannot produce {@code http://host:443/...} mismatches
     * against Entra app registration redirect URIs.
     */
    static String resolveRedirectUri(String uiUrl) {
        if (uiUrl != null && !uiUrl.isBlank()) {
            String base = uiUrl.trim().replaceAll("/+$", "");
            return base + "/login/oauth2/code/{registrationId}";
        }
        return "{baseUrl}/login/oauth2/code/{registrationId}";
    }

    /**
     * OIDC authorize scopes. When avatar fetch is enabled, append delegated
     * {@code User.Read} so the access token can call Graph {@code /me/photo/$value}.
     */
    static String[] resolveScopes(boolean fetchAvatar) {
        if (fetchAvatar) {
            return new String[]{"openid", "profile", "email", "User.Read"};
        }
        return new String[]{"openid", "profile", "email"};
    }

    static String resolveIssuerUri(String issuerUri, String tenantId) {
        if (issuerUri != null && !issuerUri.isBlank()) {
            return issuerUri.trim();
        }
        if (tenantId != null && !tenantId.isBlank()) {
            return "https://login.microsoftonline.com/" + tenantId.trim() + "/v2.0";
        }
        return null;
    }
}
