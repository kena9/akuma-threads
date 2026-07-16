/**
 * product-detail.js — all interactive logic for /product/{id}
 *
 * Loaded as a plain (non-module) script so every function is global and
 * can be referenced from onclick="…" attributes in the HTML.
 */

var selectedVariantId = null, selectedSize = null, qty = 1;

function selectSize(btn) {
  document.querySelectorAll('.size-btn').forEach(function(b) { b.classList.remove('selected'); });
  btn.classList.add('selected');
  selectedVariantId = btn.dataset.variantId;
  selectedSize = btn.dataset.size;
  document.getElementById('selected-size-label').textContent = selectedSize;
  document.getElementById('size-error-msg').classList.add('hidden');
  var stock = parseInt(btn.dataset.stock);
  var msg = document.getElementById('low-stock-msg');
  if (stock > 0 && stock <= 5) {
    msg.textContent = 'ONLY ' + stock + ' LEFT IN THIS SIZE';
    msg.classList.remove('hidden');
  } else {
    msg.classList.add('hidden');
  }
}

function adjustQty(d) {
  qty = Math.max(1, Math.min(10, qty + d));
  document.getElementById('qty-display').textContent = qty;
}

function getCsrf() {
  var c = document.cookie.split(';').map(function(s) { return s.trim(); })
    .find(function(s) { return s.startsWith('XSRF-TOKEN='); });
  return c ? decodeURIComponent(c.split('=')[1]) : '';
}

function handleAddToCart() {
  if (!selectedVariantId) {
    document.getElementById('size-error-msg').classList.remove('hidden');
    return;
  }
  var btn = document.getElementById('add-to-cart-btn');
  btn.disabled = true;
  btn.textContent = 'ADDING...';
  // window.addToCart is exported by cart.js — posts to /api/cart/add,
  // updates the navbar badge via #cart-count, and fires the toast.
  window.addToCart(btn.dataset.productId, selectedVariantId, qty)
    .then(function() {
      btn.textContent = 'ADDED';
      btn.classList.add('cart-pulse');
      setTimeout(function() {
        btn.textContent = 'ADD TO CART';
        btn.disabled = false;
        btn.classList.remove('cart-pulse');
      }, 2000);
    })
    .catch(function() {
      btn.textContent = 'TRY AGAIN';
      btn.disabled = false;
    });
}

function openLightbox() {
  var src = document.getElementById('main-product-img');
  if (!src) return;
  document.getElementById('lightbox-img').src = src.src;
  document.getElementById('lightbox').classList.add('open');
  document.body.style.overflow = 'hidden';
}

function closeLightbox() {
  document.getElementById('lightbox').classList.remove('open');
  document.body.style.overflow = '';
}

function switchThumb(btn) {
  var img = btn.querySelector('img');
  if (img) document.getElementById('main-product-img').src = img.src;
}

function openSizeGuide() {
  document.getElementById('size-guide-modal').classList.add('open');
}

function closeSizeGuide() {
  document.getElementById('size-guide-modal').classList.remove('open');
}

document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape') {
    closeLightbox();
    closeSizeGuide();
  }
});

// ── Drop countdown ────────────────────────────────────────────────────────
(function() {
  var wrap = document.getElementById('pd-countdown-wrap');
  if (!wrap) return;
  var target = parseInt(wrap.dataset.dropMs, 10);
  var dEl = document.getElementById('pd-days');
  var hEl = document.getElementById('pd-hours');
  var mEl = document.getElementById('pd-mins');
  var sEl = document.getElementById('pd-secs');

  function pad(n) { return String(n).padStart(2, '0'); }

  function tick() {
    var diff = target - Date.now();
    if (diff <= 0) { location.reload(); return; }
    dEl.textContent = pad(Math.floor(diff / 86400000));
    hEl.textContent = pad(Math.floor((diff % 86400000) / 3600000));
    mEl.textContent = pad(Math.floor((diff % 3600000) / 60000));
    sEl.textContent = pad(Math.floor((diff % 60000) / 1000));
  }

  tick();
  setInterval(tick, 1000);
})();

// ── Notify me (pre-drop) ──────────────────────────────────────────────────
function subscribeNotify() {
  var email = document.getElementById('notify-email').value.trim();
  var fb = document.getElementById('notify-feedback');
  if (!email) {
    fb.textContent = 'Please enter your email.';
    fb.classList.remove('hidden');
    return;
  }
  fetch('/subscribe', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'X-XSRF-TOKEN': getCsrf()
    },
    body: new URLSearchParams({ email: email })
  })
    .then(function(r) { return r.json(); })
    .then(function(data) {
      fb.textContent = data.code
        ? 'Subscribed! Welcome code: ' + data.code + '. We\'ll notify you when this drops.'
        : 'You\'re already subscribed. We\'ll notify you when this drops.';
      fb.classList.remove('hidden');
      document.getElementById('notify-email').value = '';
    })
    .catch(function() {
      fb.textContent = 'Something went wrong. Try again.';
      fb.classList.remove('hidden');
    });
}
