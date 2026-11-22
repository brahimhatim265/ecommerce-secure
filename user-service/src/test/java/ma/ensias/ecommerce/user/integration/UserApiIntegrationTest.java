package ma.ensias.ecommerce.user.integration;

import ma.ensias.ecommerce.user.security.mtls.ClientCertificate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.user.entity.User;
import ma.ensias.ecommerce.user.repository.UserRepository;
import ma.ensias.ecommerce.user.security.TestJwtDecoderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static ma.ensias.ecommerce.user.security.TestJwtSupport.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full-stack tests of user-service (real services, real H2, seeded data). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:users-it-${random.uuid};DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class UserApiIntegrationTest {

    private static final String DEMO_PASSWORD = "Demo@2026";
    private static final String HAMZA_ID = "00000000-0000-4000-8000-000000000001";
    private static final String ANAS_ID = "00000000-0000-4000-8000-000000000009";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private static String registration(String email, String firstName, String lastName) {
        return "{\"email\":\"" + email + "\",\"password\":\"Secret123\",\"firstName\":\"" + firstName
                + "\",\"lastName\":\"" + lastName + "\"}";
    }

    private MvcResult register(String email, String firstName, String lastName, int expectedStatus)
            throws Exception {
        return mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(email, firstName, lastName)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void seededDataIsLoadedWithFixedUuids() throws Exception {
        assertThat(userRepository.count()).isGreaterThanOrEqualTo(9);
        mvc.perform(get("/users/" + HAMZA_ID).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("hamza.belhaj@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        mvc.perform(get("/users/" + ANAS_ID).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void registrationNormalizesTheEmailAndAlwaysCreatesACustomer() throws Exception {
        MvcResult result = register("  New.Customer@Example.COM ", "Karim", "Alaoui", 201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("email").asText()).isEqualTo("new.customer@example.com");
        assertThat(body.get("roles").get(0).asText()).isEqualTo("CUSTOMER");
        assertThat(body.get("roles")).hasSize(1);
        assertThat(body.get("active").asBoolean()).isTrue();
        assertThat(UUID.fromString(body.get("id").asText())).isNotNull();
    }

    @Test
    void duplicateEmailIsRefusedEvenWithADifferentCase() throws Exception {
        register("dup.case@example.com", "Sara", "Benani", 201);

        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content(registration("Dup.Case@Example.com", "Sara", "Benani")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void twoUsersMayShareFirstAndLastName() throws Exception {
        register("twin.one@example.com", "Omar", "Idrissi", 201);
        register("twin.two@example.com", "Omar", "Idrissi", 201);
    }

    @Test
    void searchByEmailIsCaseInsensitive() throws Exception {
        register("search.me@example.com", "Lina", "Berrada", 201);

        mvc.perform(get("/users").param("email", " SEARCH.me@Example.com").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("search.me@example.com"));
        mvc.perform(get("/users").param("email", "nobody@example.com").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void registrationRefusesRolesAndUsername() throws Exception {
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"r@example.com\",\"password\":\"Secret123\",\"firstName\":\"A\","
                                + "\"lastName\":\"B\",\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"r@example.com\",\"password\":\"Secret123\",\"firstName\":\"A\","
                                + "\"lastName\":\"B\",\"username\":\"ab\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordIsStoredAsBcryptAndNeverReturned() throws Exception {
        MvcResult result = register("bcrypt.check@example.com", "Yassine", "Cherkaoui", 201);
        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        User stored = userRepository.findByEmail("bcrypt.check@example.com").orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2").doesNotContain("Secret123");

        String created = result.getResponse().getContentAsString().toLowerCase();
        String fetched = mvc.perform(get("/users/" + id).with(customer())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString().toLowerCase();
        String listed = mvc.perform(get("/users").with(customer())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString().toLowerCase();
        for (String content : new String[] {created, fetched, listed}) {
            assertThat(content).doesNotContain("password").doesNotContain("$2a$").doesNotContain("$2b$");
        }
    }

    @Test
    void emailCannotBeChangedByUpdate() throws Exception {
        mvc.perform(put("/users/" + HAMZA_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Hamza\",\"lastName\":\"Belhaj\",\"email\":\"x@example.com\"}").with(customer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profileUpdateKeepsEmailRolesAndId() throws Exception {
        MvcResult result = register("profile.update@example.com", "Nora", "Tahiri", 201);
        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(put("/users/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Noura\",\"lastName\":\"Tahiri\",\"city\":\"Rabat\"}").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.firstName").value("Noura"))
                .andExpect(jsonPath("$.city").value("Rabat"))
                .andExpect(jsonPath("$.email").value("profile.update@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void accountCanBeDeactivatedAndThenLosesItsRightToLogIn() throws Exception {
        MvcResult result = register("to.disable@example.com", "Hind", "Lamrani", 201);
        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(patch("/users/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(post("/internal/auth/verify").with(ClientCertificate.authorizationServer()).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("to.disable@example.com", "Secret123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void verifyAcceptsTheDemoCredentials() throws Exception {
        mvc.perform(post("/internal/auth/verify").with(ClientCertificate.authorizationServer()).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("hamza.belhaj@example.com", DEMO_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(HAMZA_ID))
                .andExpect(jsonPath("$.email").value("hamza.belhaj@example.com"))
                .andExpect(jsonPath("$.firstName").value("Hamza"))
                .andExpect(jsonPath("$.lastName").value("Belhaj"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void verifyAcceptsAnotherEmailCase() throws Exception {
        mvc.perform(post("/internal/auth/verify").with(ClientCertificate.authorizationServer()).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("HAMZA.Belhaj@EXAMPLE.com", DEMO_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(HAMZA_ID));
    }

    @Test
    void verifyGivesTheSame401ForWrongPasswordAndUnknownEmail() throws Exception {
        String wrongPassword = mvc.perform(post("/internal/auth/verify").with(ClientCertificate.authorizationServer()).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("hamza.belhaj@example.com", "Wrong-password1")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = mvc.perform(post("/internal/auth/verify").with(ClientCertificate.authorizationServer()).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ghost@example.com", "Wrong-password1")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String messageOne = objectMapper.readTree(wrongPassword).get("message").asText();
        String messageTwo = objectMapper.readTree(unknownEmail).get("message").asText();
        assertThat(messageOne).isEqualTo("Invalid credentials").isEqualTo(messageTwo);
    }

    @Test
    void verifyReturns403ForTheSeededDisabledAccount() throws Exception {
        mvc.perform(post("/internal/auth/verify").with(ClientCertificate.authorizationServer()).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("anas.tazi@example.com", DEMO_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Account is disabled"));
    }

    @Test
    void verifyNeverLeaksPasswordOrHash() throws Exception {
        String content = mvc.perform(post("/internal/auth/verify").with(ClientCertificate.authorizationServer()).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("hamza.belhaj@example.com", DEMO_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString().toLowerCase();

        assertThat(content).doesNotContain("password").doesNotContain("hash").doesNotContain("$2");
    }

    @Test
    void malformedUuidGives400AndUnknownUuidGives404() throws Exception {
        mvc.perform(get("/users/abc").with(customer())).andExpect(status().isBadRequest());
        mvc.perform(get("/users/" + UUID.randomUUID()).with(customer())).andExpect(status().isNotFound());
    }
}
