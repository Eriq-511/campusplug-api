package com.campusplug.api.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.email.from:noreply@campusplug.app}")
    private String from;

    @Value("${app.email.enabled:false}")
    private boolean enabled;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.port:0}")
    private int mailPort;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @PostConstruct
    void logConfig() {
        int pwLen = 0;
        if (mailSender instanceof JavaMailSenderImpl impl) {
            String pw = impl.getPassword();
            pwLen = (pw == null) ? 0 : pw.length();
        }
        log.info("[EmailService] enabled={} host={} port={} username='{}' password-length={}",
            enabled, safe(mailHost), mailPort, safe(mailUsername), pwLen);
        if (enabled && (mailUsername == null || mailUsername.isBlank())) {
            log.error("[EmailService] SMTP_USER is blank — emails will NOT be sent.");
        }
        if (enabled && pwLen == 0) {
            log.error("[EmailService] SMTP_PASSWORD is blank — emails will NOT be sent.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void sendPlainTextEmail(String toEmail, String subject, String body, String logPrefix) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            log.info("[EmailService] {} {}", logPrefix, toEmail);
        } catch (RuntimeException e) {
            log.error("[EmailService][SMTP FAILURE] Could not send to {} via {}:{} user='{}': {}",
                toEmail, safe(mailHost), mailPort, safe(mailUsername), e.getMessage(), e);
            throw e;
        }
    }

    public void sendOtpEmail(String toEmail, String otp) {
        if (!enabled) {
            log.warn("[EmailService] Email disabled — OTP for {}: {}", toEmail, otp);
            return;
        }
        String body = """
                Tukwataganee login verification code

                Your 5-digit code is: %s
                It expires in 5 minutes.

                If you did not attempt to log in, ignore this message.
                """.formatted(otp);
        sendPlainTextEmail(toEmail, "Tukwataganee login code: " + otp, body, "OTP email sent to");
    }

    public void sendPasswordResetOtpEmail(String toEmail, String otp) {
        if (!enabled) {
            log.warn("[EmailService] Email disabled — password reset OTP for {}: {}", toEmail, otp);
            return;
        }
        String body = """
                Tukwatagane password reset code

                Your 5-digit reset code is: %s
                It expires in 10 minutes.

                If you did not request a password reset, ignore this message.
                """.formatted(otp);
        sendPlainTextEmail(toEmail, "Tukwatagane password reset code: " + otp, body, "Password reset OTP email sent to");
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }
}

