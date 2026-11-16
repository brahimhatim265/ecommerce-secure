package ma.ensias.ecommerce.order.service;

import ma.ensias.ecommerce.order.dto.ProductRequest;
import ma.ensias.ecommerce.order.dto.ProductResponse;
import ma.ensias.ecommerce.order.entity.Product;
import ma.ensias.ecommerce.order.exception.DuplicateResourceException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.order.mapper.OrderMapper;
import ma.ensias.ecommerce.order.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.List;

/** Catalog management. SKU and name are unique (checked here and by database constraints). */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final OrderMapper mapper;

    public ProductService(ProductRepository productRepository, OrderMapper mapper) {
        this.productRepository = productRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> listProducts(String category) {
        List<Product> products = category == null
                ? productRepository.findAll(Sort.by("id"))
                : productRepository.findByCategoryIgnoreCaseOrderByIdAsc(category.trim());
        return products.stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long id) {
        return mapper.toResponse(findOrThrow(id));
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateResourceException("SKU already in use: " + request.sku());
        }
        if (productRepository.existsByName(request.name())) {
            throw new DuplicateResourceException("Product name already in use: " + request.name());
        }
        Product product = new Product(request.sku(), request.name(), request.brand(), request.category(),
                request.description(), request.price().setScale(2, RoundingMode.HALF_UP), request.stock());
        try {
            productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException("SKU or product name already in use");
        }
        log.info("Product created: id={}, sku={}", product.getId(), product.getSku());
        return mapper.toResponse(product);
    }

    @Transactional
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = findOrThrow(id);
        if (productRepository.existsBySkuAndIdNot(request.sku(), id)) {
            throw new DuplicateResourceException("SKU already in use: " + request.sku());
        }
        if (productRepository.existsByNameAndIdNot(request.name(), id)) {
            throw new DuplicateResourceException("Product name already in use: " + request.name());
        }
        product.update(request.sku(), request.name(), request.brand(), request.category(), request.description(),
                request.price().setScale(2, RoundingMode.HALF_UP), request.stock());
        log.info("Product updated: id={}", id);
        return mapper.toResponse(product);
    }

    private Product findOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }
}
