package com.officechat.office_chat_backend.settings;

import jakarta.persistence.*;
import lombok.*;

/** One company-wide setting an admin can change at runtime (key -> value). */
@Entity
@Table(name = "app_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 64)
    private String key;

    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String value;
}
