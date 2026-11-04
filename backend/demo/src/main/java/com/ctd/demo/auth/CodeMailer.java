package com.ctd.demo.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Emails one-time codes over SMTP (spring.mail.*). The code is in the body only, never in logs. */
@Component
public class CodeMailer {
    private final JavaMailSender mail;
    private final String from;
    private final String product;

    public CodeMailer(JavaMailSender mail, @Value("${app.mail.from}") String from,
            @Value("${app.mail.product-name:Let's Talk}") String product) {
        this.mail = mail;
        this.from = from;
        this.product = product;
    }

    /** Throws a MailException when the mail server refuses or cannot be reached. */
    public void send(String to, OneTimeCodes.Purpose purpose, String code) {
        String reason = switch (purpose) {
            case ADMIN_LOGIN -> "to finish signing in as an administrator";
            case FIRST_LOGIN -> "to set your password and finish your first sign-in";
            case PASSWORD_RESET -> "to reset your password";
        };
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(product + " verification code: " + code);
        message.setText("Your " + product + " verification code is " + code + ".\n\n"
                + "Enter it " + reason + ". It expires in 5 minutes and can be used once. Never share it.\n\n"
                + (purpose == OneTimeCodes.Purpose.PASSWORD_RESET
                        ? "If you did not ask to reset your password, ignore this email; your password has not changed."
                        : "If you did not just sign in, someone may know your password. Change it and tell your administrator."));
        mail.send(message);
    }
}
