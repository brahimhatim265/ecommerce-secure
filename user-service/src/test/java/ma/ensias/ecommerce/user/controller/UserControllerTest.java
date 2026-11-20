package ma.ensias.ecommerce.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.user.dto.CreateUserRequest;
import ma.ensias.ecommerce.user.dto.UpdateStatusRequest;
import ma.ensias.ecommerce.user.dto.UpdateUserRequest;
import ma.ensias.ecommerce.user.dto.UserResponse;
import ma.ensias.ecommerce.user.entity.Role;
import ma.ensias.ecommerce.user.exception.DuplicateResourceException;
import ma.ensias.ecommerce.user.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.user.service.UserService;
import ma.ensias.ecommerce.user.security.WebSliceSecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static ma.ensias.ecommerce.user.security.TestJwtSupport.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(WebSliceSecurityTestConfig.class)
class UserControllerTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    private static UserResponse response() {
        return new UserResponse(ID, "hamza.belhaj@example.com", "Hamza", "Belhaj", "+212 600 11 22 01",
                "45 Rue Ibnou Katir, Maârif", "Casablanca", Set.of(Role.CUSTOMER), true,
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-01T10:00:00Z"));
    }

    private static Map<String, Object> validRegistration() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", "hamza.belhaj@example.com");
        body.put("password", "Secret123");
        body.put("firstName", "Hamza");
        body.put("lastName", "Belhaj");
        body.put("phone", "+212 600 11 22 01");
        body.put("address", "45 Rue Ibnou Katir, Maârif");
        body.put("city", "Casablanca");
        return body;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    void createReturns201WithLocationAndNeverExposesPasswordData() throws Exception {
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(response());

        String content = mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRegistration())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/users/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andReturn().getResponse().getContentAsString();

        assertThat(content.toLowerCase()).doesNotContain("password").doesNotContain("hash");
    }

    static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of("email", ""),
                Arguments.of("email", "not-an-email"),
                Arguments.of("email", "a".repeat(115) + "@x.com"),
                Arguments.of("password", ""),
                Arguments.of("password", "short1"),
                Arguments.of("password", "onlyletters"),
                Arguments.of("password", "123456789"),
                Arguments.of("password", "a1" + "b".repeat(80)),
                Arguments.of("firstName", ""),
                Arguments.of("firstName", "Jean3"),
                Arguments.of("firstName", "a".repeat(61)),
                Arguments.of("lastName", "   "),
                Arguments.of("lastName", "Bel_haj"),
                Arguments.of("phone", "abc"),
                Arguments.of("phone", "123"),
                Arguments.of("address", "a".repeat(256)),
                Arguments.of("city", "a".repeat(61)));
    }

    @ParameterizedTest(name = "{0} = ''{1}'' is rejected")
    @MethodSource("invalidRegistrations")
    void createRejectsInvalidFields(String field, String value) throws Exception {
        Map<String, Object> body = validRegistration();
        body.put(field, value);

        String content = mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value("/users"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains(field + ": ");
    }

    @Test
    void createAcceptsAccentedNamesWithApostropheAndHyphen() throws Exception {
        Map<String, Object> body = validRegistration();
        body.put("firstName", "Jean-François");
        body.put("lastName", "O'Brien");
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(response());

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void createRejectsPasswordLongerThan72Bytes() throws Exception {
        Map<String, Object> body = validRegistration();
        body.put("password", "a1" + "é".repeat(36)); // 38 characters but 74 bytes in UTF-8

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsRolesInTheBody() throws Exception {
        Map<String, Object> body = validRegistration();
        body.put("roles", List.of("ADMIN"));

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("Unknown property: roles"));
    }

    @Test
    void createRejectsMalformedJson() throws Exception {
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createReturns409WhenEmailAlreadyUsed() throws Exception {
        when(userService.createUser(any(CreateUserRequest.class)))
                .thenThrow(new DuplicateResourceException("Email already in use"));

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json(validRegistration())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email already in use"));
    }

    @Test
    void listPassesTheEmailFilterToTheService() throws Exception {
        when(userService.listUsers("Hamza.Belhaj@Example.com")).thenReturn(List.of(response()));

        mvc.perform(get("/users").param("email", "Hamza.Belhaj@Example.com").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        verify(userService).listUsers(eq("Hamza.Belhaj@Example.com"));
    }

    @Test
    void getReturnsTheUser() throws Exception {
        when(userService.getUser(ID)).thenReturn(response());

        mvc.perform(get("/users/{id}", ID).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("hamza.belhaj@example.com"));
    }

    @Test
    void getReturns404WhenUnknown() throws Exception {
        when(userService.getUser(ID)).thenThrow(new ResourceNotFoundException("User not found: " + ID));

        mvc.perform(get("/users/{id}", ID).with(customer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/users/" + ID));
    }

    @Test
    void getReturns400WhenIdIsNotAUuid() throws Exception {
        mvc.perform(get("/users/abc").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void updateReturnsTheUpdatedUser() throws Exception {
        when(userService.updateUser(eq(ID), any(UpdateUserRequest.class))).thenReturn(response());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "Hamza");
        body.put("lastName", "Belhaj");
        body.put("city", "Casablanca");

        mvc.perform(put("/users/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(json(body)).with(customer()))
                .andExpect(status().isOk());
    }

    @Test
    void updateRejectsAnEmailBecauseEmailIsImmutable() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "Hamza");
        body.put("lastName", "Belhaj");
        body.put("email", "other@example.com");

        mvc.perform(put("/users/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(json(body)).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("Unknown property: email"));
    }

    @Test
    void updateRejectsBlankNames() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "");
        body.put("lastName", "Belhaj");

        mvc.perform(put("/users/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(json(body)).with(customer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusPatchReturnsTheUser() throws Exception {
        when(userService.updateStatus(eq(ID), any(UpdateStatusRequest.class))).thenReturn(response());

        mvc.perform(patch("/users/{id}/status", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}").with(customer()))
                .andExpect(status().isOk());
    }

    @Test
    void statusPatchRequiresTheActiveFlag() throws Exception {
        mvc.perform(patch("/users/{id}/status", ID).contentType(MediaType.APPLICATION_JSON).content("{}").with(customer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void usersCannotBeDeleted() throws Exception {
        mvc.perform(delete("/users/{id}", ID).with(customer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unknownUrlReturns404InTheCommonFormat() throws Exception {
        mvc.perform(get("/nothing-here").with(customer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/nothing-here"));
    }

    @Test
    void unexpectedErrorsAreHiddenBehindAGenericMessage() throws Exception {
        when(userService.getUser(ID)).thenThrow(new IllegalStateException("select * from users -- secret detail"));

        String content = mvc.perform(get("/users/{id}", ID).with(customer()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(content).doesNotContain("select").doesNotContain("secret").doesNotContain("IllegalState");
    }
}
