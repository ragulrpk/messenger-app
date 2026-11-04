package com.ctd.demo.chat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** File-sharing limits; every backend replica must mount the same root directory.
 *  dailyQuota caps the bytes one person uploads or forwards in any 24 hours. */
@ConfigurationProperties("app.files")
public record ChatFileProperties(Path root, DataSize maxSize, DataSize videoMaxSize, Duration retention,
                                 Set<String> allowedExtensions, DataSize dailyQuota) { }
