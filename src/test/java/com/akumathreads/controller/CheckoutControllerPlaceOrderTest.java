package com.akumathreads.controller;

import com.akumathreads.dto.CheckoutForm;
import com.akumathreads.repository.ProductVariantRepository;
import com.akumathreads.service.DiscountCodeService;
import com.akumathreads.service.OrderService;
import com.akumathreads.service.StripeService;
import com.akumathreads.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the defensive paths of {@code POST /checkout} in
 * {@link CheckoutController#placeOrder}.
 *
 * <p>The happy path is guarded by Stripe's own PI verification; what must be
 * proven here is that the two failure paths fail <em>safely</em>:
 *
 * <ul>
 *   <li><b>PI mismatch</b> — the PaymentIntent id posted by the browser does
 *       not match the one bound to this session (tampered form, replayed
 *       submission, or expired session). The pending order must be cancelled so
 *       its reserved stock returns to the pool, the pending session state must
 *       be cleared, and the user must land back on checkout with an error —
 *       never on a confirmation page.</li>
 *   <li><b>No pending order in session</b> — a stray POST with no checkout in
 *       flight must bounce to the shop without touching any service.</li>
 * </ul>
 */
class CheckoutControllerPlaceOrderTest {

    private OrderService             orderService;
    private UserService              userService;
    private DiscountCodeService      discountCodeService;
    private StripeService            stripeService;
    private ProductVariantRepository variantRepository;
    private HttpSession              session;
    private CheckoutController       controller;
    private UserDetails              principal;

    @BeforeEach
    void setUp() {
        orderService        = mock(OrderService.class);
        userService         = mock(UserService.class);
        discountCodeService = mock(DiscountCodeService.class);
        stripeService       = mock(StripeService.class);
        variantRepository   = mock(ProductVariantRepository.class);
        session             = mock(HttpSession.class);
        principal           = mock(UserDetails.class);
        controller = new CheckoutController(
                orderService, userService, discountCodeService,
                stripeService, variantRepository);
    }

    @Test
    void placeOrder_paymentIntentMismatch_cancelsPendingOrderAndReturnsToCheckout() {
        when(session.getAttribute("pendingOrderId")).thenReturn(42L);
        when(session.getAttribute("pendingPaymentIntentId")).thenReturn("pi_session");
        when(stripeService.getPublishableKey()).thenReturn("pk_test_x");

        CheckoutForm form = new CheckoutForm();
        form.setPaymentIntentId("pi_TAMPERED");

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.placeOrder(form, principal, session, model);

        // Back to checkout with a visible error — never a confirmation redirect
        assertEquals("checkout", view);
        assertNotNull(model.getAttribute("paymentError"));

        // Reserved stock is released via cancellation of the pending order
        verify(orderService).cancelOrder(42L);

        // Session pending-state is fully cleared so a retry starts clean
        verify(session).removeAttribute("pendingOrderId");
        verify(session).removeAttribute("pendingPaymentIntentId");
        verify(session).removeAttribute("pendingCouponCode");

        // Nothing was verified with Stripe and no order was marked paid
        verify(orderService, never()).markOrderPaid(anyLong());
        verifyNoInteractions(userService);
    }

    @Test
    void placeOrder_missingPaymentIntentInForm_treatedAsMismatch() {
        when(session.getAttribute("pendingOrderId")).thenReturn(42L);
        when(session.getAttribute("pendingPaymentIntentId")).thenReturn("pi_session");
        when(stripeService.getPublishableKey()).thenReturn("pk_test_x");

        CheckoutForm form = new CheckoutForm(); // paymentIntentId == null

        String view = controller.placeOrder(
                form, principal, session, new ExtendedModelMap());

        assertEquals("checkout", view);
        verify(orderService).cancelOrder(42L);
        verify(orderService, never()).markOrderPaid(anyLong());
    }

    @Test
    void placeOrder_noPendingOrderInSession_redirectsToShopWithoutSideEffects() {
        when(session.getAttribute("pendingOrderId")).thenReturn(null);

        String view = controller.placeOrder(
                new CheckoutForm(), principal, session, new ExtendedModelMap());

        assertEquals("redirect:/shop", view);
        verifyNoInteractions(orderService, userService,
                             discountCodeService, stripeService, variantRepository);
    }
}
