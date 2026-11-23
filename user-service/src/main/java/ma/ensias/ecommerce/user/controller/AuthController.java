package ma.ensias.ecommerce.user.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ma.ensias.ecommerce.user.dto.VerifiedIdentityResponse;
import ma.ensias.ecommerce.user.dto.VerifyCredentialsRequest;
import ma.ensias.ecommerce.user.service.AuthenticationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal endpoint used by the future authorization server to check an email / password pair.
 * It is open for now and will be restricted to that server later (see README).
 */
@RestController
@RequestMapping("/internal/auth")
@Tag(name = "Internal", description = "Reserved for the future authorization server")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    // Matrice d'accès U6 : réservé au serveur d'autorisation (certificat)
    @PreAuthorize("@callers.isService('SERVICE_AUTH')")
    @PostMapping("/verify")
    @Operation(summary = "Verify an email / password pair",
            description = "Returns the verified identity (never the hash). Unknown account and wrong password "
                    + "both give 401 'Invalid credentials'; a disabled account with the right password gives 403.")
    @ApiResponse(responseCode = "200", description = "Valid credentials")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @ApiResponse(responseCode = "403", description = "Account is disabled")
    public VerifiedIdentityResponse verify(@Valid @RequestBody VerifyCredentialsRequest request) {
        return authenticationService.verify(request);
    }
}
