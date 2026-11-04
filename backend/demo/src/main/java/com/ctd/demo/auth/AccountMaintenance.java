package com.ctd.demo.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: drops one-time codes older than a day and sign-in history older than 180 days. */
@Component
public class AccountMaintenance {
    private static final Logger log = LoggerFactory.getLogger(AccountMaintenance.class);
    private final OneTimeCodes codes;
    private final SignInEvents signIns;

    public AccountMaintenance(OneTimeCodes codes, SignInEvents signIns) {
        this.codes = codes;
        this.signIns = signIns;
    }

    @Scheduled(initialDelay = 600_000, fixedDelay = 3_600_000)
    public void prune() {
        try {
            codes.prune();
            signIns.prune();
        } catch (RuntimeException exception) {
            log.warn("Account maintenance failed; it will run again in an hour", exception);
        }
    }
}
