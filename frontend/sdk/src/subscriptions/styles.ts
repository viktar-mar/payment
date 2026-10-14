export const subscriptionStyles = `
:host { display:block; font-family:var(--gp-subscription-font,system-ui,sans-serif); color:var(--gp-subscription-text,#172033); }
* { box-sizing:border-box; } [hidden] { display:none !important; }
section { padding:1.25rem; border:1px solid var(--gp-subscription-border,#c5cfdf); border-radius:var(--gp-subscription-radius,.8rem); background:var(--gp-subscription-background,#fff); }
h2,h3,p { overflow-wrap:anywhere; } h2 { margin-top:0; } p,li,dd { line-height:1.55; }
dl { display:grid; grid-template-columns:minmax(7rem,1fr) 2fr; gap:.4rem 1rem; } dd { margin:0; overflow-wrap:anywhere; }
fieldset { margin:1rem 0; padding:1rem; border:1px solid var(--gp-subscription-border,#c5cfdf); border-radius:var(--gp-subscription-radius,.8rem); }
label { display:block; margin:.75rem 0; } select,button,input { font:inherit; } select { max-width:100%; padding:.5rem; color:inherit; background:var(--gp-subscription-background,#fff); }
button { min-height:2.75rem; margin:.25rem; padding:.6rem 1rem; border:1px solid var(--gp-subscription-accent,#234d98); border-radius:var(--gp-subscription-radius,.8rem); background:var(--gp-subscription-background,#fff); color:var(--gp-subscription-accent,#234d98); cursor:pointer; }
button:disabled { opacity:.6; cursor:wait; } input[type=checkbox] { width:1.1rem; height:1.1rem; margin-right:.5rem; accent-color:var(--gp-subscription-accent,#234d98); }
:focus-visible { outline:3px solid var(--gp-subscription-focus,#2563eb); outline-offset:3px; }
[role=alert] { font-weight:650; } [role=status] { min-height:1.5em; } ul { padding-left:1.3rem; } .note { font-size:.9rem; }
@media(max-width:28rem) { dl { grid-template-columns:1fr; } dd { margin-bottom:.6rem; } }
@media(prefers-reduced-motion:reduce) { *,*::before,*::after { animation:none !important; transition:none !important; scroll-behavior:auto !important; } }
`;
