package ma.ensias.ecommerce.order.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import ma.ensias.ecommerce.order.dto.ProductRequest;
import ma.ensias.ecommerce.order.dto.ProductResponse;
import ma.ensias.ecommerce.order.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/** REST endpoints of the product catalog (prices in MAD). */
@RestController
@RequestMapping("/products")
@Validated
@Tag(name = "Products", description = "Product catalog")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // Matrice d'accès O1 : catalogue public
    @PreAuthorize("permitAll()")
    @GetMapping
    @Operation(summary = "List products, optionally filtered by category")
    public List<ProductResponse> list(@RequestParam(required = false) @Size(max = 50) String category) {
        return productService.listProducts(category);
    }

    // Matrice d'accès O2 : catalogue public
    @PreAuthorize("permitAll()")
    @GetMapping("/{id}")
    @Operation(summary = "Get a product by id")
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ProductResponse get(@PathVariable Long id) {
        return productService.getProduct(id);
    }

    // Matrice d'accès O3 : gestion du catalogue : administration
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @Operation(summary = "Create a product")
    @ApiResponse(responseCode = "201", description = "Product created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "SKU or name already in use")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.createProduct(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    // Matrice d'accès O4 : gestion du catalogue : administration
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    @Operation(summary = "Replace a product")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "SKU or name already in use, or concurrent modification")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.updateProduct(id, request);
    }
}
