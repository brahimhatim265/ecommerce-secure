package ma.ensias.ecommerce.order.client;

import ma.ensias.ecommerce.order.dto.UserSummary;
import ma.ensias.ecommerce.order.exception.ExternalServiceException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserClientTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String URL = "http://user-service.test/users/" + ID;

    private MockRestServiceServer server;
    private UserClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://user-service.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new UserClient(builder.build());
    }

    @Test
    void activeUserIsReadAndUnknownFieldsAreIgnored() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"id\":\"" + ID + "\",\"email\":\"hamza.belhaj@example.com\",\"roles\":[\"CUSTOMER\"],"
                        + "\"active\":true}", MediaType.APPLICATION_JSON));

        UserSummary user = client.getUser(ID);

        assertThat(user.id()).isEqualTo(ID);
        assertThat(user.active()).isTrue();
    }

    @Test
    void inactiveUserIsReportedAsInactive() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"id\":\"" + ID + "\",\"active\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.getUser(ID).active()).isFalse();
    }

    @Test
    void notFoundBecomesResourceNotFound() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getUser(ID)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void serverErrorBecomesExternalServiceException() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.getUser(ID)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void ioErrorBecomesExternalServiceException() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("refused")));

        assertThatThrownBy(() -> client.getUser(ID)).isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("unreachable");
    }
}
