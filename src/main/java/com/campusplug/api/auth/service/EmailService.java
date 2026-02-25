package com.campusplug.api.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.email.from:noreply@campusplug.local}")
    private String from;

    @Value("${app.email.enabled:false}")
    private boolean enabled;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Sends a 6-digit OTP to the user's email for login verification.
     * Runs asynchronously so it never blocks the HTTP response.
     */
    @Async
    public void sendOtpEmail(String toEmail, String otp) {
        if (!enabled) {
            log.info("[EmailService] Email disabled — OTP for {}: {}", toEmail, otp);
            return;
        }

        String body = """
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: auto; padding: 20px;">
                  <h2 style="color: #2C3E50;">CampusPlug — Login Verification</h2>
                  <p>Use the code below to complete your login. It expires in <strong>5 minutes</strong>.</p>
                  <div style="text-align: center; margin: 32px 0;">
                    <span style="font-size: 42px; font-weight: bold; letter-spacing: 12px;
                                 color: #27AE60; background: #F0FFF4; padding: 16px 24px;
                                 border-radius: 8px; display: inline-block;">%s</span>
                  </div>
                  <p style="color: #7F8C8D; font-size: 13px;">
                    If you did not attempt to log in, someone may have your password.
                    Change it immediately.
                  </p>
                  <hr style="border: none; border-top: 1px solid #ECF0F1; margin-top: 32px;">
                  <p style="color: #BDC3C7; font-size: 11px;">CampusPlug · Mbarara University of Science and Technology</p>
                </body>
                </html>
                """.formatted(otp);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject("CampusPlug — Your login code: " + otp);
            helper.setText(body, true);
            mailSender.send(message);
            log.info("[EmailService] OTP email sent to {}", toEmail);
        } catch (MessagingException e) {
            log.error("[EmailService] Failed to send OTP email to {}: {}", toEmail, e.getMessage());
        }
    }

    /**
     * Sends a password reset email containing both a direct link and the raw token.
     * Runs asynchronously so it never blocks the HTTP response.
     */
    @Async
    public void sendPasswordResetEmail(String toEmail, String resetToken, String frontendBaseUrl) {
        if (!enabled) {
            log.info("[EmailService] Email disabled — reset token for {}: {}", toEmail, resetToken);
            return;
        }

        String resetLink = frontendBaseUrl + "/reset-password?token=" + resetToken;

        String body = """
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: auto; padding: 20px;">
                  <h2 style="color: #2C3E50;">CampusPlug — Password Reset</h2>
                  <p>You requested a password reset. Click the button below to choose a new password:</p>
                  <p style="text-align: center; margin: 32px 0;">
                    <a href="%s"
                       style="background-color: #27AE60; color: white; padding: 14px 28px;
                              text-decoration: none; border-radius: 6px; font-size: 16px;">
                      Reset My Password
                    </a>
                  </p>
                  <p style="color: #7F8C8D; font-size: 13px;">
                    Or copy this link into your browser:<br>
                    <a href="%s" style="color: #2980B9;">%s</a>
                  </p>
                  <p style="color: #7F8C8D; font-size: 13px;">
                    This link expires in <strong>30 minutes</strong>.<br>
                    If you did not request a reset, you can safely ignore this email.
                  </p>
                  <hr style="border: none; border-top: 1px solid #ECF0F1; margin-top: 32px;">
                  <p style="color: #BDC3C7; font-size: 11px;">CampusPlug · Mbarara University of Science and Technology</p>
                </body>
                </html>
                """.formatted(resetLink, resetLink, resetLink);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject("CampusPlug — Reset your password");
            helper.setText(body, true); // true = HTML
            mailSender.send(message);
            log.info("[EmailService] Password reset email sent to {}", toEmail);
        } catch (MessagingException e) {
            // Log but do not rethrow — user still gets the generic "email sent" response
            log.error("[EmailService] Failed to send password reset email to {}: {}", toEmail, e.getMessage());
        }
    }
}
