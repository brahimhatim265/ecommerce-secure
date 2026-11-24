package ma.ensias.ecommerce.payment.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.payment.security.mtls.ClientCertificate;
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

import static ma.ensias.ecommerce.payment.security.TestIdentities.ADMIN_ID;
import static ma.ensias.ecommerce.payment.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.payment.security.TestIdentities.OTHER_CUSTOMER_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access matrix (docs/matrice-acces.md) enforced end to end: every row below is one real HTTP request through the
 * whole stack (signed token, JWT decoder, role converter, URL rules, client certificate, method rules, ownership
 * rules, error handlers) with the answer that the matrix promises.
 * <p>
 * Every 4xx must also be an ApiError (status, error, message, path, timestamp, details) that tells nothing about the
 * inside of the service. The result of each row is written to {@code target/access-matrix-payment-service.md}, ready for
 * the report.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payments-matrix-${random.uuid};DB_CLOSE_DELAY=-1",
        "payment.simulation.random.seed=2026"})
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

    private static String cash(UUID owner, int orderId) {
        return "{\"orderId\":" + orderId + ",\"ownerId\":\"" + owner + "\",\"amount\":10.00,"
                + "\"currency\":\"MAD\",\"method\":\"CASH_ON_DELIVERY\"}";
    }

    /** A payment request that is new every time (own order number, own idempotency key). */
    private static Row creation(Who who, int expected, UUID owner) {
        int order = UNIQUE.incrementAndGet();
        return new Row("P1", POST, "/payments", Map.of(), () -> cash(owner, order),
                Map.of("Idempotency-Key", "matrix-" + order), who, expected);
    }

    static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        // P1 create a payment: the order service, for the user of the relayed token, and for nobody else (A5)
        rows.add(creation(Who.ORDER_SERVICE_FOR_CUSTOMER, 201, CUSTOMER_ID));
        rows.add(creation(Who.ORDER_SERVICE_FOR_OTHER, 403, CUSTOMER_ID));
        rows.add(creation(Who.ORDER_SERVICE_ALONE, 401, CUSTOMER_ID));
        rows.add(creation(Who.CUSTOMER, 401, CUSTOMER_ID));
        rows.add(creation(Who.ADMIN, 401, CUSTOMER_ID));
        rows.add(creation(Who.ANONYMOUS, 401, CUSTOMER_ID));
        rows.add(creation(Who.ROGUE_SERVICE, 403, CUSTOMER_ID));
        rows.add(creation(Who.AUTH_SERVICE, 403, CUSTOMER_ID));
        // P2, P3 read a payment: its owner or an administrator; for anybody else a 404 (decision D2)
        for (String path : List.of("/payments/1", "/payments/order/1")) {
            String ref = path.contains("order") ? "P3" : "P2";
            rows.add(row(ref, GET, path, Who.ANONYMOUS, 401));
            rows.add(row(ref, GET, path, Who.ORDER_SERVICE_ALONE, 401));
            rows.add(row(ref, GET, path, Who.CUSTOMER, 200));
            rows.add(row(ref, GET, path, Who.OTHER_CUSTOMER, 404));
            rows.add(row(ref, GET, path, Who.ADMIN, 200));
        }
        rows.add(row("P2", GET, "/payments/9999", Who.CUSTOMER, 404));
        rows.add(row("P2", GET, "/payments/9999", Who.ADMIN, 404));
        // P4 list the payments: filtered on the identity (A4)
        rows.add(row("P4", GET, "/payments", Who.ANONYMOUS, 401));
        rows.add(row("P4", GET, "/payments", Who.CUSTOMER, 200));
        rows.add(row("P4", GET, "/payments", Map.of("ownerId", CUSTOMER_ID.toString()), Who.CUSTOMER, 200));
        rows.add(row("P4", GET, "/payments", Map.of("ownerId", OTHER_CUSTOMER_ID.toString()), Who.CUSTOMER, 403));
        rows.add(row("P4", GET, "/payments", Who.ADMIN, 200));
        rows.add(row("P4", GET, "/payments", Map.of("ownerId", OTHER_CUSTOMER_ID.toString()), Who.ADMIN, 200));
        // P5 collect the payment at delivery: administrators only
        rows.add(row("P5", POST, "/payments/5/collect", Who.ANONYMOUS, 401));
        rows.add(row("P5", POST, "/payments/5/collect", Who.CUSTOMER, 403));
        rows.add(row("P5", POST, "/payments/5/collect", Who.OTHER_CUSTOMER, 403));
        rows.add(row("P5", POST, "/payments/5/collect", Who.ORDER_SERVICE_FOR_CUSTOMER, 403));
        rows.add(row("P5", POST, "/payments/5/collect", Who.ADMIN, 200));
        // P6 health probe: public
        rows.add(row("P6", GET, "/actuator/health", Who.ANONYMOUS, 200));
        // default
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

    @org.junit.jupiter.api.Test
    void anotherUsersPaymentCannotBeToldApartFromAMissingOne() throws Exception {
        MockHttpServletResponse someoneElses = mvc.perform(MockMvcRequestBuilders.get("/payments/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestIdentities.bearerToken(OTHER_CUSTOMER_ID, "CUSTOMER")))
                .andReturn().getResponse();
        MockHttpServletResponse missing = mvc.perform(MockMvcRequestBuilders.get("/payments/9999")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestIdentities.bearerToken(OTHER_CUSTOMER_ID, "CUSTOMER")))
                .andReturn().getResponse();

        assertThat(someoneElses.getStatus()).isEqualTo(404).isEqualTo(missing.getStatus());
        JsonNode a = JSON.readTree(someoneElses.getContentAsString(StandardCharsets.UTF_8));
        JsonNode b = JSON.readTree(missing.getContentAsString(StandardCharsets.UTF_8));
        assertThat(a.fieldNames()).toIterable().containsExactlyElementsOf(() -> b.fieldNames());
        assertThat(a.get("error")).isEqualTo(b.get("error"));
        assertThat(a.get("message").asText().replaceAll("\\d+", "N")).isEqualTo(b.get("message").asText().replaceAll("\\d+", "N"));
    }

    @AfterAll
    static void writeTheReport() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Matrice d'accès appliquée : payment-service");
        lines.add("");
        lines.add("| Réf. | Requête | Appelant | Attendu | Obtenu | Résultat |");
        lines.add("|---|---|---|---|---|---|");
        synchronized (REPORT) {
            lines.addAll(REPORT);
        }
        Path target = Path.of("target");
        if (Files.isDirectory(target)) {
            Files.write(target.resolve("access-matrix-payment-service.md"), lines, StandardCharsets.UTF_8);
        }
    }
}
