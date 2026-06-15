/**
 * cart.js — Async cart operations for Olly Threads.
 *
 * ES Module. Import via:
 *   <script type="module" src="/js/cart.js"></script>
 *
 * CSRF token is read from the XSRF-TOKEN cookie written by Spring Security's
 * CookieCsrfTokenRepository (httpOnly=false). This avoids the race condition
 * where HttpSessionCsrfTokenRepository hadn't yet initialised the token before
 * the first JS fetch. Falls back to the <meta name="_csrf"> tag if no cookie.
 */

// ── CSRF ─────────────────────────────────────────────────────────────────────

/**
 * Reads the CSRF token value.
 *
 * Primary: XSRF-TOKEN cookie (CookieCsrfTokenRepository, httpOnly=false).
 * Fallback: <meta name="_csrf"> tag (legacy belt-and-suspenders).
 *
 * @returns {string} the CSRF token
 */
export function getCsrfToken() {
    const cookie = document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='));
    if (cookie) return decodeURIComponent(cookie.split('=')[1]);
    const meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.getAttribute('content') : '';
}

/**
 * Returns the CSRF header name.
 *
 * CookieCsrfTokenRepository uses X-XSRF-TOKEN by convention.
 * Falls back to the meta tag value for legacy compatibility.
 *
 * @returns {string}
 */
export function getCsrfHeader() {
    const meta = document.querySelector('meta[name="_csrf_header"]');
    return (meta && meta.getAttribute('content')) || 'X-XSRF-TOKEN';
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
 * Displays a fixed-position brand toast at the bottom-right of the screen.
 *
 * Design: sharp corners, mono font, dark bg (#111) with a 2px left border
 * (green for success, crimson for error). Auto-removes after 3500 ms.
 *
 * @param {string} message - text to display
 * @param {'success'|'error'} type - controls accent border colour
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

    // Sharp mono notification — no rounded corners, left accent border
    const borderColor = type === 'success' ? '#22c55e' : '#e50914';
    Object.assign(toast.style, {
        position:       'fixed',
        bottom:         '1.5rem',
        right:          '1.5rem',
        zIndex:         '9999',
        background:     '#111',
        borderLeft:     `3px solid ${borderColor}`,
        color:          '#ffffff',
        fontFamily:     '"Courier New", "Lucida Console", monospace',
        fontSize:       '11px',
        fontWeight:     '700',
        letterSpacing:  '0.12em',
        textTransform:  'uppercase',
        padding:        '0.75rem 1.25rem',
        boxShadow:      '0 4px 24px rgba(0,0,0,0.6)',
        opacity:        '0',
        transform:      'translateY(8px)',
        transition:     'opacity 0.25s ease, transform 0.25s ease',
        cursor:         'pointer',
        maxWidth:       '320px',
        lineHeight:     '1.4'
    });

    toast.textContent = message;
    document.body.appendChild(toast);

    // Trigger enter animation on next frame
    requestAnimationFrame(() => {
        requestAnimationFrame(() => {
            toast.style.opacity   = '1';
            toast.style.transform = 'translateY(0)';
        });
    });

    // Schedule exit animation then removal
    const exitTimer = setTimeout(() => {
        toast.style.opacity   = '0';
        toast.style.transform = 'translateY(8px)';
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
 * Fetches the cart count on page load so the navbar badge is accurate
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

// Expose module functions on window so Thymeleaf onclick attributes can call them
window.addToCart      = addToCart;
window.removeFromCart = removeFromCart;
window.showToast      = showToast;
