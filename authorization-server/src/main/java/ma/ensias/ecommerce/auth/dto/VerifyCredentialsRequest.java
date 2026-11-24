package ma.ensias.ecommerce.auth.dto;

public record VerifyCredentialsRequest(String email, String password) {

    @Override
    public String toString() {
        return "VerifyCredentialsRequest[email=" + email + "]";
    }
}