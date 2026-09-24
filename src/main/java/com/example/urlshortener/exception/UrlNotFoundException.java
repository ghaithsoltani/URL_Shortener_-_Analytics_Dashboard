package com.example.urlshortener.exception;


public class UrlNotFoundException extends RuntimeException {
    // leans it is an unchecked exception and your don't need to add : throws UrlNotFoundException
    public UrlNotFoundException(String code) {
        super("No URL found for code: " + code);
    }
}