package com.example.urlshortener.dto;


import lombok.Data;

import java.time.Instant;
@Data
public class UrlResponse {
    private String shortCode;
    private String shortUrl;
    private String originalUrl;
    private Instant createdAt;


}