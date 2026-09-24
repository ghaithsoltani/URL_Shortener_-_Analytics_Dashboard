package com.example.urlshortener.controller;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.dto.UrlResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.service.UrlService;
import com.example.urlshortener.dto.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

// REST controller responsible for URL shortening endpoints
@RestController

// Base path for all endpoints in this controller
@RequestMapping
public class UrlController {

    // Business logic layer
    private final UrlService urlService;

    // Reads the application base URL from application.properties
    @Value("${app.base-url}")
    private String baseUrl;

    // Constructor injection of UrlService
    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    // Creates a new short URL
    @PostMapping("/api/urls")
    public ResponseEntity<UrlResponse> create(

            // Validates request body before processing
            @Valid @RequestBody CreateUrlRequest request) {

        // Generate and save short URL
        ShortUrl created = urlService.createShortUrl(request.getOriginalUrl());

        // Return HTTP 201 Created with URL details
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UrlResponse(created, baseUrl));
    }

    // Returns all shortened URLs
    @GetMapping("/api/urls")
    public List<UrlResponse> listAll() {

        // Convert entities into response DTOs
        return urlService.listAll().stream()
                .map(u -> new UrlResponse(u, baseUrl))
                .toList();
    }

    // Redirects a short code to its original URL
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(

            // Extracts shortCode from URL path
            @PathVariable String shortCode) {

        // Find URL associated with the short code
        ShortUrl shortUrl = urlService.resolve(shortCode);

        // Return HTTP 302 Found with Location header
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(shortUrl.getOriginalUrl()))
                .build();
    }
}