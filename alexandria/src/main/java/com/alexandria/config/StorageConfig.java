package com.alexandria.config;

import com.alexandria.storage.FileStorageService;
import com.alexandria.storage.LocalFileStorageService;
import com.alexandria.storage.StorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public FileStorageService fileStorageService(StorageProperties storageProperties) {
        return new LocalFileStorageService(storageProperties);
    }
}
