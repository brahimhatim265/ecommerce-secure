package ma.ensias.ecommerce.user.mapper;

import ma.ensias.ecommerce.user.dto.UserResponse;
import ma.ensias.ecommerce.user.dto.VerifiedIdentityResponse;
import ma.ensias.ecommerce.user.entity.User;
import org.springframework.stereotype.Component;

import java.util.TreeSet;

/** Hand-written entity to DTO conversions. The password hash is never copied. */
@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getPhone(), user.getAddress(), user.getCity(), new TreeSet<>(user.getRoles()),
                user.isActive(), user.getCreatedAt(), user.getUpdatedAt());
    }

    public VerifiedIdentityResponse toIdentity(User user) {
        return new VerifiedIdentityResponse(user.getId(), user.getEmail(), user.getFirstName(),
                user.getLastName(), new TreeSet<>(user.getRoles()));
    }
}
