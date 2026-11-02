package com.officechat.office_chat_backend.user;

import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.file.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.Set;

/** Profile photo, job title and reporting line (org chart). */
@Service
@RequiredArgsConstructor
public class ProfileService {

    public static final String PHOTO_PATH = "/api/users/photo/";
    private static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_PHOTO_BYTES = 2 * 1024 * 1024;

    private final UserRepository userRepository;
    private final FileStorageService storageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final AuditService auditService;

    @Transactional
    public UserResponse setPhoto(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty() || !PHOTO_TYPES.contains(String.valueOf(file.getContentType()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upload a JPG, PNG or WebP image");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo must be under 2 MB");
        }
        User user = load(userId);
        String old = user.getProfilePicUrl();
        user.setProfilePicUrl(PHOTO_PATH + storageService.store(file).storedFileName());
        deleteStoredPhoto(old);
        return saveAndBroadcast(user);
    }

    @Transactional
    public UserResponse removePhoto(Long userId) {
        User user = load(userId);
        deleteStoredPhoto(user.getProfilePicUrl());
        user.setProfilePicUrl(null);
        return saveAndBroadcast(user);
    }

    /** Only files that are someone's current profile photo are served (uploads share one folder). */
    public Resource loadPhoto(String fileName) {
        if (!fileName.matches("[A-Za-z0-9-]+\\.(jpg|jpeg|png|webp)") || !userRepository.existsByProfilePicUrl(PHOTO_PATH + fileName)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Photo not found");
        }
        return storageService.load(fileName);
    }

    /** People edit their own job title; department and manager are set by admins. */
    @Transactional
    public UserResponse updateOwnProfile(Long userId, String jobTitle) {
        User user = load(userId);
        user.setJobTitle(clean(jobTitle, 100));
        return saveAndBroadcast(user);
    }

    @Transactional
    public UserResponse adminUpdateProfile(Long adminId, Long userId, String jobTitle, String department, Long managerId) {
        User user = load(userId);
        if (managerId != null) {
            if (managerId.equals(userId)) throw new ApiException(HttpStatus.BAD_REQUEST, "Someone can't report to themselves");
            load(managerId);
            // Walk up from the new manager; reaching this user again would make a loop in the org chart.
            Set<Long> seen = new HashSet<>();
            for (Long m = managerId; m != null && seen.add(m); m = userRepository.findById(m).map(User::getManagerId).orElse(null)) {
                if (m.equals(userId)) throw new ApiException(HttpStatus.BAD_REQUEST, "That would make a reporting loop");
            }
        }
        user.setJobTitle(clean(jobTitle, 100));
        user.setDepartment(clean(department, 100));
        user.setManagerId(managerId);
        auditService.log(adminId, "USER_PROFILE_CHANGED", "USER", userId,
                "Title: " + user.getJobTitle() + ", department: " + user.getDepartment() + ", manager: " + managerId);
        return saveAndBroadcast(user);
    }

    private UserResponse saveAndBroadcast(User user) {
        UserResponse view = UserResponse.from(userRepository.save(user));
        // Everyone's directory/avatars update live (broadcast, so a provider's email is withheld).
        messagingTemplate.convertAndSend("/topic/user.updated", view.visibleTo(false, null));
        return view;
    }

    private void deleteStoredPhoto(String url) {
        if (url != null && url.startsWith(PHOTO_PATH)) storageService.delete(url.substring(PHOTO_PATH.length()));
    }

    private User load(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
