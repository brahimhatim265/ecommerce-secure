package ma.ensias.ecommerce.order.service;

import ma.ensias.ecommerce.order.dto.ProductRequest;
import ma.ensias.ecommerce.order.dto.ProductResponse;
import ma.ensias.ecommerce.order.entity.Product;
import ma.ensias.ecommerce.order.exception.DuplicateResourceException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.order.mapper.OrderMapper;
import ma.ensias.ecommerce.order.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, new OrderMapper());
    }

    private static ProductRequest request(String sku, String name) {
        return new ProductRequest(sku, name, "Sony", "Gaming", "desc", new BigDecimal("790.5"), 10);
    }

    private static Product product() {
        return new Product("PS5-DUALSENSE", "Manette DualSense PS5", "Sony", "Gaming", null,
                new BigDecimal("790.00"), 59);
    }

    @Test
    void listWithoutCategoryReturnsEverything() {
        when(productRepository.findAll(any(Sort.class))).thenReturn(List.of(product()));

        assertThat(service.listProducts(null)).hasSize(1);
    }

    @Test
    void listWithCategoryFilters() {
        when(productRepository.findByCategoryIgnoreCaseOrderByIdAsc("Gaming")).thenReturn(List.of(product()));

        assertThat(service.listProducts(" Gaming ")).hasSize(1);
    }

    @Test
    void getUnknownProductFails() {
        when(productRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProduct(5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createStoresThePriceWithTwoDecimals() {
        when(productRepository.existsBySku("NEW-SKU")).thenReturn(false);
        when(productRepository.existsByName("New product")).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = service.createProduct(request("NEW-SKU", "New product"));

        assertThat(response.price()).isEqualTo(new BigDecimal("790.50"));
        assertThat(response.stock()).isEqualTo(10);
    }

    @Test
    void createRejectsDuplicateSkuAndName() {
        when(productRepository.existsBySku("DUP")).thenReturn(true);
        assertThatThrownBy(() -> service.createProduct(request("DUP", "Whatever")))
                .isInstanceOf(DuplicateResourceException.class);

        when(productRepository.existsBySku("OK")).thenReturn(false);
        when(productRepository.existsByName("Dup name")).thenReturn(true);
        assertThatThrownBy(() -> service.createProduct(request("OK", "Dup name")))
                .isInstanceOf(DuplicateResourceException.class);
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void createTranslatesAUniqueConstraintRace() {
        when(productRepository.existsBySku("RACE")).thenReturn(false);
        when(productRepository.existsByName("Race")).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.createProduct(request("RACE", "Race")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateReplacesTheFields() {
        Product existing = product();
        when(productRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(productRepository.existsBySkuAndIdNot("PS5-DUALSENSE", 2L)).thenReturn(false);
        when(productRepository.existsByNameAndIdNot("Manette renommée", 2L)).thenReturn(false);

        ProductResponse response = service.updateProduct(2L, request("PS5-DUALSENSE", "Manette renommée"));

        assertThat(response.name()).isEqualTo("Manette renommée");
        assertThat(response.stock()).isEqualTo(10);
        assertThat(existing.getPrice()).isEqualTo(new BigDecimal("790.50"));
    }

    @Test
    void updateRejectsASkuUsedByAnotherProduct() {
        when(productRepository.findById(2L)).thenReturn(Optional.of(product()));
        when(productRepository.existsBySkuAndIdNot("TAKEN", 2L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateProduct(2L, request("TAKEN", "Name")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateRejectsANameUsedByAnotherProduct() {
        when(productRepository.findById(2L)).thenReturn(Optional.of(product()));
        when(productRepository.existsBySkuAndIdNot("SKU", 2L)).thenReturn(false);
        when(productRepository.existsByNameAndIdNot("Taken name", 2L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateProduct(2L, request("SKU", "Taken name")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateUnknownProductFails() {
        when(productRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProduct(9L, request("SKU", "Name")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
