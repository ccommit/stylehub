package ccommit.stylehub.user.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.user.dto.request.AddressCreateRequest;
import ccommit.stylehub.user.dto.response.AddressResponse;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.service.AddressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 로그인한 구매자(USER)의 배송지 관리 API를 제공한다.
 * 경로에 userId 를 받지 않고 세션의 userId 만 사용해, 요청 값으로 다른 사용자의 배송지를 지정할 수 없게 한다.
 * </p>
 */
@RestController
@RequestMapping("/users/me/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @PostMapping
    @RequiredRole(UserRole.USER)
    public ResponseEntity<AddressResponse> registerAddress(
            @Valid @RequestBody AddressCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(addressService.registerAddress(userId, request));
    }

    @GetMapping
    @RequiredRole(UserRole.USER)
    public ResponseEntity<List<AddressResponse>> getAddresses(HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(addressService.getAddresses(userId));
    }

    @PatchMapping("/{addressId}/default")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<AddressResponse> changeDefaultAddress(
            @PathVariable Long addressId,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(addressService.changeDefaultAddress(userId, addressId));
    }

    @DeleteMapping("/{addressId}")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<Void> deleteAddress(
            @PathVariable Long addressId,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        addressService.deleteAddress(userId, addressId);
        return ResponseEntity.noContent().build();
    }
}
