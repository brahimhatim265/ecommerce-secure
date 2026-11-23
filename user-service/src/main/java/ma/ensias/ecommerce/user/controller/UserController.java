package ma.ensias.ecommerce.user.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import ma.ensias.ecommerce.user.dto.CreateUserRequest;
import ma.ensias.ecommerce.user.dto.UpdateStatusRequest;
import ma.ensias.ecommerce.user.dto.UpdateUserRequest;
import ma.ensias.ecommerce.user.dto.UserResponse;
import ma.ensias.ecommerce.user.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import java.util.UUID;

/** REST endpoints for accounts. No business rule here: everything is delegated to {@link UserService}. */
@RestController
@RequestMapping("/users")
@Validated
@Tag(name = "Users", description = "Accounts and profiles")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // Matrice d'accès U1 : inscription publique
    @PreAuthorize("permitAll()")
    @PostMapping
    @Operation(summary = "Register a new account (always with the CUSTOMER role)")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Email already in use")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.createUser(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    // Matrice d'accès U2 : liste et recherche des comptes : administration
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    @Operation(summary = "List users, optionally filtered by email (0 or 1 result)")
    public List<UserResponse> list(@RequestParam(required = false) @Size(max = 120) String email) {
        return userService.listUsers(email);
    }

    // Matrice d'accès U3 : lecture d'un profil (le propriétaire sera vérifié par la règle ABAC A1/A7)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @GetMapping("/{id}")
    @Operation(summary = "Get a user by id (UUID)")
    @ApiResponse(responseCode = "200", description = "User found")
    @ApiResponse(responseCode = "400", description = "Malformed UUID")
    @ApiResponse(responseCode = "404", description = "User not found")
    public UserResponse get(@PathVariable UUID id) {
        return userService.getUser(id);
    }

    // Matrice d'accès U4 : modification d'un profil (le propriétaire sera vérifié par la règle ABAC A1)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @PutMapping("/{id}")
    @Operation(summary = "Update the profile (first name, last name, phone, address, city)")
    @ApiResponse(responseCode = "400", description = "Validation error or unknown property (e.g. email, roles)")
    @ApiResponse(responseCode = "404", description = "User not found")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(id, request);
    }

    // Matrice d'accès U5 : activation / désactivation d'un compte : administration
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate an account")
    @ApiResponse(responseCode = "404", description = "User not found")
    public UserResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return userService.updateStatus(id, request);
    }
}
