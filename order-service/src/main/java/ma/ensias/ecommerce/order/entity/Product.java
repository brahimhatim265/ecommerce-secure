package ma.ensias.ecommerce.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.Objects;

/** A catalog product. Concurrent stock changes are detected with optimistic locking ({@code version}). */
@Entity
@Table(name = "products", uniqueConstraints = {
        @UniqueConstraint(name = "uk_products_sku", columnNames = "sku"),
        @UniqueConstraint(name = "uk_products_name", columnNames = "name")
})
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku", nullable = false, length = 40)
    private String sku;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "brand", nullable = false, length = 60)
    private String brand;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "stock", nullable = false)
    private int stock;

    @Version
    private Long version;

    protected Product() {
        // required by JPA
    }

    public Product(String sku, String name, String brand, String category, String description, BigDecimal price,
                   int stock) {
        update(sku, name, brand, category, description, price, stock);
    }

    /** Replaces every editable field. */
    public final void update(String sku, String name, String brand, String category, String description,
                             BigDecimal price, int stock) {
        this.sku = Objects.requireNonNull(sku, "sku");
        this.name = Objects.requireNonNull(name, "name");
        this.brand = Objects.requireNonNull(brand, "brand");
        this.category = Objects.requireNonNull(category, "category");
        this.description = description;
        this.price = Objects.requireNonNull(price, "price");
        this.stock = stock;
    }

    public boolean hasStockFor(int quantity) {
        return stock >= quantity;
    }

    /** Reserves stock. The caller must have checked {@link #hasStockFor(int)}. */
    public void decreaseStock(int quantity) {
        if (quantity <= 0 || quantity > stock) {
            throw new IllegalArgumentException("Invalid stock decrease: " + quantity + " (stock " + stock + ")");
        }
        this.stock -= quantity;
    }

    /** Gives reserved stock back. */
    public void increaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Invalid stock increase: " + quantity);
        }
        this.stock += quantity;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Product other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Product{id=" + id + ", sku=" + sku + "}";
    }
}
