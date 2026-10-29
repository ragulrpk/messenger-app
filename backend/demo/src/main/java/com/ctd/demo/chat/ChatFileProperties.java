package com.ctd.demo.chat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** File-sharing limits; every backend replica must mount the same root directory. */
@ConfigurationProperties("app.files")
public record ChatFileProperties(Path root, DataSize maxSize, Duration retention, Set<String> allowedExtensions) { }
