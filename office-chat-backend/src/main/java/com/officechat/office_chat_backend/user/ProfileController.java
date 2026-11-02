package com.officechat.office_chat_backend.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class ProfileController {

    private final ProfileService profileService;
    private final com.officechat.office_chat_backend.auth.PasswordService passwordService;

    @PostMapping("/me/photo")
    public UserResponse uploadPhoto(@AuthenticationPrincipal User user, @RequestParam("file") MultipartFile file) {
        return profileService.setPhoto(user.getId(), file);
    }

    @DeleteMapping("/me/photo")
    public UserResponse removePhoto(@AuthenticationPrincipal User user) {
        return profileService.removePhoto(user.getId());
    }

    /** Body: { jobTitle } */
    @PutMapping("/me/profile")
    public UserResponse updateMyProfile(@AuthenticationPrincipal User user, @RequestBody Map<String, String> body) {
        return profileService.updateOwnProfile(user.getId(), body.get("jobTitle"));
    }

    /** Body: { jobTitle, department, managerId } */
    @PutMapping("/admin/{userId}/profile")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse adminUpdateProfile(@AuthenticationPrincipal User admin, @PathVariable Long userId,
                                           @RequestBody Map<String, Object> body) {
        Object manager = body.get("managerId");
        return profileService.adminUpdateProfile(admin.getId(), userId, (String) body.get("jobTitle"),
                (String) body.get("department"), manager == null ? null : ((Number) manager).longValue());
    }

    /**
     * Admin: issue a temporary password (shown once). Body: { disableTwoFactor? }.
     * The person is signed out everywhere and must choose a new password at next login.
     */
    @PostMapping("/admin/{userId}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> resetPassword(@AuthenticationPrincipal User admin, @PathVariable Long userId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        boolean disable2fa = body != null && Boolean.TRUE.equals(body.get("disableTwoFactor"));
        return Map.of("temporaryPassword", passwordService.adminReset(admin, userId, disable2fa));
    }

    /** Public by URL (random name); file names change on every upload, so they can be cached for long. */
    @GetMapping("/photo/{fileName}")
    public ResponseEntity<Resource> photo(@PathVariable String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(fileName).orElse(MediaType.IMAGE_JPEG))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(profileService.loadPhoto(fileName));
    }
}
