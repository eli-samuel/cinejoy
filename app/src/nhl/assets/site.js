// NHL TV tweaks for nhlstreams.io. Appended to inject.js, so it runs at document start in every frame.
(function () {
  if (window !== window.top) return;

  // Start in the site's dark theme. The site's own toggle keeps its choice in localStorage
  // "currentTheme" ("themeActive" = dark). Only set it once, so switching back to light sticks.
  try {
    if (!localStorage.getItem('nhltvDarkDefault')) {
      localStorage.setItem('currentTheme', 'themeActive');
      localStorage.setItem('nhltvDarkDefault', '1');
    }
  } catch (e) {}

  // No stream chat: the app blocks chatango.com (SITE_BLOCKLIST), and this removes the chat panel
  // and its open/close buttons. "hide_chat" is the site's own class that gives the player the
  // chat's width.
  var styled = false;
  function addStyle() {
    styled = true;
    var style = document.createElement('style');
    style.textContent = 'html body .chatbox_area,html body .chat_hide_button{display:none!important}';
    (document.head || document.documentElement).appendChild(style);
  }
  // At document start there may be no <html> element yet.
  if (document.documentElement) addStyle();
  document.addEventListener('DOMContentLoaded', function () {
    if (!styled) addStyle();
    if (document.querySelector('.chatbox_area')) document.body.classList.add('hide_chat');
  });
})();
