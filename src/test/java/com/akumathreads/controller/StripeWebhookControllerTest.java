package com.akumathreads.controller;

import com.akumathreads.service.DiscountCodeService;
import com.akumathreads.service.EmailService;
import com.akumathreads.service.OrderService;
import com.akumathreads.service.PrintfulService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests the security boundary of {@link StripeWebhookController}.
 *
 * <p>This endpoint is CSRF-exempt and unauthenticated — the HMAC signature is
 * the ONLY thing standing between the internet and "mark this order paid".
 * These tests prove that boundary holds:
 *
 * <ul>
 *   <li>A forged/invalid signature is rejected with 400 and causes zero side
 *       effects — no order transitions, no emails, no Printful pushes.</li>
 *   <li>A correctly signed event is accepted (200), verified by computing a
 *       real Stripe-format signature with the JDK's HMAC-SHA256 — the same
 *       scheme Stripe documents: sign {@code "<timestamp>.<payload>"} and send
 *       {@code Stripe-Signature: t=<timestamp>,v1=<hex>}.</li>
 *   <li>An unconfigured secret short-circuits to 200 without processing
 *       anything (local-dev behavior), never treating events as trusted.</li>
 * </ul>
 */
class StripeWebhookControllerTest {

    private static final String SECRET = "whsec_test_secret_for_unit_tests";

    private OrderService        orderService;
    private DiscountCodeService discountCodeService;
    private EmailService        emailService;
    private PrintfulService     printfulService;
    private StripeWebhookController controller;

    @BeforeEach
    void setUp() {
        orderService        = mock(OrderService.class);
        discountCodeService = mock(DiscountCodeService.class);
        emailService        = mock(EmailService.class);
        printfulService     = mock(PrintfulService.class);
        controller = new StripeWebhookController(
                orderService, discountCodeService, emailService, printfulService);
        ReflectionTestUtils.setField(controller, "webhookSecret", SECRET);
    }

    /** Computes a valid Stripe-Signature header for the given payload + secret. */
    private static String sign(String payload, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String v1 = HexFormat.of().formatHex(
                mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + v1;
    }

    /** Minimal well-formed Stripe event JSON of an unhandled type. */
    private static String eventJson() {
        return """
               {"id":"evt_test_1","object":"event","api_version":"2020-08-27",
                "type":"charge.updated","data":{"object":{}}}""";
    }

    @Test
    void invalidSignature_rejectedWith400_andNothingProcessed() {
        ResponseEntity<String> response = controller.handleWebhook(
                eventJson(),
                "t=" + Instant.now().getEpochSecond() + ",v1=deadbeefdeadbeef");

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(orderService, discountCodeService,
                             emailService, printfulService);
    }

    @Test
    void signatureFromWrongSecret_rejectedWith400() throws Exception {
        // Attacker knows the payload format but not the signing secret.
        String forged = sign(eventJson(), "whsec_attacker_guess");

        ResponseEntity<String> response =
                controller.handleWebhook(eventJson(), forged);

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(orderService, discountCodeService,
                             emailService, printfulService);
    }

    @Test
    void validSignature_unhandledEventType_returns200WithoutSideEffects() throws Exception {
        String payload = eventJson();

        ResponseEntity<String> response =
                controller.handleWebhook(payload, sign(payload, SECRET));

        // 200 so Stripe does not retry unknown event types
        assertEquals(200, response.getStatusCode().value());
        verifyNoInteractions(orderService, discountCodeService,
                             emailService, printfulService);
    }

    @Test
    void unconfiguredSecret_returns200_butNeverTrustsTheEvent() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");

        ResponseEntity<String> response = controller.handleWebhook(
                eventJson(), "t=0,v1=whatever");

        assertEquals(200, response.getStatusCode().value());
        verifyNoInteractions(orderService, discountCodeService,
                             emailService, printfulService);
    }
}
