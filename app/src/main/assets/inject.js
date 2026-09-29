// Injected at document start into every frame (top page and iframes).
(function () {
  if (window.__cjtv) return;
  window.__cjtv = true;

  // Filled in by MainActivity with the wrapped site's domain.
  var SITE = '__SITE_DOMAIN__';
  var host = location.hostname.toLowerCase();
  var firstParty = host === SITE || host.slice(-SITE.length - 1) === '.' + SITE;
  var isTop = window === window.top;

  // Third-party frames (embedded players, ad iframes) never get to open popups.
  if (!firstParty) {
    try { window.open = function () { return null; }; } catch (e) {}
  }

  // --- Remote control of <video> elements, relayed into cross-origin frames ---
  function pickVideo() {
    var v = document.querySelectorAll('video'), i;
    for (i = 0; i < v.length; i++) if (!v[i].paused) return v[i];
    for (i = 0; i < v.length; i++) if (v[i].readyState > 0) return v[i];
    return null;
  }
  function handle(m) {
    var v = pickVideo();
    if (!v) return;
    var play = function () { var p = v.play(); if (p && p.catch) p.catch(function () {}); };
    switch (m.cjtv) {
      case 'toggle': v.paused ? play() : v.pause(); break;
      case 'play': play(); break;
      case 'pause': v.pause(); break;
      case 'seek': v.currentTime = Math.max(0, v.currentTime + (m.v || 0)); break;
    }
  }
  function forward(m) {
    for (var i = 0; i < window.frames.length; i++) {
      try { window.frames[i].postMessage(m, '*'); } catch (e) {}
    }
  }
  window.addEventListener('message', function (e) {
    var m = e.data;
    if (m && typeof m === 'object' && m.cjtv && e.source === window.parent && !isTop) {
      handle(m);
      forward(m);
    }
  });

  if (!isTop) return;

  window.__cjtvBroadcast = function (m) { handle(m); forward(m); };

  // Scroll whatever is under the cursor (inner scroll containers, carousels), else the page.
  window.__cjtvScroll = function (fx, fy, dx, dy) {
    var px = dx * window.innerWidth, py = dy * window.innerHeight;
    var el = document.elementFromPoint(fx * window.innerWidth, fy * window.innerHeight);
    while (el && el !== document.body && el !== document.documentElement) {
      var s = getComputedStyle(el);
      var canY = py && /(auto|scroll)/.test(s.overflowY) && el.scrollHeight > el.clientHeight;
      var canX = px && /(auto|scroll)/.test(s.overflowX) && el.scrollWidth > el.clientWidth;
      if (canY || canX) { el.scrollBy(canX ? px : 0, canY ? py : 0); return; }
      el = el.parentElement;
    }
    window.scrollBy(px, py);
  };

  // --- TV-style focus navigation: the D-pad jumps between clickable elements ---
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,video,iframe,' +
    '[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[onclick],' +
    '[tabindex]:not([tabindex="-1"])';
  var current = null, cache = null, dirty = true;

  new MutationObserver(function () { dirty = true; })
    .observe(document.documentElement || document, { childList: true, subtree: true });

  function rect(e) { return e.getBoundingClientRect(); }

  function visible(e) {
    var r = rect(e);
    if (r.width < 6 || r.height < 6) return false;
    var s = getComputedStyle(e);
    return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity) > 0.05;
  }

  // Clickable elements, including JS-driven cards that only advertise themselves via cursor:pointer.
  function collect() {
    if (!dirty && cache) return cache;
    var list = [].slice.call(document.querySelectorAll(SEL));
    var extra = document.querySelectorAll('div,li,span,img,article,section');
    if (extra.length < 5000) {
      for (var i = 0; i < extra.length; i++) {
        var e = extra[i], p = e.parentElement;
        // cursor is inherited, so only take the outermost element of a pointer subtree.
        if (getComputedStyle(e).cursor === 'pointer' && !(p && getComputedStyle(p).cursor === 'pointer')) list.push(e);
      }
    }
    var all = new Set(list);
    cache = list.filter(function (e) {
      for (var p = e.parentElement; p; p = p.parentElement) if (all.has(p)) return false;
      return true;
    });
    dirty = false;
    return cache;
  }

  function mark(e) {
    if (current) current.classList.remove('cjtv-focus');
    current = e;
    e.classList.add('cjtv-focus');
    if (!/^(INPUT|TEXTAREA|SELECT)$/.test(e.tagName)) {
      try { e.focus({ preventScroll: true }); } catch (x) {}
    }
    e.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    var r = rect(e);
    if (r.top < innerHeight * 0.12 || r.bottom > innerHeight * 0.88 || r.left < 0 || r.right > innerWidth) {
      e.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
    }
  }

  function gap(a1, a2, b1, b2) { return b2 < a1 ? a1 - b2 : (b1 > a2 ? b1 - a2 : 0); }

  window.__cjtvNav = function (dir) {
    var list = collect().filter(visible), best = null, bestScore = Infinity;
    if (!current || !current.isConnected || !visible(current)) {
      // Nothing selected yet: start with the top-left item on screen.
      list.forEach(function (e) {
        var r = rect(e);
        if (r.bottom < 0 || r.top > innerHeight) return;
        var s = r.top * 2 + r.left;
        if (s < bestScore) { bestScore = s; best = e; }
      });
    } else {
      var r = rect(current);
      list.forEach(function (e) {
        if (e === current || e.contains(current) || current.contains(e)) return;
        var c = rect(e), main, side;
        if (dir === 'down') {
          if (c.top < r.bottom - r.height * 0.25) return;
          main = c.top - r.bottom; side = gap(r.left, r.right, c.left, c.right);
        } else if (dir === 'up') {
          if (c.bottom > r.top + r.height * 0.25) return;
          main = r.top - c.bottom; side = gap(r.left, r.right, c.left, c.right);
        } else if (dir === 'right') {
          if (c.left < r.right - r.width * 0.25) return;
          main = c.left - r.right; side = gap(r.top, r.bottom, c.top, c.bottom);
        } else {
          if (c.right > r.left + r.width * 0.25) return;
          main = r.left - c.right; side = gap(r.top, r.bottom, c.top, c.bottom);
        }
        var s = Math.max(0, main) + side * 4;
        if (s < bestScore) { bestScore = s; best = e; }
      });
    }
    if (best) { mark(best); return 'moved'; }
    // Nothing further that way: scroll so lazy-loaded content can appear.
    if (dir === 'down') window.scrollBy(0, innerHeight * 0.4);
    if (dir === 'up') window.scrollBy(0, -innerHeight * 0.4);
    dirty = true;
    return 'scrolled';
  };

  // Centre of the selected element in CSS pixels, plus viewport width for scaling.
  window.__cjtvTarget = function () {
    if (!current || !current.isConnected) return null;
    var r = rect(current);
    var x = Math.min(Math.max(r.left + r.width / 2, 1), innerWidth - 1);
    var y = Math.min(Math.max(r.top + r.height / 2, 1), innerHeight - 1);
    return [x, y, innerWidth];
  };

  // Light cosmetic filtering for common ad containers.
  // Focus style: soft white ring + lift, similar to native TV launchers.
  var css = '.cjtv-focus{outline:2px solid rgba(255,255,255,.85)!important;outline-offset:3px!important;' +
    'border-radius:8px;box-shadow:0 0 0 7px rgba(255,255,255,.14),0 10px 28px rgba(0,0,0,.55)!important;' +
    'filter:brightness(1.12);transition:outline-offset .15s ease,box-shadow .15s ease,filter .15s ease}' + [
    'ins.adsbygoogle', '[id^="google_ads_"]', '[id^="div-gpt-ad"]',
    'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication"]',
    '[id*="ScriptRoot"]', '[class*="popunder"]', '[id*="popunder"]',
    'a[href*="//ad."][target="_blank"] > img'
  ].join(',') + '{display:none!important}';
  function addStyle() {
    var st = document.createElement('style');
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  }
  if (document.documentElement) addStyle();
  else document.addEventListener('DOMContentLoaded', addStyle);
})();
