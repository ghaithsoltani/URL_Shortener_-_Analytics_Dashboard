package com.example.urlshortener.dto;


import com.example.urlshortener.entity.ShortUrl;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
public class UrlResponse {
    private String shortCode;
    private String shortUrl;
    private String originalUrl;
    private Instant createdAt;

    public UrlResponse(ShortUrl entity, String baseUrl) {
        this.shortCode = entity.getShortCode();
        this.shortUrl = baseUrl + entity.getShortCode();
        this.originalUrl = entity.getOriginalUrl();
        this.createdAt = entity.getCreatedAt();
    }

    public String getShortCode() { return shortCode; }
    public String getShortUrl() { return shortUrl; }
    public String getOriginalUrl() { return originalUrl; }
    public Instant getCreatedAt() { return createdAt; }
}