/**
 * cart.js — Async cart operations for Akuma Threads.
 *
 * ES Module. Import via:
 *   <script type="module" src="/js/cart.js"></script>
 *
 * The CSRF token is read from <meta name="_csrf"> in the document head,
 * which Thymeleaf + Spring Security populate automatically.
 */

// ── CSRF ─────────────────────────────────────────────────────────────────────

/**
 * Reads the CSRF token value from the <meta name="_csrf"> tag injected by
 * Thymeleaf. Required on all state-mutating fetch calls.
 *
 * @returns {string} the token, or an empty string if the meta tag is absent
 */
export function getCsrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.getAttribute('content') : '';
}

/**
 * Reads the CSRF header name from <meta name="_csrf_header">.
 * Spring Security defaults to 'X-CSRF-TOKEN'.
 *
 * @returns {string}
 */
export function getCsrfHeader() {
    const meta = document.querySelector('meta[name="_csrf_header"]');
    return meta ? meta.getAttribute('content') : 'X-CSRF-TOKEN';
}

// ── Cart badge ────────────────────────────────────────────────────────────────

/**
 * Updates the cart count badge in the navbar.
 * Shows the badge when count > 0; hides it when count is 0.
 *
 * @param {number} count - total unit count from the server
 */
export function updateCartBadge(count) {
    const badge = document.getElementById('cart-count');
    if (!badge) return;
    if (count > 0) {
        badge.textContent = count > 99 ? '99+' : String(count);
        badge.classList.remove('hidden');
    } else {
        badge.textContent = '';
        badge.classList.add('hidden');
    }
}

// ── Toast notifications ───────────────────────────────────────────────────────

/**
 * Displays a fixed-position toast notification at the bottom-right of the screen.
 * Auto-removes itself after 3500 ms with a CSS transition fade-out.
 *
 * @param {string} message - text to display
 * @param {'success'|'error'} type - controls background colour
 */
export function showToast(message, type = 'success') {
    // Prevent stacking — remove any existing toast first
    const existing = document.getElementById('akuma-toast');
    if (existing) {
        existing.remove();
    }

    const toast = document.createElement('div');
    toast.id = 'akuma-toast';
    toast.setAttribute('role', 'status');
    toast.setAttribute('aria-live', 'polite');

    // Base Tailwind classes
    const base = [
        'fixed', 'bottom-6', 'right-6', 'z-50',
        'px-5', 'py-3', 'rounded-xl', 'shadow-2xl',
        'text-white', 'font-semibold', 'text-sm',
        'transition-all', 'duration-300',
        'opacity-0', 'translate-y-3'
    ];

    // Type-specific colour
    const colour = type === 'success'
        ? ['bg-green-600']
        : ['bg-red-600'];

    toast.classList.add(...base, ...colour);
    toast.textContent = message;
    document.body.appendChild(toast);

    // Trigger enter animation on next frame
    requestAnimationFrame(() => {
        requestAnimationFrame(() => {
            toast.classList.remove('opacity-0', 'translate-y-3');
            toast.classList.add('opacity-100', 'translate-y-0');
        });
    });

    // Schedule exit animation then removal
    const exitTimer = setTimeout(() => {
        toast.classList.remove('opacity-100', 'translate-y-0');
        toast.classList.add('opacity-0', 'translate-y-3');
        setTimeout(() => toast.remove(), 300);
    }, 3500);

    // Allow early dismissal on click
    toast.addEventListener('click', () => {
        clearTimeout(exitTimer);
        toast.remove();
    });
}

// ── Core cart actions ─────────────────────────────────────────────────────────

/**
 * Sends an add-to-cart request to the backend, then updates the badge and
 * shows a toast notification.
 *
 * @param {number} productId  - Product entity PK
 * @param {number} variantId  - ProductVariant entity PK (size/SKU)
 * @param {number} [quantity=1] - units to add
 */
export async function addToCart(productId, variantId, quantity = 1) {
    try {
        const response = await fetch('/api/cart/add', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                [getCsrfHeader()]: getCsrfToken()
            },
            body: JSON.stringify({ productId, variantId, quantity })
        });

        if (!response.ok) {
            const errorData = await response.json().catch(() => ({}));
            const msg = errorData.message || 'Could not add item. Please try again.';
            showToast(msg, 'error');
            return;
        }

        const data = await response.json();
        updateCartBadge(data.itemCount);
        showToast(data.message, 'success');

    } catch (networkError) {
        console.error('[cart.js] addToCart network error:', networkError);
        showToast('Could not add item. Please try again.', 'error');
    }
}

/**
 * Sends a remove-from-cart request, updates the badge, shows a toast,
 * and optionally removes the DOM element with a matching data attribute.
 *
 * @param {number} variantId - the variant (cart line key) to remove
 */
export async function removeFromCart(variantId) {
    try {
        const response = await fetch(`/api/cart/remove/${variantId}`, {
            method: 'DELETE',
            headers: {
                [getCsrfHeader()]: getCsrfToken()
            }
        });

        if (!response.ok) {
            showToast('Could not remove item. Please try again.', 'error');
            return;
        }

        const data = await response.json();
        updateCartBadge(data.itemCount);
        showToast(data.message, 'success');

        // Remove the cart row from the DOM if present
        const row = document.querySelector(`[data-variant-id="${variantId}"]`);
        if (row) {
            row.style.transition = 'opacity 0.3s';
            row.style.opacity   = '0';
            setTimeout(() => row.remove(), 300);
        }

    } catch (networkError) {
        console.error('[cart.js] removeFromCart network error:', networkError);
        showToast('Could not remove item. Please try again.', 'error');
    }
}

// ── Init: populate badge on page load ─────────────────────────────────────────

/**
 * Fetches the cart count on DOMContentLoaded so the navbar badge is accurate
 * immediately, even without any user interaction.
 * Fails silently — a missing badge is acceptable; a broken page is not.
 */
(async function initCartBadge() {
    try {
        const response = await fetch('/api/cart/count');
        if (response.ok) {
            const data = await response.json();
            updateCartBadge(data.count);
        }
    } catch {
        // Non-critical path — silently ignore network errors on badge init
    }
})();

/*
 * ── Thymeleaf product card snippet ────────────────────────────────────────────
 *
 * Include the following in any product card template to wire up the add-to-cart
 * button. Replace th:attr expressions with the actual Thymeleaf field names.
 *
 * In the page <head> (via fragments/layout :: head):
 *   <meta name="_csrf"        th:content="${_csrf.token}"/>
 *   <meta name="_csrf_header" th:content="${_csrf.headerName}"/>
 *   <script type="module" src="/js/cart.js"></script>
 *
 * Add-to-cart button:
 *   <button type="button"
 *           th:attr="onclick='addToCart(' + ${product.id} + ',' + ${product.defaultVariantId} + ',1)'"
 *           aria-label="Add to cart">
 *       Add to Cart
 *   </button>
 *
 * Note: because cart.js is a module, addToCart is not on window by default.
 * Expose it with:  window.addToCart = addToCart;  at the bottom of this file
 * so inline onclick handlers can reach it.
 */

// Expose module functions on window so Thymeleaf onclick attributes can call them
window.addToCart     = addToCart;
window.removeFromCart = removeFromCart;
window.showToast      = showToast;
