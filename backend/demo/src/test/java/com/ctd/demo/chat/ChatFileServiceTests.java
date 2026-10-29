package com.ctd.demo.chat;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatFileServiceTests {
    @Test
    void cleanNameKeepsOnlyASafeFinalSegment() {
        assertThat(ChatFileService.cleanName("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(ChatFileService.cleanName("C:\\Users\\me\\report.pdf")).isEqualTo("report.pdf");
        assertThat(ChatFileService.cleanName("bad\u0000\"name\n.txt")).isEqualTo("badname.txt");
        String longName = ChatFileService.cleanName("a".repeat(300) + ".xlsx");
        assertThat(longName).hasSize(255).endsWith(".xlsx");
        for (String missing : new String[] { null, "", "folder/", "..", " " })
            assertThatThrownBy(() -> ChatFileService.cleanName(missing)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void signaturesAreCheckedForRenderedFormatsOnly() {
        assertThat(ChatFileService.signatureMatches("jpg", new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0 })).isTrue();
        assertThat(ChatFileService.signatureMatches("png", "GIF89a".getBytes())).isFalse();
        assertThat(ChatFileService.signatureMatches("webp", "RIFF\0\0\0\0WEBPVP8 ".getBytes())).isTrue();
        assertThat(ChatFileService.signatureMatches("pdf", "<html>".getBytes())).isFalse();
        assertThat(ChatFileService.signatureMatches("docx", new byte[0])).isTrue();
    }
}
