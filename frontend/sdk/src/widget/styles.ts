export const widgetStyles = `
:host { display:block; color:var(--gp-text,#17202a); font-family:var(--gp-font,system-ui,sans-serif); }
section { border:1px solid var(--gp-border,#ccd1d1); border-radius:var(--gp-radius,0.75rem); padding:1rem; background:var(--gp-background,#fff); }
button { font:inherit; min-height:44px; padding:0.65rem 1rem; border:1px solid transparent; border-radius:var(--gp-radius,0.75rem); background:var(--gp-accent,#1554ad); color:var(--gp-button-text,#fff); cursor:pointer; }
button:disabled { opacity:0.65; cursor:wait; }
button:focus-visible { outline:3px solid var(--gp-focus,#b35c00); outline-offset:3px; }
[role="status"] { margin:0 0 1rem; line-height:1.5; }
@media (prefers-reduced-motion:reduce) { *, *::before, *::after { animation:none!important; transition:none!important; scroll-behavior:auto!important; } }
`;
