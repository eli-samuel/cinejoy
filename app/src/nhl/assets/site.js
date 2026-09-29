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
})();
