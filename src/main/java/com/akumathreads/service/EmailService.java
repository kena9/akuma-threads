package com.akumathreads.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

/**
 * Sends transactional and marketing emails via Gmail SMTP.
 *
 * <p>All methods are {@code @Async} so they never block the request thread.
 * Email failures are logged as warnings — they never propagate to the caller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    // ── Password reset ────────────────────────────────────────────────────────

    @Async
    public void sendPasswordResetEmail(String toEmail, String token) {
        String resetUrl = baseUrl + "/reset-password?token=" + token;
        String subject  = "Reset your Olly Threads password";
        String body = """
                <div style="font-family:monospace; max-width:520px; margin:40px auto; color:#111;">
                  <h2 style="font-size:1.5rem; margin-bottom:8px;">PASSWORD RESET</h2>
                  <p style="color:#555; margin-bottom:24px;">
                    We received a request to reset the password for your Olly Threads account.
                    Click the button below — this link expires in <strong>1 hour</strong>.
                  </p>
                  <a href="%s"
                     style="display:inline-block; background:#e50914; color:#fff;
                            font-family:monospace; font-size:11px; letter-spacing:0.2em;
                            text-transform:uppercase; text-decoration:none;
                            padding:14px 32px; margin-bottom:24px;">
                    RESET MY PASSWORD
                  </a>
                  <p style="color:#888; font-size:11px;">
                    If you didn't request this, you can safely ignore this email.
                    Your password won't change.
                  </p>
                  <hr style="border:none; border-top:1px solid #eee; margin:24px 0;"/>
                  <p style="color:#aaa; font-size:10px;">
                    Or paste this link into your browser:<br/>
                    <a href="%s" style="color:#aaa;">%s</a>
                  </p>
                </div>
                """.formatted(resetUrl, resetUrl, resetUrl);

        send(toEmail, subject, body);
    }

    // ── Drop / deal blast ─────────────────────────────────────────────────────

    @Async
    public void sendDropAnnouncement(List<String> emails, String subject, String htmlBody) {
        for (String email : emails) {
            String unsubLink = baseUrl + "/unsubscribe?email=" + email;
            String fullBody  = htmlBody + """
                    <div style="font-family:monospace; font-size:10px; color:#aaa;
                                margin-top:40px; padding-top:16px; border-top:1px solid #eee;">
                      You're receiving this because you subscribed to Olly Threads drop alerts.<br/>
                      <a href="%s" style="color:#aaa;">Unsubscribe</a>
                    </div>
                    """.formatted(unsubLink);
            send(email, subject, fullBody);
        }
    }

    // ── Welcome email (10% code) ──────────────────────────────────────────────

    @Async
    public void sendWelcomeEmail(String toEmail, String discountCode) {
        String subject = "Your 10% off code — Olly Threads";
        String body = """
                <div style="font-family:monospace; max-width:520px; margin:40px auto; color:#111;">
                  <h2 style="font-size:1.5rem; margin-bottom:8px;">WELCOME TO OLLY THREADS</h2>
                  <p style="color:#555; margin-bottom:24px;">
                    Here's your exclusive discount code. Use it on your first order.
                  </p>
                  <div style="background:#0a0a0a; color:#fff; text-align:center;
                              padding:20px 40px; margin-bottom:24px; letter-spacing:0.3em;
                              font-size:1.4rem; font-weight:bold;">
                    %s
                  </div>
                  <a href="%s/shop"
                     style="display:inline-block; background:#e50914; color:#fff;
                            font-family:monospace; font-size:11px; letter-spacing:0.2em;
                            text-transform:uppercase; text-decoration:none; padding:14px 32px;">
                    SHOP NOW
                  </a>
                  <p style="color:#888; font-size:11px; margin-top:24px;">
                    Code valid on your first order only. Not combinable with other offers.
                  </p>
                </div>
                """.formatted(discountCode, baseUrl);

        send(toEmail, subject, body);
    }

    // ── Order confirmation ────────────────────────────────────────────────────

    @Async
    public void sendOrderConfirmation(String toEmail, String customerName,
                                      Long orderId, java.math.BigDecimal total,
                                      String couponCode, java.math.BigDecimal discountAmount) {
        String subject = "Order confirmed — Olly Threads #" + orderId;

        String discountRow = "";
        if (couponCode != null && !couponCode.isBlank() &&
                discountAmount != null && discountAmount.compareTo(java.math.BigDecimal.ZERO) > 0) {
            discountRow = """
                    <tr>
                      <td style="padding:6px 0; color:#555;">Discount (%s)</td>
                      <td style="padding:6px 0; text-align:right; color:#e50914;">
                        −$%s
                      </td>
                    </tr>
                    """.formatted(couponCode, String.format("%.2f", discountAmount));
        }

        String body = """
                <div style="font-family:monospace; max-width:560px; margin:40px auto; color:#111;">
                  <div style="background:#0a0a0a; padding:24px 32px; margin-bottom:24px;">
                    <h1 style="font-family:Impact,sans-serif; font-size:2rem; color:#fff;
                               letter-spacing:-0.02em; margin:0 0 4px;">OLLY THREADS</h1>
                    <p style="color:#e50914; font-size:10px; letter-spacing:0.3em;
                               text-transform:uppercase; margin:0;">Order Confirmed</p>
                  </div>

                  <h2 style="font-size:1.1rem; margin-bottom:6px;">
                    Thanks, %s — you're all set.
                  </h2>
                  <p style="color:#555; font-size:13px; margin-bottom:24px;">
                    Your order <strong>#%d</strong> has been received and is being prepared for print.
                    Printful will send a separate shipping confirmation with tracking once your order ships.
                  </p>

                  <table style="width:100%%; border-top:2px solid #e50914;
                                 border-bottom:1px solid #eee; padding:12px 0; margin-bottom:24px;">
                    %s
                    <tr>
                      <td style="padding:12px 0 6px; font-weight:bold; letter-spacing:0.1em;
                                  text-transform:uppercase; font-size:12px;">
                        Order Total
                      </td>
                      <td style="padding:12px 0 6px; text-align:right;
                                  font-weight:bold; font-size:1.1rem;">
                        $%s
                      </td>
                    </tr>
                  </table>

                  <p style="color:#888; font-size:11px; margin-bottom:24px;">
                    Questions about your order? Reply to this email or reach out at
                    <a href="https://www.instagram.com/oliver_jin_wang"
                       style="color:#e50914;">@oliver_jin_wang</a> on Instagram.
                  </p>

                  <a href="%s/account/orders"
                     style="display:inline-block; background:#e50914; color:#fff;
                            font-family:monospace; font-size:11px; letter-spacing:0.2em;
                            text-transform:uppercase; text-decoration:none; padding:14px 32px;">
                    VIEW MY ORDER
                  </a>

                  <hr style="border:none; border-top:1px solid #eee; margin:32px 0 16px;"/>
                  <p style="color:#aaa; font-size:10px;">
                    Olly Threads · Original anime artist clothing by @oliver_jin_wang<br/>
                    Powered by Printful print-on-demand fulfillment.
                  </p>
                </div>
                """.formatted(HtmlUtils.htmlEscape(customerName == null ? "" : customerName),
                              orderId, discountRow,
                              String.format("%.2f", total), baseUrl);

        send(toEmail, subject, body);
    }

    // ── Abandoned cart recovery ───────────────────────────────────────────────

    /**
     * Sends an abandoned-cart recovery email with a 10% recovery code.
     * Lists up to 3 cart items so the email feels personal, not generic.
     */
    public void sendAbandonedCartEmail(String toEmail, String customerName,
                                       java.util.List<com.akumathreads.model.CartItem> items,
                                       String recoveryCode) {
        String subject = "You left something behind — Olly Threads";

        StringBuilder itemRows = new StringBuilder();
        int shown = 0;
        for (com.akumathreads.model.CartItem item : items) {
            if (shown >= 3) break;
            itemRows.append("""
                <tr>
                  <td style="padding:10px 0; border-bottom:1px solid #f0f0f0;">
                    <strong style="font-family:monospace; text-transform:uppercase; font-size:12px; letter-spacing:0.05em;">%s</strong>
                    <span style="color:#888; font-size:11px; display:block; margin-top:2px;">
                      Size %s &nbsp;·&nbsp; Qty %d
                    </span>
                  </td>
                </tr>
                """.formatted(
                    HtmlUtils.htmlEscape(item.getVariant().getProduct().getName()),
                    item.getVariant().getSize().name(),
                    item.getQuantity()));
            shown++;
        }
        if (items.size() > 3) {
            itemRows.append("""
                <tr><td style="padding:8px 0; color:#888; font-size:11px;">
                  + %d more item(s)
                </td></tr>
                """.formatted(items.size() - 3));
        }

        String body = """
                <div style="font-family:monospace; max-width:560px; margin:40px auto; color:#111;">
                  <div style="background:#0a0a0a; padding:24px 32px; margin-bottom:24px;">
                    <h1 style="font-family:Impact,sans-serif; font-size:2rem; color:#fff;
                               letter-spacing:-0.02em; margin:0 0 4px;">OLLY THREADS</h1>
                    <p style="color:#e50914; font-size:10px; letter-spacing:0.3em;
                               text-transform:uppercase; margin:0;">Original Anime Artist Clothing</p>
                  </div>

                  <h2 style="font-size:1.1rem; margin-bottom:6px;">
                    Hey %s — still thinking about it?
                  </h2>
                  <p style="color:#555; font-size:13px; margin-bottom:24px;">
                    You left some pieces in your cart. These are print-on-demand — no restocks once an edition is gone.
                  </p>

                  <table style="width:100%%; border-top:2px solid #e50914; margin-bottom:24px;">
                    %s
                  </table>

                  <div style="background:#fff8f8; border:1px solid #e50914;
                               padding:16px 20px; margin-bottom:24px;">
                    <p style="margin:0 0 6px; font-size:11px; letter-spacing:0.2em;
                               text-transform:uppercase; color:#e50914;">
                      YOUR RECOVERY CODE
                    </p>
                    <p style="font-family:Impact,sans-serif; font-size:1.8rem;
                               letter-spacing:0.1em; margin:0 0 6px; color:#111;">
                      %s
                    </p>
                    <p style="margin:0; font-size:11px; color:#888;">
                      10%% off your order — single-use, no expiry.
                    </p>
                  </div>

                  <a href="%s/checkout"
                     style="display:inline-block; background:#e50914; color:#fff;
                            font-family:monospace; font-size:11px; letter-spacing:0.2em;
                            text-transform:uppercase; text-decoration:none; padding:14px 32px;
                            margin-bottom:24px;">
                    COMPLETE MY ORDER
                  </a>

                  <hr style="border:none; border-top:1px solid #eee; margin:32px 0 16px;"/>
                  <p style="color:#aaa; font-size:10px;">
                    Olly Threads · Original anime artist clothing by @oliver_jin_wang<br/>
                    <a href="%s/unsubscribe?email=%s" style="color:#aaa;">Unsubscribe</a>
                  </p>
                </div>
                """.formatted(HtmlUtils.htmlEscape(customerName == null ? "" : customerName),
                              itemRows, recoveryCode, baseUrl, baseUrl,
                              java.net.URLEncoder.encode(toEmail, java.nio.charset.StandardCharsets.UTF_8));

        send(toEmail, subject, body);
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, "UTF-8");
            helper.setFrom(fromAddress, "Olly Threads");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(msg);
            log.info("[Email] Sent '{}' → {}", subject, to);
        } catch (Exception e) {
            log.warn("[Email] Failed to send '{}' → {}: {}", subject, to, e.getMessage());
        }
    }
}
