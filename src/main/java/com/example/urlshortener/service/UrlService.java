package com.example.urlshortener.service;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.repository.UrlRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

@Service
public class UrlService {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int CODE_LENGTH = 7;
    private final UrlRepository urlRepository;
    private final SecureRandom random = new SecureRandom();

    public UrlService(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    public ShortUrl createShortUrl(String orginalUrl)   {
        String code = generateUniqueCode();
        ShortUrl shortUrl = new ShortUrl(code, originalUrl);
        return urlRepository.save(shortUrl);
    }

    private String generateUniqueCode() {
        String code;
        int attemps = 0;
        do {
            code = randomCode();
            attemps++;
            if (attemps > 5)  {
                throw new IllegalStateException("Could not generate unique code after 5 attempts");
            }   while (urlRepository.existsByShortCode(code));
            return code;
        }

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