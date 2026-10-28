/*
 * e-GP Mobile Browser: tap-to-hover for the e-GP portal's hover menus.
 *
 * Injected only into https://www.eprocure.gov.bd and https://eprocure.gov.bd.
 * It only shows and hides menus: it reads no form fields, cookies or storage,
 * exposes nothing to the app and makes no network requests.
 *
 * The portal's menus open on mouse hover:
 *  - ddlevelsmenu.js (Dynamic Drive): header <a rel="ddsubmenuN"> opens <ul id="ddsubmenuN">
 *    on mouseover, nested <li><ul> levels likewise, and a click on the header hides the menu.
 *  - CSS: .dropdown:hover .dropdown-content / .dropdown1:hover .dropdown1-content.
 * A tap fires mouseover and click back to back, so the menu flashes open and closes again
 * (or the header link navigates). Like Safari on iOS, the first tap on a closed menu header
 * now opens it, a second tap on the same header follows its link, and tapping anywhere
 * outside closes it.
 */
(function () {
  'use strict';
  if (window.__egpTouchHover) return;
  window.__egpTouchHover = true;

  var OPEN_CLASS = 'egp-touch-open';
  var TAP_WINDOW_MS = 1500;
  var pending = null;

  function hasClass(el, name) {
    return !!(el.classList && el.classList.contains(name));
  }

  function insideClass(el, name) {
    for (var n = el; n && n.nodeType === 1; n = n.parentNode) {
      if (hasClass(n, name)) return true;
    }
    return false;
  }

  function childList(li) {
    for (var c = li.firstElementChild; c; c = c.nextElementSibling) {
      if (c.tagName === 'UL') return c;
    }
    return null;
  }

  function isVisible(menu) {
    return window.getComputedStyle(menu).visibility === 'visible';
  }

  /** The hover-menu header that contains node, as {el, kind, menu}, or null. */
  function headerFor(node) {
    for (var el = node; el && el.nodeType === 1; el = el.parentNode) {
      if (el.tagName === 'A' && el.getAttribute('rel')) {
        var dropdown = document.getElementById(el.getAttribute('rel'));
        if (dropdown && dropdown.tagName === 'UL') return { el: el, kind: 'dd', menu: dropdown };
      }
      if (el.tagName === 'LI' && insideClass(el, 'ddsubmenustyle')) {
        var nested = childList(el);
        // A leaf item inside a drop-down is an ordinary link, not a header.
        return nested ? { el: el, kind: 'dd', menu: nested } : null;
      }
      if (hasClass(el, 'dropdown') || hasClass(el, 'dropdown1')) {
        var content = el.querySelector('.dropdown-content, .dropdown1-content');
        if (!content || content.contains(node)) return null;
        return { el: el, kind: 'css', menu: content };
      }
    }
    return null;
  }

  function isOpen(header) {
    if (header.kind === 'css') {
      return hasClass(header.el, OPEN_CLASS) || window.getComputedStyle(header.menu).display !== 'none';
    }
    return isVisible(header.menu);
  }

  function mouse(type, el) {
    el.dispatchEvent(new MouseEvent(type, {
      bubbles: true, cancelable: true, view: window, relatedTarget: document.body
    }));
  }

  function open(header) {
    if (header.kind === 'css') {
      header.el.classList.add(OPEN_CLASS);
    } else if (!isVisible(header.menu)) {
      mouse('mouseover', header.el);
    }
  }

  /** Closes every open menu that does not contain target. */
  function closeOutside(target) {
    var opened = document.querySelectorAll('.' + OPEN_CLASS);
    for (var i = 0; i < opened.length; i++) {
      if (!opened[i].contains(target)) opened[i].classList.remove(OPEN_CLASS);
    }
    var headers = document.querySelectorAll('a[rel]');
    for (var j = 0; j < headers.length; j++) {
      var a = headers[j];
      var dropdown = document.getElementById(a.getAttribute('rel'));
      if (!dropdown || dropdown.tagName !== 'UL' || !isVisible(dropdown)) continue;
      if (a.contains(target) || dropdown.contains(target)) continue;
      // Nested levels first; their inline visibility would otherwise outlive the parent.
      var items = dropdown.querySelectorAll('li');
      for (var k = 0; k < items.length; k++) {
        var nested = childList(items[k]);
        if (nested && isVisible(nested)) mouse('mouseout', items[k]);
      }
      mouse('mouseout', a);
    }
  }

  // touchstart runs before the browser's emulated mouseover, so it sees the state before the tap.
  window.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) {
      pending = null;
      return;
    }
    var header = headerFor(e.target);
    pending = header ? { el: header.el, wasOpen: isOpen(header), time: Date.now() } : null;
  }, { capture: true, passive: true });

  window.addEventListener('click', function (e) {
    var tap = pending;
    pending = null;
    var header = headerFor(e.target);
    var firstTapOnClosedHeader = header && tap && tap.el === header.el && !tap.wasOpen &&
      Date.now() - tap.time < TAP_WINDOW_MS;
    closeOutside(e.target);
    if (!firstTapOnClosedHeader) return;
    // Keep the menu open: stop the header's link and the menu script's hide-on-click.
    e.preventDefault();
    e.stopPropagation();
    open(header);
  }, true);

  function addStyle() {
    var style = document.createElement('style');
    style.textContent =
      '.dropdown.' + OPEN_CLASS + ' .dropdown-content,' +
      '.dropdown1.' + OPEN_CLASS + ' .dropdown1-content{display:block!important}';
    (document.head || document.documentElement).appendChild(style);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', addStyle);
  } else {
    addStyle();
  }
})();
