package org.spring.shopuser.entity;

import lombok.Getter;

@Getter
public class AddressEntity {
    private Long id;
    private final long userId;
    private final String receiver;
    private final String phone;
    private final String regionCode;
    private final String detail;
    private final boolean defaultAddress;

    public AddressEntity(long userId, String receiver, String phone, String regionCode, String detail, boolean defaultAddress) {
        this.userId = userId;
        this.receiver = receiver;
        this.phone = phone;
        this.regionCode = regionCode;
        this.detail = detail;
        this.defaultAddress = defaultAddress;
    }

    /** MyBatis writes the generated database key back after INSERT. */
    public void setId(Long id) {
        this.id = id;
    }
}
