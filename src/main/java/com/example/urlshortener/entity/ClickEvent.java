package com.example.urlshortener.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.*;

import java.time.Instant;


public class ClickEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String shortCode;
    private Instant clickedAt;
    private String referrer;
    private String userAgent;
}