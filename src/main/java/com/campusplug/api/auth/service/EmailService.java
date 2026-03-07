package com.campusplug.api.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.email.from:noreply@campusplug.local}")
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
      log.info("[EmailService] initialized");
    }

    public boolean isEnabled() {
      return enabled;
    }

    private void sendPlainTextEmail(String toEmail, String subject, String body, String successLogPrefix) {
      try {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        log.info("[EmailService] {} {}", successLogPrefix, toEmail);
      } catch (RuntimeException e) {
        log.error("[EmailService] Failed to send email to {} via SMTP {}:{} user='{}': {}",
            toEmail, safe(mailHost), mailPort, safe(mailUsername), e.getMessage(), e);
      }
    }

    /**
     * Sends a 5-digit OTP to the user's email for login verification.
     * Runs asynchronously so it never blocks the HTTP response.
     */
    @Async
    public void sendOtpEmail(String toEmail, String otp) {
        if (!enabled) {
        log.warn("[EmailService] Email disabled (app.email.enabled=false) — OTP for {}: {}", toEmail, otp);
        log.warn(
            "[EmailService] To enable delivery, set APP_EMAIL_ENABLED=true and configure SMTP. SMTP currently: {}:{} user='{}'",
            safe(mailHost), mailPort, safe(mailUsername)
        );
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

    /**
     * Sends a 5-digit password-reset OTP to the user's email.
     * Runs asynchronously so it never blocks the HTTP response.
     */
    @Async
    public void sendPasswordResetOtpEmail(String toEmail, String otp) {
        if (!enabled) {
        log.warn("[EmailService] Email disabled (app.email.enabled=false) — password reset OTP for {}: {}", toEmail, otp);
        log.warn(
            "[EmailService] To enable delivery, set APP_EMAIL_ENABLED=true and configure SMTP. SMTP currently: {}:{} user='{}'",
            safe(mailHost), mailPort, safe(mailUsername)
        );
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
