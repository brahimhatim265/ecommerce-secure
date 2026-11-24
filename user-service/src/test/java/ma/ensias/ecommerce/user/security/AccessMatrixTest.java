package ma.ensias.ecommerce.user.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.user.security.mtls.ClientCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static ma.ensias.ecommerce.user.security.TestIdentities.ADMIN_ID;
import static ma.ensias.ecommerce.user.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.user.security.TestIdentities.OTHER_CUSTOMER_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access matrix (docs/matrice-acces.md) enforced end to end: every row below is one real HTTP request through the
 * whole stack (signed token, JWT decoder, role converter, URL rules, client certificate, method rules, ownership
 * rules, error handlers) with the answer that the matrix promises.
 * <p>
 * Every 4xx must also be an ApiError (status, error, message, path, timestamp, details) that tells nothing about the
 * inside of the service. The result of each row is written to {@code target/access-matrix-user-service.md}, ready for
 * the report.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:users-matrix-${random.uuid};DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class AccessMatrixTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REPORT = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger UNIQUE = new AtomicInteger(9000);

    @Autowired
    private MockMvc mvc;

    /** Who calls: the identity (token and/or client certificate) that the request carries. */
    enum Who {
        ANONYMOUS, CUSTOMER, OTHER_CUSTOMER, ADMIN,
        ORDER_SERVICE_FOR_CUSTOMER, ORDER_SERVICE_FOR_OTHER, ORDER_SERVICE_ALONE, AUTH_SERVICE, ROGUE_SERVICE
    }

    /** One line of the matrix: a request, the caller, and the status that the matrix promises. */
    record Row(String ref, HttpMethod method, String path, Map<String, String> params, Supplier<String> body,
               Map<String, String> headers, Who who, int expected) {
        @Override
        public String toString() {
            String query = params.isEmpty() ? "" : "?" + String.join("&", params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).toList());
            return "[" + ref + "] " + method + " " + path + query + " as " + who + " -> " + expected;
        }
    }

    private static Row row(String ref, HttpMethod method, String path, Who who, int expected) {
        return new Row(ref, method, path, Map.of(), null, Map.of(), who, expected);
    }

    private static Row row(String ref, HttpMethod method, String path, Map<String, String> params, Who who,
                           int expected) {
        return new Row(ref, method, path, params, null, Map.of(), who, expected);
    }

    private static Row row(String ref, HttpMethod method, String path, Supplier<String> body, Who who, int expected) {
        return new Row(ref, method, path, Map.of(), body, Map.of(), who, expected);
    }

    private static void bearer(MockHttpServletRequestBuilder request, UUID subject, String role) {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestIdentities.bearerToken(subject, role));
    }

    private static void apply(MockHttpServletRequestBuilder request, Who who) {
        switch (who) {
            case ANONYMOUS -> { }
            case CUSTOMER -> bearer(request, CUSTOMER_ID, "CUSTOMER");
            case OTHER_CUSTOMER -> bearer(request, OTHER_CUSTOMER_ID, "CUSTOMER");
            case ADMIN -> bearer(request, ADMIN_ID, "ADMIN");
            case ORDER_SERVICE_FOR_CUSTOMER -> {
                bearer(request, CUSTOMER_ID, "CUSTOMER");
                request.with(ClientCertificate.of("order-service"));
            }
            case ORDER_SERVICE_FOR_OTHER -> {
                bearer(request, OTHER_CUSTOMER_ID, "CUSTOMER");
                request.with(ClientCertificate.of("order-service"));
            }
            case ORDER_SERVICE_ALONE -> request.with(ClientCertificate.of("order-service"));
            case AUTH_SERVICE -> request.with(ClientCertificate.of("authorization-server"));
            case ROGUE_SERVICE -> request.with(ClientCertificate.of("rogue-service"));
        }
    }

    private static final HttpMethod GET = HttpMethod.GET;
    private static final HttpMethod POST = HttpMethod.POST;
    private static final HttpMethod PUT = HttpMethod.PUT;
    private static final HttpMethod PATCH = HttpMethod.PATCH;

    private static String registration() {
        return "{\"email\":\"" + UUID.randomUUID() + "@example.com\",\"password\":\"Secret123\","
                + "\"firstName\":\"Test\",\"lastName\":\"Matrice\"}";
    }

    private static final String PROFILE = "{\"firstName\":\"Hamza\",\"lastName\":\"Belhaj\",\"city\":\"Casablanca\"}";
    private static final String CREDENTIALS = "{\"email\":\"hamza.belhaj@example.com\",\"password\":\"Demo@2026\"}";
    private static final String HAMZA = "/users/" + CUSTOMER_ID;
    private static final String ISSAM = "/users/" + OTHER_CUSTOMER_ID;

    static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        // U1 sign-up: public
        for (Who who : List.of(Who.ANONYMOUS, Who.CUSTOMER, Who.ADMIN)) {
            rows.add(row("U1", POST, "/users", AccessMatrixTest::registration, who, 201));
        }
        // U2 list and search of the accounts: administrators only
        rows.add(row("U2", GET, "/users", Who.ANONYMOUS, 401));
        rows.add(row("U2", GET, "/users", Who.CUSTOMER, 403));
        rows.add(row("U2", GET, "/users", Who.ORDER_SERVICE_FOR_CUSTOMER, 403));
        rows.add(row("U2", GET, "/users", Who.ADMIN, 200));
        rows.add(row("U2", GET, "/users", Map.of("email", "issam.bourhim@example.com"), Who.CUSTOMER, 403));
        rows.add(row("U2", GET, "/users", Map.of("email", "issam.bourhim@example.com"), Who.ADMIN, 200));
        // U3 read a profile: its owner (A1), the order service for the user of the relayed token (A7), an administrator
        rows.add(row("U3", GET, HAMZA, Who.ANONYMOUS, 401));
        rows.add(row("U3", GET, HAMZA, Who.CUSTOMER, 200));
        rows.add(row("U3", GET, HAMZA, Who.OTHER_CUSTOMER, 403));
        rows.add(row("U3", GET, HAMZA, Who.ADMIN, 200));
        rows.add(row("U3", GET, HAMZA, Who.ORDER_SERVICE_FOR_CUSTOMER, 200));
        rows.add(row("U3", GET, HAMZA, Who.ORDER_SERVICE_FOR_OTHER, 403));
        rows.add(row("U3", GET, HAMZA, Who.ORDER_SERVICE_ALONE, 401));
        rows.add(row("U3", GET, "/users/" + UUID.randomUUID(), Who.CUSTOMER, 403));
        rows.add(row("U3", GET, "/users/" + UUID.randomUUID(), Who.ADMIN, 404));
        // U4 update a profile: its owner or an administrator
        rows.add(row("U4", PUT, HAMZA, () -> PROFILE, Who.ANONYMOUS, 401));
        rows.add(row("U4", PUT, HAMZA, () -> PROFILE, Who.CUSTOMER, 200));
        rows.add(row("U4", PUT, HAMZA, () -> PROFILE, Who.OTHER_CUSTOMER, 403));
        rows.add(row("U4", PUT, HAMZA, () -> PROFILE, Who.ADMIN, 200));
        // U5 activate or deactivate an account: administrators only
        rows.add(row("U5", PATCH, ISSAM + "/status", () -> "{\"active\":true}", Who.ANONYMOUS, 401));
        rows.add(row("U5", PATCH, ISSAM + "/status", () -> "{\"active\":true}", Who.CUSTOMER, 403));
        rows.add(row("U5", PATCH, ISSAM + "/status", () -> "{\"active\":true}", Who.ADMIN, 200));
        // U6 credentials check: the authorization server only, by its certificate, without any user
        rows.add(row("U6", POST, "/internal/auth/verify", () -> CREDENTIALS, Who.AUTH_SERVICE, 200));
        rows.add(row("U6", POST, "/internal/auth/verify", () -> CREDENTIALS, Who.ANONYMOUS, 401));
        rows.add(row("U6", POST, "/internal/auth/verify", () -> CREDENTIALS, Who.CUSTOMER, 401));
        rows.add(row("U6", POST, "/internal/auth/verify", () -> CREDENTIALS, Who.ADMIN, 401));
        rows.add(row("U6", POST, "/internal/auth/verify", () -> CREDENTIALS, Who.ORDER_SERVICE_ALONE, 403));
        rows.add(row("U6", POST, "/internal/auth/verify", () -> CREDENTIALS, Who.ORDER_SERVICE_FOR_CUSTOMER, 403));
        rows.add(row("U6", POST, "/internal/auth/verify", () -> CREDENTIALS, Who.ROGUE_SERVICE, 403));
        // U7 health probe: public
        rows.add(row("U7", GET, "/actuator/health", Who.ANONYMOUS, 200));
        rows.add(row("U7", GET, "/actuator/health", Who.CUSTOMER, 200));
        // default: anything else needs a user, and an unknown URL is a 404 for that user
        rows.add(row("D7", GET, "/nothing-here", Who.ANONYMOUS, 401));
        rows.add(row("D7", GET, "/nothing-here", Who.CUSTOMER, 404));
        return rows;
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void theMatrixIsEnforced(Row row) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(row.method(), row.path());
        row.params().forEach((name, value) -> request.param(name, value));
        row.headers().forEach((name, value) -> request.header(name, value));
        if (row.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(row.body().get());
        }
        apply(request, row.who());

        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();

        REPORT.add("| " + row.ref() + " | " + row.method() + " " + row.path()
                + (row.params().isEmpty() ? "" : "?" + String.join("&", row.params().entrySet().stream()
                        .map(e -> e.getKey() + "=...").toList()))
                + " | " + row.who() + " | " + row.expected() + " | " + response.getStatus() + " | "
                + (response.getStatus() == row.expected() ? "OK" : "ECART") + " |");
        assertThat(response.getStatus()).as(row.toString()).isEqualTo(row.expected());
        if (row.expected() >= 400) {
            assertThatItIsAnApiErrorThatHidesTheInside(response, row);
        }
    }

    private static void assertThatItIsAnApiErrorThatHidesTheInside(MockHttpServletResponse response, Row row)
            throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        JsonNode error = JSON.readTree(body);
        assertThat(error.path("status").asInt()).as(row.toString()).isEqualTo(row.expected());
        assertThat(error.path("path").asText()).as(row.toString()).isEqualTo(row.path());
        assertThat(error.has("timestamp")).as(row.toString()).isTrue();
        assertThat(error.has("error")).as(row.toString()).isTrue();
        assertThat(error.has("message")).as(row.toString()).isTrue();
        assertThat(error.has("details")).as(row.toString()).isTrue();
        assertThat(body).as(row.toString())
                .doesNotContain("Exception")
                .doesNotContain("ma.ensias")
                .doesNotContain("java.")
                .doesNotContain("SQL")
                .doesNotContain("eyJ");
    }


    @AfterAll
    static void writeTheReport() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Matrice d'accès appliquée : user-service");
        lines.add("");
        lines.add("| Réf. | Requête | Appelant | Attendu | Obtenu | Résultat |");
        lines.add("|---|---|---|---|---|---|");
        synchronized (REPORT) {
            lines.addAll(REPORT);
        }
        Path target = Path.of("target");
        if (Files.isDirectory(target)) {
            Files.write(target.resolve("access-matrix-user-service.md"), lines, StandardCharsets.UTF_8);
        }
    }
}
