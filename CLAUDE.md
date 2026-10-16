<!-- bench:design-tokens -->
Design tokens are mandatory: they live as CSS custom properties in `web/app.css` `:root` and `web/themes.css`, recorded in `design-tokens.json`; `Design.md` says why. No hardcoded colours, sizes or spaces in components.
<!-- /bench:design-tokens -->

<!-- bench:design-brief -->
UI work: `Design-brief.md` says what each view is for, its one primary action, its states and its layout at both widths. Build to it; change the brief first when a view's job changes.
<!-- /bench:design-brief -->

<!-- bench:design-check -->
UI changes: before you finish, run `/bench:verify-ui` and show its before and after screenshots. The design gate is `sh .bench/design-check.sh`: 0 passed, 1 a defect it names, 2 it could not look, which is a failure. Fix what it names.
<!-- /bench:design-check -->
