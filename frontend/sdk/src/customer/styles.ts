export const emailOtpStyles = `
:host{display:block;font-family:var(--gp-font,system-ui,sans-serif);color:var(--gp-text,#17202a)}
section{padding:1rem;background:var(--gp-background,white);border:1px solid var(--gp-border,#ccd1d1);border-radius:var(--gp-radius,.75rem)}
form{display:grid;gap:.75rem}label{font-weight:600}input,button{font:inherit;padding:.7rem;border-radius:var(--gp-radius,.75rem)}
input{border:1px solid var(--gp-border,#ccd1d1);background:var(--gp-background,white);color:inherit;min-width:0}
button{background:var(--gp-accent,#1554ad);color:var(--gp-button-text,white);border:0;cursor:pointer}button:disabled{opacity:.6;cursor:default}
input:focus-visible,button:focus-visible{outline:3px solid var(--gp-focus,#b35c00);outline-offset:3px}[hidden]{display:none!important}
@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto}}
`;
