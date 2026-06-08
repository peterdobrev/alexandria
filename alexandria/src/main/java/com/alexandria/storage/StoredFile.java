package com.alexandria.storage;

public record StoredFile(
        String relativePath,
        String originalFilename,
        String contentType,
        long sizeBytes
) {
}
