package ma.ensias.ecommerce.user.controller;

import ma.ensias.ecommerce.user.security.TestIdentities;
import ma.ensias.ecommerce.user.security.WebSliceSecurityTestConfig;
import ma.ensias.ecommerce.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static ma.ensias.ecommerce.user.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.user.security.TestIdentities.OTHER_CUSTOMER_ID;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A customer who asks for the profile of someone else gets a 403 in the ApiError format, never a 500. */
@WebMvcTest(UserController.class)
@Import(WebSliceSecurityTestConfig.class)
class ProfileOwnershipHttpTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService userService;

    @Test
    void aCustomerCannotReadTheProfileOfAnotherUser() throws Exception {
        mvc.perform(get("/users/" + OTHER_CUSTOMER_ID).with(TestIdentities.customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(jsonPath("$.path").value("/users/" + OTHER_CUSTOMER_ID));
        verifyNoInteractions(userService);
    }

    @Test
    void aCustomerCannotReadTheProfileOfAnotherUserEvenWhenThatUserDoesNotExist() throws Exception {
        // same answer for an existing and a missing profile: nothing tells which identifiers exist
        mvc.perform(get("/users/" + java.util.UUID.randomUUID()).with(TestIdentities.customer()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }

    @Test
    void anotherCustomerIsRefusedOnTheProfileOfTheFirstOne() throws Exception {
        mvc.perform(get("/users/" + CUSTOMER_ID).with(TestIdentities.otherCustomer()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }
}
