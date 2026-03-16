package bwj.stylehub.user.dto.response;

public record GoogleUserInfoResponse(

        String sub,
        String name,
        String email
) {
}
