package com.officechat.office_chat_backend.user;

public enum Role {
    ADMIN,
    EMPLOYEE,
    /** External doctor/provider (client): talks only with company staff. */
    PROVIDER
}
