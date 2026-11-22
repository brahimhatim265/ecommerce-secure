package ma.ensias.ecommerce.payment.security.mtls;

import ma.ensias.ecommerce.payment.security.ApiAccessDeniedHandler;
import ma.ensias.ecommerce.payment.security.ApiAuthenticationEntryPoint;
import ma.ensias.ecommerce.payment.security.ApiErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

/** 401 or 403 according to the reason recorded by the service rule, with the existing ApiError bodies. */
class ServiceRuleDeniedHandlerTest {

    private final ApiErrorWriter writer = new ApiErrorWriter(Jackson2ObjectMapperBuilder.json().build());
    private final ServiceRuleDeniedHandler handler =
            new ServiceRuleDeniedHandler(new ApiAuthenticationEntryPoint(writer), new ApiAccessDeniedHandler(writer));

    private static MockHttpServletRequest requestRefusedFor(DenialReason reason) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");
        request.setAttribute(DenialReason.REQUEST_ATTRIBUTE, reason);
        return request;
    }

    private static MockHttpServletRequest requestNotRefusedByTheServiceRule() {
        return new MockHttpServletRequest("GET", "/users");
    }

    // ---------------------------------------------------------------- anonymous caller (entry point)

    @Test
    void anAnonymousCallerWithoutCertificateGets401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(requestRefusedFor(DenialReason.MISSING_CERTIFICATE), response,
                new BadCredentialsException("ignored"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Authentication required");
    }

    @Test
    void anAnonymousCallerWhoseServiceIsNotAllowedGets403NotThe401SpringSecurityWouldGive() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(requestRefusedFor(DenialReason.SERVICE_NOT_ALLOWED), response,
                new BadCredentialsException("ignored"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Access denied");
    }

    @Test
    void anAnonymousCallerOfAnAllowedServiceWithoutTokenGets401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(requestRefusedFor(DenialReason.MISSING_TOKEN), response, new BadCredentialsException("ignored"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Authentication required");
    }

    @Test
    void aRefusalThatDoesNotComeFromTheServiceRuleStaysA401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(requestNotRefusedByTheServiceRule(), response, new BadCredentialsException("ignored"));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- caller with a token (access denied handler)

    @Test
    void aCallerWithATokenButWithoutCertificateGets401NotThe403SpringSecurityWouldGive() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(requestRefusedFor(DenialReason.MISSING_CERTIFICATE), response, new AccessDeniedException("ignored"));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void aCallerWithATokenWhoseServiceIsNotAllowedGets403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(requestRefusedFor(DenialReason.SERVICE_NOT_ALLOWED), response, new AccessDeniedException("ignored"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Access denied");
    }

    @Test
    void aRefusalThatDoesNotComeFromTheServiceRuleStaysA403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(requestNotRefusedByTheServiceRule(), response, new AccessDeniedException("ignored"));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void theBodyNeverTellsWhyOrWho() throws Exception {
        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        MockHttpServletResponse forbidden = new MockHttpServletResponse();

        handler.commence(requestRefusedFor(DenialReason.MISSING_CERTIFICATE), unauthorized, new BadCredentialsException("x"));
        handler.commence(requestRefusedFor(DenialReason.SERVICE_NOT_ALLOWED), forbidden, new BadCredentialsException("x"));

        assertThat(unauthorized.getContentAsString() + forbidden.getContentAsString())
                .doesNotContain("certificate", "missing_certificate", "service_not_allowed", "missing_token", "CN=", "rogue");
    }
}
