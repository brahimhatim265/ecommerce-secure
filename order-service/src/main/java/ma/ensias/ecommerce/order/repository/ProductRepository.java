package ma.ensias.ecommerce.order.repository;

import ma.ensias.ecommerce.order.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCategoryIgnoreCaseOrderByIdAsc(String category);

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    boolean existsByName(String name);

    boolean existsBySkuAndIdNot(String sku, Long id);

    boolean existsByNameAndIdNot(String name, Long id);
}
