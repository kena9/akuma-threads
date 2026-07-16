/**
 * motion.js — Olly Threads global animation system
 *
 * Features:
 *  1. Page fade-in (every page loads smoothly)
 *  2. Scroll-reveal (elements animate in as they enter viewport)
 *  3. Custom cursor (red accent dot on desktop)
 *  4. Counter animation (numbers count up when visible)
 *  5. Magnetic buttons (subtle cursor-attraction on hover)
 *  6. Stagger children (lists animate in with cascading delay)
 *  7. Parallax sections (subtle depth on scroll)
 *  8. Scroll progress bar (thin red line at top of page)
 */

(function () {
    'use strict';

    // ── 0. MOTION PREFERENCES ─────────────────────────────────────────────────
    // Respect the OS-level "reduce motion" setting. When it's on, every effect
    // in this file either no-ops or resolves instantly. Vestibular-disorder
    // users get a fully functional, fully static site.
    const REDUCED = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // Cross-document View Transitions (Chrome 126+). When the browser can run
    // the native page transition declared in main.css, the legacy JS fade-out
    // below steps aside so navigations aren't animated twice.
    const HAS_VIEW_TRANSITIONS =
        typeof CSS !== 'undefined' &&
        CSS.supports && CSS.supports('view-transition-name: none');

    // Marker class so CSS can scope JS-dependent styles (e.g. image fade-in)
    // and no-JS visitors never get invisible content.
    document.documentElement.classList.add('ot-motion');
    if (REDUCED) document.documentElement.classList.add('ot-reduced');

    // ── 1. PAGE FADE-IN ────────────────────────────────────────────────────────
    // Body starts invisible; we fade it in immediately on DOM ready.
    // This prevents the "flash of unstyled content" on slow connections.
    document.documentElement.style.setProperty('--page-opacity', REDUCED ? '1' : '0');

    const pageStyle = document.createElement('style');
    pageStyle.textContent = `
        body { opacity: var(--page-opacity, 0); transition: opacity 0.35s ease; }
    `;
    document.head.appendChild(pageStyle);

    function revealPage() {
        document.documentElement.style.setProperty('--page-opacity', '1');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', revealPage);
    } else {
        // Already loaded (script deferred)
        requestAnimationFrame(revealPage);
    }

    // Fade out before navigating away (makes internal nav feel like SPA).
    // Skipped entirely when the browser supports native cross-document View
    // Transitions (declared in main.css) — those are smoother and free.
    if (!REDUCED && !HAS_VIEW_TRANSITIONS) {
        document.addEventListener('click', function (e) {
            const link = e.target.closest('a[href]');
            if (!link) return;
            // Never hijack modified clicks: ctrl/cmd-click (new tab),
            // shift-click (new window), middle-click, alt-click (download).
            if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey ||
                e.button !== 0 || e.defaultPrevented) return;
            if (link.hasAttribute('download')) return;
            const href = link.getAttribute('href');
            // Only animate same-origin, non-hash, non-external links
            if (!href || href.startsWith('#') || href.startsWith('http') ||
                href.startsWith('mailto') || link.target === '_blank') return;
            e.preventDefault();
            document.documentElement.style.setProperty('--page-opacity', '0');
            setTimeout(() => { window.location.href = href; }, 280);
        });
    }

    // ── 2. SCROLL PROGRESS BAR ─────────────────────────────────────────────────
    const progressBar = document.createElement('div');
    progressBar.id = 'scroll-progress';
    progressBar.style.cssText = `
        position: fixed;
        top: 0; left: 0;
        height: 2px;
        width: 0%;
        background: #e50914;
        z-index: 9999;
        transition: width 0.1s linear;
        pointer-events: none;
    `;
    document.body.appendChild(progressBar);

    window.addEventListener('scroll', function () {
        const scrolled = window.scrollY;
        const total    = document.documentElement.scrollHeight - window.innerHeight;
        const pct      = total > 0 ? (scrolled / total) * 100 : 0;
        progressBar.style.width = pct + '%';
    }, { passive: true });

    // ── 3. CUSTOM CURSOR ───────────────────────────────────────────────────────
    // Only on non-touch devices, and never when the user asked for reduced
    // motion — they keep the native OS cursor.
    if (!REDUCED && window.matchMedia('(pointer: fine)').matches) {
        const cursor    = document.createElement('div');
        const cursorDot = document.createElement('div');

        cursor.id    = 'ot-cursor';
        cursorDot.id = 'ot-cursor-dot';

        cursor.style.cssText = `
            position: fixed;
            width: 32px; height: 32px;
            border: 1px solid #e50914;
            border-radius: 50%;
            pointer-events: none;
            z-index: 99999;
            transform: translate(-50%, -50%);
            transition: width 0.25s ease, height 0.25s ease,
                        border-color 0.2s ease, opacity 0.3s ease,
                        background-color 0.25s ease;
            opacity: 0;
            will-change: transform;
            mix-blend-mode: difference;
        `;

        cursorDot.style.cssText = `
            position: fixed;
            width: 4px; height: 4px;
            background: #e50914;
            border-radius: 50%;
            pointer-events: none;
            z-index: 99999;
            transform: translate(-50%, -50%);
            transition: opacity 0.3s ease;
            opacity: 0;
            will-change: transform;
        `;

        document.body.appendChild(cursor);
        document.body.appendChild(cursorDot);

        let cx = -100, cy = -100;      // cursor ring (eased)
        let tx = -100, ty = -100;      // target position
        let visible = false;

        document.addEventListener('mousemove', function (e) {
            tx = e.clientX; ty = e.clientY;
            if (!visible) {
                visible = true;
                cursor.style.opacity    = '1';
                cursorDot.style.opacity = '1';
            }
            // Dot follows exactly
            cursorDot.style.left = tx + 'px';
            cursorDot.style.top  = ty + 'px';
        });

        document.addEventListener('mouseleave', function () {
            cursor.style.opacity    = '0';
            cursorDot.style.opacity = '0';
            visible = false;
        });

        // Animate cursor ring with easing
        (function animateCursor() {
            cx += (tx - cx) * 0.12;
            cy += (ty - cy) * 0.12;
            cursor.style.left = cx + 'px';
            cursor.style.top  = cy + 'px';
            requestAnimationFrame(animateCursor);
        })();

        // Cursor grows on interactive elements
        document.addEventListener('mouseover', function (e) {
            const el = e.target.closest('a, button, [role="button"], input, select, textarea, label');
            if (el) {
                cursor.style.width           = '52px';
                cursor.style.height          = '52px';
                cursor.style.borderColor     = '#e50914';
                cursor.style.backgroundColor = 'rgba(229,9,20,0.08)';
            } else {
                cursor.style.width           = '32px';
                cursor.style.height          = '32px';
                cursor.style.borderColor     = '#e50914';
                cursor.style.backgroundColor = 'transparent';
            }
        });

        // Click burst effect
        document.addEventListener('mousedown', function () {
            cursor.style.transform = 'translate(-50%, -50%) scale(0.75)';
        });
        document.addEventListener('mouseup', function () {
            cursor.style.transform = 'translate(-50%, -50%) scale(1)';
        });

        // Hide default cursor everywhere
        const noCursorStyle = document.createElement('style');
        noCursorStyle.textContent = '* { cursor: none !important; }';
        document.head.appendChild(noCursorStyle);
    }

    // ── 4. SCROLL REVEAL ───────────────────────────────────────────────────────
    // Add [data-reveal] to any element to animate it in on scroll.
    // Variants: data-reveal="fade" | "slide-up" | "slide-left" | "slide-right" | "scale"
    // Delay:    data-reveal-delay="200" (ms)
    // Duration: data-reveal-duration="600" (ms)

    const revealCSS = document.createElement('style');
    revealCSS.textContent = `
        [data-reveal] {
            opacity: 0;
            transition-property: opacity, transform;
            transition-timing-function: cubic-bezier(0.16, 1, 0.3, 1);
            will-change: opacity, transform;
        }
        [data-reveal="fade"]        { }
        [data-reveal="slide-up"]    { transform: translateY(40px); }
        [data-reveal="slide-left"]  { transform: translateX(40px); }
        [data-reveal="slide-right"] { transform: translateX(-40px); }
        [data-reveal="scale"]       { transform: scale(0.92); }
        [data-reveal].revealed {
            opacity: 1 !important;
            transform: none !important;
        }
    `;
    document.head.appendChild(revealCSS);

    function initReveal() {
        const els = document.querySelectorAll('[data-reveal]');
        if (!els.length) return;

        const observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) return;
                const el       = entry.target;
                const delay    = parseInt(el.dataset.revealDelay    || '0',  10);
                const duration = parseInt(el.dataset.revealDuration || '700', 10);
                el.style.transitionDuration = duration + 'ms';
                setTimeout(function () {
                    el.classList.add('revealed');
                }, delay);
                observer.unobserve(el);
            });
        }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });

        els.forEach(function (el) { observer.observe(el); });
    }

    // ── 5. STAGGER CHILDREN ────────────────────────────────────────────────────
    // Add [data-stagger] to a container to animate its direct children in sequence.
    // data-stagger-delay="80" — ms between each child (default 80ms)

    function initStagger() {
        const containers = document.querySelectorAll('[data-stagger]');
        containers.forEach(function (container) {
            const delay    = parseInt(container.dataset.staggerDelay || '80', 10);
            const children = Array.from(container.children);

            children.forEach(function (child, i) {
                child.setAttribute('data-reveal', child.dataset.reveal || 'slide-up');
                child.dataset.revealDelay = String(i * delay);
            });
        });
    }

    // ── 6. COUNTER ANIMATION ───────────────────────────────────────────────────
    // Add [data-count="1200"] to an element — it counts up when it enters view.

    function easeOut(t) { return 1 - Math.pow(1 - t, 3); }

    function animateCount(el) {
        const target   = parseInt(el.dataset.count, 10);
        const duration = parseInt(el.dataset.countDuration || '1800', 10);
        const suffix   = el.dataset.countSuffix || '';
        const start    = performance.now();

        function tick(now) {
            const elapsed = Math.min(now - start, duration);
            const value   = Math.round(easeOut(elapsed / duration) * target);
            el.textContent = value.toLocaleString() + suffix;
            if (elapsed < duration) requestAnimationFrame(tick);
        }
        requestAnimationFrame(tick);
    }

    function initCounters() {
        const els = document.querySelectorAll('[data-count]');
        if (!els.length) return;

        const observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) return;
                animateCount(entry.target);
                observer.unobserve(entry.target);
            });
        }, { threshold: 0.5 });

        els.forEach(function (el) { observer.observe(el); });
    }

    // ── 7. MAGNETIC BUTTONS ────────────────────────────────────────────────────
    // Add [data-magnetic] to buttons/links for a subtle cursor-attraction effect.

    function initMagnetic() {
        if (!window.matchMedia('(pointer: fine)').matches) return;

        document.querySelectorAll('[data-magnetic]').forEach(function (el) {
            el.addEventListener('mousemove', function (e) {
                const rect     = el.getBoundingClientRect();
                const cx       = rect.left + rect.width  / 2;
                const cy       = rect.top  + rect.height / 2;
                const dx       = (e.clientX - cx) * 0.28;
                const dy       = (e.clientY - cy) * 0.28;
                el.style.transform    = `translate(${dx}px, ${dy}px)`;
                el.style.transition   = 'transform 0.15s ease';
            });
            el.addEventListener('mouseleave', function () {
                el.style.transform  = '';
                el.style.transition = 'transform 0.5s cubic-bezier(0.16,1,0.3,1)';
            });
        });
    }

    // ── 8. PARALLAX ────────────────────────────────────────────────────────────
    // Add [data-parallax="0.3"] to an element — it moves at 30% of scroll speed.

    function initParallax() {
        const els = document.querySelectorAll('[data-parallax]');
        if (!els.length) return;

        window.addEventListener('scroll', function () {
            const scrollY = window.scrollY;
            els.forEach(function (el) {
                const speed  = parseFloat(el.dataset.parallax || '0.2');
                const offset = scrollY * speed;
                el.style.transform = `translateY(${offset}px)`;
            });
        }, { passive: true });
    }

    // ── 9. TEXT SPLIT ANIMATION ────────────────────────────────────────────────
    // Add [data-split] to headings — each word animates in individually.

    function initSplitText() {
        document.querySelectorAll('[data-split]').forEach(function (el) {
            const words = el.textContent.trim().split(/\s+/);
            const delay = parseInt(el.dataset.splitDelay || '60', 10);

            el.innerHTML = words.map(function (word, i) {
                return `<span class="split-word" style="
                    display: inline-block;
                    opacity: 0;
                    transform: translateY(30px);
                    transition: opacity 0.6s cubic-bezier(0.16,1,0.3,1) ${i * delay}ms,
                                transform 0.6s cubic-bezier(0.16,1,0.3,1) ${i * delay}ms;
                ">${word}</span>`;
            }).join(' ');

            const spans = el.querySelectorAll('.split-word');
            const observer = new IntersectionObserver(function (entries) {
                if (!entries[0].isIntersecting) return;
                spans.forEach(function (span) {
                    span.style.opacity   = '1';
                    span.style.transform = 'translateY(0)';
                });
                observer.unobserve(el);
            }, { threshold: 0.2 });
            observer.observe(el);
        });
    }

    // ── 10. PROGRESSIVE IMAGE LOAD ─────────────────────────────────────────────
    // Every image settles in with a soft fade + micro-zoom as it finishes
    // loading (CSS lives in main.css under `.ot-motion img`). Images already in
    // cache are marked instantly so nothing flashes.

    function initImageLoad() {
        document.querySelectorAll('img').forEach(function (img) {
            if (img.complete && img.naturalWidth > 0) {
                img.classList.add('ot-loaded');
            } else {
                img.addEventListener('load',  function () { img.classList.add('ot-loaded'); }, { once: true });
                img.addEventListener('error', function () { img.classList.add('ot-loaded'); }, { once: true });
            }
        });
    }

    // ── REDUCED-MOTION FAST PATH ───────────────────────────────────────────────
    // Everything visible immediately; counters show their final value.
    function initReducedMotion() {
        document.querySelectorAll('[data-reveal]').forEach(function (el) {
            el.classList.add('revealed');
        });
        document.querySelectorAll('[data-count]').forEach(function (el) {
            const target = parseInt(el.dataset.count, 10);
            const suffix = el.dataset.countSuffix || '';
            if (!isNaN(target)) el.textContent = target.toLocaleString() + suffix;
        });
        document.querySelectorAll('img').forEach(function (img) {
            img.classList.add('ot-loaded');
        });
    }

    // ── INIT ALL ───────────────────────────────────────────────────────────────
    function init() {
        if (REDUCED) {
            initReducedMotion();
            return;
        }
        initStagger();   // must run before initReveal so children get data-reveal
        initReveal();
        initCounters();
        initMagnetic();
        initParallax();
        initSplitText();
        initImageLoad();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

})();
