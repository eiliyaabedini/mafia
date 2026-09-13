/* Loading UI only. Game and account data never enter this module. */
(() => {
  const screen = document.getElementById('boot-screen');
  const title = document.getElementById('boot-title');
  const description = document.getElementById('boot-description');
  const status = document.getElementById('boot-status');
  const progress = document.getElementById('boot-progress');
  const retry = document.getElementById('boot-retry');
  const rtl = value => '\u202b' + value + '\u202c';
  let ready = false;
  let failed = false;
  const slowTimer = setTimeout(() => {
    if (ready || failed) return;
    status.textContent = rtl('بارگذاری اول کمی زمان می‌برد؛ اتصال اینترنتت را بررسی کن.');
    retry.hidden = false;
  }, 25000);
  retry.addEventListener('click', () => location.reload());
  function fail() {
    if (ready || failed) return;
    failed = true;
    clearTimeout(slowTimer);
    title.textContent = rtl('میز بازی آماده نشد');
    description.textContent = rtl(navigator.onLine
      ? 'دوباره تلاش کن. اگر مشکل ادامه داشت، صفحه را با نسخهٔ جدید مرورگرت باز کن.'
      : 'اتصال اینترنت قطع است. پس از وصل‌شدن، دوباره تلاش کن.');
    status.textContent = rtl('برای شروع بازی، اتصال اینترنت لازم است.');
    progress.hidden = true;
    retry.hidden = false;
    screen.setAttribute('aria-busy', 'false');
  }
  window.MafiaShell = {
    ready() {
      if (ready) return;
      ready = true;
      clearTimeout(slowTimer);
      requestAnimationFrame(() => requestAnimationFrame(() => {
        screen.hidden = true;
        screen.setAttribute('aria-busy', 'false');
      }));
    }
  };
  window.addEventListener('error', event => {
    if (event.target?.tagName === 'SCRIPT' || event.error || event.message) fail();
  }, true);
  window.addEventListener('unhandledrejection', fail);
  if (typeof WebAssembly === 'undefined') fail();
  if ('serviceWorker' in navigator && window.isSecureContext) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('./sw.js', { updateViaCache: 'none' }).catch(() => {
        // Private browsing or installation restrictions must not block online play.
      });
    }, { once: true });
  }
})();
