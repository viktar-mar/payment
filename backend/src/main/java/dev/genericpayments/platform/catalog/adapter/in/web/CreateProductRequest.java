package dev.genericpayments.platform.catalog.adapter.in.web;
import jakarta.validation.constraints.*;
public record CreateProductRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9-]{2,62}") String key,
                                   @NotBlank @Size(max = 120) String name, @Size(max = 1000) String description) {}
