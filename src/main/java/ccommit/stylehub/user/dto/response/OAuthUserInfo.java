package ccommit.stylehub.user.dto.response;

public record OAuthUserInfo(
        String name,
        String email,
        String providerId
) {
}
