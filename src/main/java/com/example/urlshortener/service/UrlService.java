package com.example.urlshortener.service;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.repository.UrlRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

@Service
public class UrlService {

    private static final int MAX_RETRIES = 5;
    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int CODE_LENGTH = 7;

    private final UrlRepository urlRepository;
    private final SecureRandom random = new SecureRandom();

    public UrlService(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    public ShortUrl createShortUrl(String originalUrl) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            String code = randomCode();
            ShortUrl shortUrl = new ShortUrl(code, originalUrl);
            try {
                return urlRepository.save(shortUrl);
            } catch (DataIntegrityViolationException e) {
                if (attempt == MAX_RETRIES) {
                    throw new IllegalStateException(
                            "Could not generate unique code after " + MAX_RETRIES + " attempts", e);
                }
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public ShortUrl resolve(String shortCode) {
        return urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
    }

    public List<ShortUrl> listAll() {
        return urlRepository.findAll();
    }
}