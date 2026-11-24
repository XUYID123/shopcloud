package org.spring.shopuser.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AddressVO(String addressId, String receiver, String phone, String regionCode, String detail,
                        @JsonProperty("isDefault") boolean isDefault) { }
