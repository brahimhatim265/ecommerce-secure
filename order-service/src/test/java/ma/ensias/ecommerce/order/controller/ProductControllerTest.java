package ma.ensias.ecommerce.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.order.dto.ProductRequest;
import ma.ensias.ecommerce.order.dto.ProductResponse;
import ma.ensias.ecommerce.order.exception.DuplicateResourceException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.order.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    private static ProductResponse product() {
        return new ProductResponse(2L, "PS5-DUALSENSE", "Manette DualSense PS5", "Sony", "Gaming", "desc",
                new BigDecimal("790.00"), 59);
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", "PS5-DUALSENSE");
        body.put("name", "Manette DualSense PS5");
        body.put("brand", "Sony");
        body.put("category", "Gaming");
        body.put("description", "desc");
        body.put("price", new BigDecimal("790.00"));
        body.put("stock", 59);
        return body;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    void listPassesTheCategoryFilter() throws Exception {
        when(productService.listProducts("Gaming")).thenReturn(List.of(product()));

        mvc.perform(get("/products").param("category", "Gaming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].price").value(790.00));
    }

    @Test
    void getReturns404WhenUnknownAnd400WhenIdIsMalformed() throws Exception {
        when(productService.getProduct(99L)).thenThrow(new ResourceNotFoundException("Product not found: 99"));

        mvc.perform(get("/products/99")).andExpect(status().isNotFound());
        mvc.perform(get("/products/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(productService.createProduct(any(ProductRequest.class))).thenReturn(product());

        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/products/2"));
    }

    static Stream<Arguments> invalidProducts() {
        return Stream.of(
                Arguments.of("sku", ""),
                Arguments.of("sku", "s".repeat(41)),
                Arguments.of("name", " "),
                Arguments.of("name", "n".repeat(121)),
                Arguments.of("brand", ""),
                Arguments.of("brand", "b".repeat(61)),
                Arguments.of("category", ""),
                Arguments.of("category", "c".repeat(51)),
                Arguments.of("description", "d".repeat(501)),
                Arguments.of("price", null),
                Arguments.of("price", new BigDecimal("0.00")),
                Arguments.of("price", new BigDecimal("-1.00")),
                Arguments.of("price", new BigDecimal("1.005")),
                Arguments.of("stock", null),
                Arguments.of("stock", -1));
    }

    @ParameterizedTest(name = "{0} = {1} is rejected")
    @MethodSource("invalidProducts")
    void invalidProductsAreRejected(String field, Object value) throws Exception {
        Map<String, Object> body = validBody();
        body.put(field, value);

        String content = mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains(field + ": ");
        verifyNoInteractions(productService);
    }

    @Test
    void duplicateSkuReturns409() throws Exception {
        when(productService.createProduct(any(ProductRequest.class)))
                .thenThrow(new DuplicateResourceException("SKU already in use: PS5-DUALSENSE"));

        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isConflict());
    }

    @Test
    void updateReturnsTheProductAndConflictsMapTo409() throws Exception {
        when(productService.updateProduct(eq(2L), any(ProductRequest.class))).thenReturn(product());
        when(productService.updateProduct(eq(3L), any(ProductRequest.class)))
                .thenThrow(new OptimisticLockingFailureException("collision"));

        mvc.perform(put("/products/2").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isOk());
        mvc.perform(put("/products/3").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isConflict());
    }
}
