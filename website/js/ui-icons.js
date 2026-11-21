/* 界面图标（24×24，圆润填充风格，贴近 App 截图里的 Material Rounded 图标）
 * 用法：HTML 里写 <i data-ic="play"></i>，调用 SIcons.hydrate() 替换为 SVG
 */
(function () {
  const P = {
    play: '<path d="M8 5.6v12.8c0 .8.9 1.3 1.6.9l10-6.4c.6-.4.6-1.4 0-1.8l-10-6.4C8.9 4.3 8 4.8 8 5.6z"/>',
    pause: '<rect x="6.5" y="5" width="4" height="14" rx="1.6"/><rect x="13.5" y="5" width="4" height="14" rx="1.6"/>',
    prev: '<path d="M11.5 7.2v9.6c0 .8-.9 1.2-1.5.7L4.6 13c-.5-.4-.5-1.2 0-1.6L10 6.5c.6-.5 1.5-.1 1.5.7zm8 0v9.6c0 .8-.9 1.2-1.5.7L12.6 13c-.5-.4-.5-1.2 0-1.6L18 6.5c.6-.5 1.5-.1 1.5.7z"/>',
    next: '<path d="M12.5 7.2v9.6c0 .8.9 1.2 1.5.7l5.4-4.5c.5-.4.5-1.2 0-1.6L14 6.5c-.6-.5-1.5-.1-1.5.7zm-8 0v9.6c0 .8.9 1.2 1.5.7l5.4-4.5c.5-.4.5-1.2 0-1.6L6 6.5c-.6-.5-1.5-.1-1.5.7z"/>',
    skipPrev: '<rect x="5" y="6" width="2.4" height="12" rx="1.2"/><path d="M18.5 7v10c0 .8-.9 1.3-1.6.8l-7.3-5c-.6-.4-.6-1.2 0-1.6l7.3-5c.7-.5 1.6 0 1.6.8z"/>',
    skipNext: '<rect x="16.6" y="6" width="2.4" height="12" rx="1.2"/><path d="M5.5 7v10c0 .8.9 1.3 1.6.8l7.3-5c.6-.4.6-1.2 0-1.6l-7.3-5c-.7-.5-1.6 0-1.6.8z"/>',
    shuffle: '<path d="M4 7h2.6c1.3 0 2.5.6 3.3 1.7l4.2 6.6c.8 1.1 2 1.7 3.3 1.7H19" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/><path d="M4 17h2.6c1.3 0 2.5-.6 3.3-1.7l4.2-6.6c.8-1.1 2-1.7 3.3-1.7H19" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/><path d="M17.5 4.5 20.5 7l-3 2.5zM17.5 14.5l3 2.5-3 2.5z"/>',
    queue: '<rect x="3.5" y="6" width="11" height="2.2" rx="1.1"/><rect x="3.5" y="11" width="11" height="2.2" rx="1.1"/><rect x="3.5" y="16" width="7" height="2.2" rx="1.1"/><path d="M17 6.5v8.2a2.6 2.6 0 1 0 2 2.5V9h2V6.5z"/>',
    heart: '<path d="M12 20.3 10.6 19C5.6 14.5 2.5 11.7 2.5 8.2 2.5 5.4 4.7 3.3 7.4 3.3c1.6 0 3.1.7 4.1 1.9l.5.6.5-.6c1-1.2 2.5-1.9 4.1-1.9 2.7 0 4.9 2.1 4.9 4.9 0 3.5-3.1 6.3-8.1 10.8z"/>',
    download: '<path d="M12 4v10.2m0 0-4.2-4.2M12 14.2l4.2-4.2" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/><rect x="5" y="17.6" width="14" height="2.4" rx="1.2"/>',
    search: '<circle cx="10.5" cy="10.5" r="6" fill="none" stroke="currentColor" stroke-width="2.4"/><path d="m15 15 5 5" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/>',
    gear: '<path d="M12 8.6a3.4 3.4 0 1 0 0 6.8 3.4 3.4 0 0 0 0-6.8zm8.2 2.2-1.9-.4a6.6 6.6 0 0 0-.7-1.6l1.1-1.6-1.9-1.9-1.6 1.1c-.5-.3-1-.5-1.6-.7l-.4-1.9h-2.6l-.4 1.9c-.6.2-1.1.4-1.6.7L7 5.3 5.1 7.2l1.1 1.6c-.3.5-.5 1-.7 1.6l-1.9.4v2.6l1.9.4c.2.6.4 1.1.7 1.6l-1.1 1.6L7 18.9l1.6-1.1c.5.3 1 .5 1.6.7l.4 1.9h2.6l.4-1.9c.6-.2 1.1-.4 1.6-.7l1.6 1.1 1.9-1.9-1.1-1.6c.3-.5.5-1 .7-1.6l1.9-.4z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>',
    layers: '<path d="m12 3.5 9 4.8-9 4.8-9-4.8z"/><path d="m4.6 12 7.4 4 7.4-4 1.6.9-9 4.8-9-4.8z" opacity=".75"/><path d="m4.6 15.6 7.4 4 7.4-4 1.6.9-9 4.8-9-4.8z" opacity=".5"/>',
    chevronDown: '<path d="m6.5 9.5 5.5 5.5 5.5-5.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>',
    lyrics: '<rect x="4" y="6" width="9" height="2.2" rx="1.1"/><rect x="4" y="11" width="14" height="2.2" rx="1.1"/><rect x="4" y="16" width="11" height="2.2" rx="1.1"/>',
    volume: '<path d="M4 9.5v5h3.2l4.3 3.8V5.7L7.2 9.5z"/><path d="M15 9a4.2 4.2 0 0 1 0 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    sliders: '<path d="M7 4v16M12 4v16M17 4v16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><rect x="4.5" y="13" width="5" height="3" rx="1.5"/><rect x="9.5" y="7" width="5" height="3" rx="1.5"/><rect x="14.5" y="11" width="5" height="3" rx="1.5"/>',
    locate: '<circle cx="12" cy="12" r="6.2" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="12" r="2.4"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    dice: '<rect x="4" y="4" width="16" height="16" rx="4" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="9" cy="9" r="1.5"/><circle cx="15" cy="15" r="1.5"/><circle cx="15" cy="9" r="1.5"/><circle cx="9" cy="15" r="1.5"/>',
    vinyl: '<circle cx="12" cy="12" r="8.5" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="12" r="2.6"/><path d="M12 6.2a5.8 5.8 0 0 1 5.8 5.8" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" opacity=".7"/>',
    more: '<circle cx="12" cy="5.5" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="12" cy="18.5" r="1.8"/>',
    close: '<path d="m7 7 10 10M17 7 7 17" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/>',
    mic: '<rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M6 11.5a6 6 0 0 0 12 0M12 17.5v3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    speaker: '<rect x="6" y="3" width="12" height="18" rx="3" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="14.5" r="3"/><circle cx="12" cy="7.5" r="1.3"/>',
    headphone: '<path d="M4 15v-2.5a8 8 0 0 1 16 0V15" fill="none" stroke="currentColor" stroke-width="2"/><rect x="3.5" y="13.5" width="4.5" height="7" rx="2"/><rect x="16" y="13.5" width="4.5" height="7" rx="2"/>',
    tv: '<rect x="3" y="5" width="18" height="12" rx="2.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M8.5 20.5h7" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    nas: '<rect x="5" y="3.5" width="14" height="17" rx="2.5" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="9" cy="8" r="1.2"/><circle cx="9" cy="12" r="1.2"/><path d="M12 8h4M12 12h4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    phone: '<rect x="6.5" y="2.5" width="11" height="19" rx="3" fill="none" stroke="currentColor" stroke-width="2"/><path d="M10.5 18.5h3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    folder: '<path d="M3.5 7.5c0-1.1.9-2 2-2h4l2 2h7c1.1 0 2 .9 2 2v7.5c0 1.1-.9 2-2 2h-13c-1.1 0-2-.9-2-2z"/>',
    usb: '<path d="M12 3v14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="m9.5 5.5 2.5-3 2.5 3z"/><path d="M12 13l-4-2.5V8M12 15l4-2.5V10" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/><circle cx="12" cy="19" r="2.2"/>',
    tag: '<path d="M3.5 12.2V5.5c0-1.1.9-2 2-2h6.7l8.3 8.3c.8.8.8 2 0 2.8l-6.7 6.7c-.8.8-2 .8-2.8 0z" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="8.2" cy="8.2" r="1.6"/>',
    info: '<circle cx="12" cy="12" r="8.5" fill="none" stroke="currentColor" stroke-width="2"/><rect x="11" y="10.5" width="2" height="6.5" rx="1"/><circle cx="12" cy="7.6" r="1.3"/>',
    open: '<path d="M14 4h6v6M20 4l-8.5 8.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>',
    scan: '<path d="M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/><path d="M4 12h16" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>',
    widget: '<rect x="3.5" y="3.5" width="7.5" height="7.5" rx="2"/><rect x="13" y="3.5" width="7.5" height="7.5" rx="2" opacity=".6"/><rect x="3.5" y="13" width="17" height="7.5" rx="2" opacity=".8"/>',
    cast: '<path d="M3.5 17.5a3 3 0 0 1 3 3M3.5 13.5a7 7 0 0 1 7 7M3.5 9.5a11 11 0 0 1 11 11" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M8 4.5h10a2.5 2.5 0 0 1 2.5 2.5v10A2.5 2.5 0 0 1 18 19.5h-1" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    lock: '<rect x="5" y="10.5" width="14" height="10" rx="2.5"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5" fill="none" stroke="currentColor" stroke-width="2.2"/>',
    sparkle: '<path d="M12 3.5c.6 3.9 2.6 5.9 6.5 6.5-3.9.6-5.9 2.6-6.5 6.5-.6-3.9-2.6-5.9-6.5-6.5 3.9-.6 5.9-2.6 6.5-6.5z"/><path d="M18.5 15c.3 1.7 1.1 2.5 2.8 2.8-1.7.3-2.5 1.1-2.8 2.8-.3-1.7-1.1-2.5-2.8-2.8 1.7-.3 2.5-1.1 2.8-2.8z" opacity=".7"/>',
    shield: '<path d="M12 3 5 6v5.5c0 4.4 3 8.2 7 9.5 4-1.3 7-5.1 7-9.5V6z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="m9 12 2.2 2.2L15.5 10" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    code: '<path d="m8.5 7-5 5 5 5M15.5 7l5 5-5 5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
    arrowDown: '<path d="M12 5v14m0 0-5-5m5 5 5-5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
    copy: '<rect x="9" y="9" width="11" height="11" rx="2.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M5 15H4.5A2.5 2.5 0 0 1 2 12.5v-8A2.5 2.5 0 0 1 4.5 2h8A2.5 2.5 0 0 1 15 4.5V5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    chat: '<path d="M4.5 5.5A2.5 2.5 0 0 1 7 3h10a2.5 2.5 0 0 1 2.5 2.5v8A2.5 2.5 0 0 1 17 16H9.5l-4.2 3.5c-.6.5-1.3.1-1.3-.7z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><circle cx="9" cy="9.5" r="1.2"/><circle cx="12" cy="9.5" r="1.2"/><circle cx="15" cy="9.5" r="1.2"/>',
    github: '<path d="M12 2C6.48 2 2 6.58 2 12.23c0 4.52 2.87 8.35 6.84 9.7.5.1.68-.22.68-.49 0-.24-.01-1.04-.01-1.88-2.78.62-3.37-1.2-3.37-1.2-.45-1.18-1.11-1.5-1.11-1.5-.91-.64.07-.63.07-.63 1 .07 1.53 1.05 1.53 1.05.9 1.56 2.35 1.11 2.92.85.09-.66.35-1.11.63-1.37-2.22-.26-4.56-1.14-4.56-5.06 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.71 0 0 .84-.27 2.75 1.05a9.35 9.35 0 0 1 5 0c1.91-1.32 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.63 1.03 2.75 0 3.93-2.34 4.8-4.57 5.05.36.32.68.94.68 1.9 0 1.37-.01 2.48-.01 2.82 0 .27.18.6.69.49A10.22 10.22 0 0 0 22 12.23C22 6.58 17.52 2 12 2z"/>'
  };

  function svg(name, cls) {
    const p = P[name];
    if (!p) return '';
    return `<svg class="ic${cls ? ' ' + cls : ''}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">${p}</svg>`;
  }
  function hydrate(root = document) {
    root.querySelectorAll('i[data-ic]').forEach(i => {
      const s = svg(i.dataset.ic, i.className);
      if (s) i.outerHTML = s;
    });
  }
  window.SIcons = { svg, hydrate };
})();
