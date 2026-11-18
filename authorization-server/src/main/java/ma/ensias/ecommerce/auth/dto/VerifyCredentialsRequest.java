package ma.ensias.ecommerce.auth.dto;

public record VerifyCredentialsRequest(String email, String password) {

    @Override
    public String toString() {
        // never print the password
        return "VerifyCredentialsRequest[email=" + email + "]";
    }
}