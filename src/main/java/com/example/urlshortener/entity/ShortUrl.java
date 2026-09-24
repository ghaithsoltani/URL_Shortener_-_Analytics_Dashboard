package com.example.urlshortener.entity;

import jakarta.persistence.*;
import jdk.jfr.DataAmount;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class ShortUrl {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String shortCode;       // base62 encoded, e.g. "aZskQ1"

    @Column(nullable = false, length = 2048)
    private String originalUrl;

    private Instant createdAt;
    private Instant expiresAt;      // nullable - stretch goal TTL


    public ShortUrl(String code, String originalUrl) {
    }
}