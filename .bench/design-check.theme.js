// Bench picks its theme from localStorage (web/theme.mjs, applied in index.html), not from
// prefers-color-scheme. Design.md names the two the gate judges: Classic for dark, Quiet
// Temple - the quietest light theme - for light.
localStorage.setItem('bench:theme', JSON.stringify(theme === 'dark' ? 'classic' : 'quiet-temple'));
