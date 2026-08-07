/**
 * Gravity Theme Toggle
 *
 * Loads gravity-theme.css by default; an explicit localStorage preference
 * can switch to Classic Deck. Renders a small action button fixed at the
 * bottom-left corner.
 *
 * Default: Gravity UI
 * Toggle: loads/unloads the Gravity CSS stylesheet dynamically.
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'deck-gravity-enabled';
  var CSS_ID = 'gravity-theme-link';
  // Bump this URL when deploying the CSS directly to a running pod so
  // browsers cannot reuse a stale theme stylesheet from their cache.
  var CSS_HREF = '/gravity-theme.css?v=gravity-ui-20260807-05';

  // Gravity is the default. Preserve only an explicit Classic opt-out.
  function isGravityEnabled() {
    return localStorage.getItem(STORAGE_KEY) !== 'false';
  }

  function setGravityEnabled(enabled) {
    localStorage.setItem(STORAGE_KEY, enabled ? 'true' : 'false');
  }

  function loadGravityCSS() {
    if (document.getElementById(CSS_ID)) return;
    var link = document.createElement('link');
    link.id = CSS_ID;
    link.rel = 'stylesheet';
    link.href = CSS_HREF;
    document.body.appendChild(link);
  }

  function unloadGravityCSS() {
    var link = document.getElementById(CSS_ID);
    if (link) link.remove();
  }

  function createToggleButton() {
    var btn = document.createElement('button');
    btn.id = 'gravity-toggle-btn';
    btn.type = 'button';

    // Styles — fixed bottom-left, unobtrusive
    btn.style.cssText = [
      'position: fixed',
      'bottom: 16px',
      'left: 16px',
      'z-index: 99999',
      'border: 1px solid rgba(0,0,0,0.1)',
      'border-radius: 20px',
      'padding: 6px 12px',
      'font-size: 12px',
      'font-family: -apple-system, BlinkMacSystemFont, sans-serif',
      'font-weight: 500',
      'cursor: pointer',
      'transition: background-color 0.2s, color 0.2s, box-shadow 0.2s',
      'box-shadow: 0 2px 8px rgba(0,0,0,0.08)',
      'user-select: none'
    ].join('; ');

    updateButtonAppearance(btn);

    btn.addEventListener('click', function () {
      var enabled = !isGravityEnabled();
      setGravityEnabled(enabled);
      if (enabled) {
        loadGravityCSS();
      } else {
        unloadGravityCSS();
      }
      updateButtonAppearance(btn);
    });

    document.body.appendChild(btn);
  }

  function updateButtonAppearance(btn) {
    var gravityEnabled = isGravityEnabled();

    btn.setAttribute('aria-pressed', String(gravityEnabled));

    if (gravityEnabled) {
      // Buttons describe their next action; title describes current state.
      btn.textContent = 'Return to Classic UI';
      btn.title = 'Current theme: Gravity UI';
      btn.setAttribute('aria-label', 'Switch to Classic UI');
      btn.style.backgroundColor = '#ffffff';
      btn.style.color = '#000F1E';
      btn.style.borderColor = 'rgba(0,15,30,0.15)';
    } else {
      btn.textContent = 'Try Gravity UI';
      btn.title = 'Current theme: Classic UI';
      btn.setAttribute('aria-label', 'Switch to Gravity UI');
      btn.style.backgroundColor = '#1B6AEE';
      btn.style.color = '#ffffff';
      btn.style.borderColor = '#1B6AEE';
    }
  }

  // Initialize on DOM ready
  function init() {
    if (isGravityEnabled()) {
      loadGravityCSS();
    }
    createToggleButton();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
