package com.alexandria.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.util.Set;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        Path root,
        DataSize maxFileSize,
        Set<String> allowedContentTypes
) {
}
