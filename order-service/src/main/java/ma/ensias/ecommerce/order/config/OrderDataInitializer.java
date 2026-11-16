package ma.ensias.ecommerce.order.config;

import ma.ensias.ecommerce.order.entity.Order;
import ma.ensias.ecommerce.order.entity.OrderItem;
import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.entity.Product;
import ma.ensias.ecommerce.order.repository.OrderRepository;
import ma.ensias.ecommerce.order.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads the demo catalog and the 5 demo orders when the tables are empty. Orders are inserted in order so that
 * their ids are 1 to 5 (the payment-service seed refers to them). The stocks listed for the products are the
 * current ones, after those orders: loading does not decrement anything.
 */
@Component
public class OrderDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderDataInitializer.class);

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;

    public OrderDataInitializer(ProductRepository productRepository, OrderRepository orderRepository, Clock clock) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (productRepository.count() == 0) {
            seedProducts();
        }
        if (orderRepository.count() == 0) {
            seedOrders();
        }
    }

    private void seedProducts() {
        List<Product> products = List.of(
                new Product("PS5-SLIM-STD", "PlayStation 5 Slim Édition Standard", "Sony", "Gaming",
                        "Console de jeu PS5 Slim avec lecteur de disques, SSD 1 To, manette DualSense incluse.",
                        price("5990.00"), 18),
                new Product("PS5-DUALSENSE", "Manette DualSense PS5", "Sony", "Gaming",
                        "Manette sans fil avec retour haptique et gâchettes adaptatives.", price("790.00"), 59),
                new Product("IPH18-256", "iPhone 18 256 Go", "Apple", "Smartphones",
                        "Smartphone Apple, écran OLED, 256 Go de stockage, double capteur photo.",
                        price("13990.00"), 14),
                new Product("GS25U-256", "Samsung Galaxy S25 Ultra 256 Go", "Samsung", "Smartphones",
                        "Smartphone haut de gamme Samsung avec stylet S Pen et zoom optique avancé.",
                        price("12990.00"), 20),
                new Product("LG-TV55-4K", "TV LG 55\" 4K UHD Smart TV", "LG", "TV",
                        "Téléviseur 55 pouces 4K UHD, Smart TV webOS, HDR, trois ports HDMI.",
                        price("5490.00"), 11),
                new Product("MBA13-M4", "MacBook Air 13\" M4 256 Go", "Apple", "Informatique",
                        "Ordinateur portable ultra-fin Apple, puce M4, 256 Go SSD, autonomie de toute la journée.",
                        price("12490.00"), 8),
                new Product("APP-PRO2", "AirPods Pro 2", "Apple", "Audio",
                        "Écouteurs sans fil à réduction active du bruit, boîtier de charge MagSafe.",
                        price("2490.00"), 40),
                new Product("SONY-XM5", "Sony WH-1000XM5", "Sony", "Audio",
                        "Casque Bluetooth à réduction de bruit adaptative, 30 heures d'autonomie.",
                        price("3290.00"), 24),
                new Product("CHG-65W", "Chargeur USB-C 65 W", "Anker", "Accessoires",
                        "Chargeur rapide USB-C 65 W compatible ordinateurs portables, tablettes et smartphones.",
                        price("249.00"), 150));
        productRepository.saveAll(products);
        log.info("Seeded {} demo products", products.size());
    }

    private void seedOrders() {
        Instant today = clock.instant().truncatedTo(ChronoUnit.DAYS);
        List<Order> orders = new ArrayList<>();

        orders.add(seedOrder(today, 1, "00000000-0000-4000-8000-000000000001",
                "45 Rue Ibnou Katir, Maârif", "Casablanca", PaymentMethod.CARD,
                line("PS5-SLIM-STD", 1), line("PS5-DUALSENSE", 1)));
        orders.add(seedOrder(today, 2, "00000000-0000-4000-8000-000000000002",
                "12 Rue Oum Rabia, Agdal", "Rabat", PaymentMethod.CARD,
                line("IPH18-256", 1)));
        orders.add(seedOrder(today, 3, "00000000-0000-4000-8000-000000000003",
                "8 Avenue Mohammed VI, Guéliz", "Marrakech", PaymentMethod.CARD,
                line("LG-TV55-4K", 1)));
        orders.add(seedOrder(today, 4, "00000000-0000-4000-8000-000000000004",
                "27 Rue de la Liberté, Centre-ville", "Tanger", PaymentMethod.CARD,
                line("SONY-XM5", 1), line("CHG-65W", 1)));
        orders.add(seedOrder(today, 5, "00000000-0000-4000-8000-000000000005",
                "5 Rue Atlas, Ville Nouvelle", "Fès", PaymentMethod.CASH_ON_DELIVERY,
                line("APP-PRO2", 1), line("CHG-65W", 2)));

        for (Order order : orders) {
            // saved one by one so that identity ids are 1..5; paymentId equals the order id (seed convention)
            Order saved = orderRepository.save(order);
            saved.markConfirmed(saved.getId(), order.getCreatedAt());
            orderRepository.save(saved);
        }
        log.info("Seeded {} demo orders", orders.size());
    }

    /** Same formula as the payment-service seed: order N is created (6 - N) days before today, at a fixed time. */
    static Instant orderCreatedAt(Instant today, int orderNumber) {
        return today.minus(6 - orderNumber, ChronoUnit.DAYS).plus(9 + orderNumber, ChronoUnit.HOURS)
                .plus(orderNumber * 7L, ChronoUnit.MINUTES);
    }

    private Order seedOrder(Instant today, int number, String ownerId, String address, String city,
                            PaymentMethod method, SeedLine... lines) {
        Order order = new Order(UUID.fromString(ownerId), OrderStatus.PENDING_PAYMENT, address, city, method,
                orderCreatedAt(today, number));
        for (SeedLine seedLine : lines) {
            Product product = productRepository.findBySku(seedLine.sku()).orElseThrow();
            order.addItem(new OrderItem(product.getId(), product.getName(), product.getPrice(), seedLine.quantity()));
        }
        return order;
    }

    private static SeedLine line(String sku, int quantity) {
        return new SeedLine(sku, quantity);
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value);
    }

    private record SeedLine(String sku, int quantity) {
    }
}
