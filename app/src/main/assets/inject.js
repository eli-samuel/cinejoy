// Injected at document start into every frame (top page and iframes).
(function () {
  if (window.__cjtv) return;
  window.__cjtv = true;

  var firstParty = /(^|\.)cinejoy\.pk$/i.test(location.hostname);
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

  // Light cosmetic filtering for common ad containers.
  var css = [
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
