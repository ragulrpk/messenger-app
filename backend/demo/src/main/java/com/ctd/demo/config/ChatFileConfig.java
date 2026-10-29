package com.ctd.demo.config;

import com.ctd.demo.chat.ChatFileProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Binds file-sharing settings and runs the file retention schedule. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ChatFileProperties.class)
class ChatFileConfig { }
