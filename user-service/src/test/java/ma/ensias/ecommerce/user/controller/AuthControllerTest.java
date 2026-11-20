package ma.ensias.ecommerce.user.controller;

import ma.ensias.ecommerce.user.dto.VerifiedIdentityResponse;
import ma.ensias.ecommerce.user.dto.VerifyCredentialsRequest;
import ma.ensias.ecommerce.user.entity.Role;
import ma.ensias.ecommerce.user.exception.AccountDisabledException;
import ma.ensias.ecommerce.user.exception.InvalidCredentialsException;
import ma.ensias.ecommerce.user.service.AuthenticationService;
import ma.ensias.ecommerce.user.security.WebSliceSecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(WebSliceSecurityTestConfig.class)
class AuthControllerTest {

    private static final String BODY = "{\"email\":\"hamza.belhaj@example.com\",\"password\":\"Demo@2026\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @Test
    void validCredentialsReturnTheIdentityWithoutAnySecret() throws Exception {
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000001");
        when(authenticationService.verify(any(VerifyCredentialsRequest.class))).thenReturn(
                new VerifiedIdentityResponse(id, "hamza.belhaj@example.com", "Hamza", "Belhaj",
                        Set.of(Role.CUSTOMER)));

        String content = mvc.perform(post("/internal/auth/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("hamza.belhaj@example.com"))
                .andExpect(jsonPath("$.firstName").value("Hamza"))
                .andExpect(jsonPath("$.lastName").value("Belhaj"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andReturn().getResponse().getContentAsString();

        assertThat(content.toLowerCase()).doesNotContain("password").doesNotContain("hash");
    }

    @Test
    void invalidCredentialsReturn401() throws Exception {
        when(authenticationService.verify(any(VerifyCredentialsRequest.class)))
                .thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/internal/auth/verify").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void disabledAccountReturns403() throws Exception {
        when(authenticationService.verify(any(VerifyCredentialsRequest.class)))
                .thenThrow(new AccountDisabledException());

        mvc.perform(post("/internal/auth/verify").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Account is disabled"));
    }

    @Test
    void missingPasswordReturns400() throws Exception {
        mvc.perform(post("/internal/auth/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"hamza.belhaj@example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPropertyReturns400() throws Exception {
        mvc.perform(post("/internal/auth/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"x\",\"username\":\"a\"}"))
                .andExpect(status().isBadRequest());
    }
}
