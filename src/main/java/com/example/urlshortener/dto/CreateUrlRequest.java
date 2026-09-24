package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.URL;
@Data
public class CreateUrlRequest {
    // URL cannot be null, emply, or contain only spaces
    @NotBlank
    // Validates that the value is a properly formatted URL
    @URL
    private String orignalUrl;
}