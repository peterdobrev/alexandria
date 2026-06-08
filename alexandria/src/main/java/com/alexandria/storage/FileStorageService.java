package com.alexandria.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    StoredFile store(MultipartFile file);

    Resource load(String relativePath);

    void delete(String relativePath);
}
