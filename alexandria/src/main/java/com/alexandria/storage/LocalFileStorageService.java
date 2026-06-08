package com.alexandria.storage;

import com.alexandria.exception.InvalidDocumentContentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileStorageService implements FileStorageService {

    private static final String EMPTY_EXTENSION = "";
    private static final String UNKNOWN_FILENAME = "unknown";

    private final StorageProperties storageProperties;

    @Override
    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentContentException("Uploaded file is empty");
        }

        long maxBytes = storageProperties.maxFileSize().toBytes();
        if (file.getSize() > maxBytes) {
            throw new MaxUploadSizeExceededException(maxBytes);
        }

        String contentType = file.getContentType();
        if (contentType == null || !storageProperties.allowedContentTypes().contains(contentType)) {
            throw new InvalidDocumentContentException(
                    "Unsupported content type: " + contentType);
        }

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String extension = extractExtension(originalFilename);

        String uuid = UUID.randomUUID().toString().replace("-", "");
        String shard1 = uuid.substring(0, 2);
        String shard2 = uuid.substring(2, 4);
        String filename = extension.isEmpty() ? uuid : uuid + "." + extension;
        String relativePath = shard1 + "/" + shard2 + "/" + filename;

        Path root = storageProperties.root().toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).normalize();

        if (!target.startsWith(root)) {
            throw new InvalidDocumentContentException("Resolved path escapes storage root");
        }

        try {
            Files.createDirectories(target.getParent());
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to write uploaded file to {}", target, e);
            throw new UncheckedIOException("Failed to store uploaded file", e);
        }

        log.info("Stored uploaded file: relativePath={}, size={} bytes, contentType={}",
                relativePath, file.getSize(), contentType);

        return new StoredFile(relativePath, originalFilename, contentType, file.getSize());
    }

    @Override
    public Resource load(String relativePath) {
        Path root = storageProperties.root().toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();

        if (!resolved.startsWith(root)) {
            throw new InvalidDocumentContentException("Resolved path escapes storage root");
        }

        return new FileSystemResource(resolved);
    }

    @Override
    public void delete(String relativePath) {
        Path root = storageProperties.root().toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();

        if (!resolved.startsWith(root)) {
            log.warn("Refusing to delete file outside storage root: {}", relativePath);
            return;
        }

        try {
            boolean deleted = Files.deleteIfExists(resolved);
            if (!deleted) {
                log.warn("File to delete did not exist: {}", relativePath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete file at {}: {}", relativePath, e.getMessage());
        }
    }

    private static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return UNKNOWN_FILENAME;
        }
        String name = original.replace("\\", "/");
        int slashIdx = name.lastIndexOf('/');
        if (slashIdx >= 0) {
            name = name.substring(slashIdx + 1);
        }
        // Strip control chars and replace anything outside a conservative whitelist with '_'.
        String sanitized = name.replaceAll("[\\p{Cntrl}]", "")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            return UNKNOWN_FILENAME;
        }
        return sanitized;
    }

    private static String extractExtension(String filename) {
        if (filename == null) {
            return EMPTY_EXTENSION;
        }
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == filename.length() - 1) {
            return EMPTY_EXTENSION;
        }
        String ext = filename.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
        if (!ext.matches("[a-z0-9]{1,16}")) {
            return EMPTY_EXTENSION;
        }
        return ext;
    }
}
