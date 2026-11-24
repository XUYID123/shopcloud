package org.spring.shopuser.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Validated
@ConfigurationProperties("shop.address")
public record AddressProperties(
        @NotEmpty Map<@Pattern(regexp = "[0-9]{6}") String, @NotBlank String> regions) { }
