export const donationStyles = `
:host { display:block; color:var(--generic-donation-text,#17243a); font:inherit; }
* { box-sizing:border-box; } [hidden] { display:none!important; }
form { display:grid; gap:1rem; padding:var(--generic-donation-padding,1rem); border:1px solid var(--generic-donation-border,#c2cad6); border-radius:var(--generic-donation-radius,.75rem); background:var(--generic-donation-background,#fff); }
fieldset { display:flex; flex-wrap:wrap; gap:.6rem; border:0; padding:0; margin:0; }
legend { margin-bottom:.5rem; font-weight:600; } label { display:block; }
input,textarea,button { font:inherit; border-radius:.35rem; padding:.65rem; border:1px solid #6c7b90; }
input,textarea { display:block; width:100%; color:inherit; background:inherit; }
textarea { min-height:6rem; resize:vertical; } button { cursor:pointer; background:var(--generic-donation-accent,#2351a0); color:var(--generic-donation-button-text,#fff); }
button[aria-pressed=true] { outline:3px solid var(--generic-donation-accent,#2351a0); outline-offset:2px; } :focus-visible { outline:3px solid var(--generic-donation-focus,#ad5300); outline-offset:3px; }
:disabled { cursor:wait; opacity:.65; } [role=alert] { color:var(--generic-donation-error,#9d1821); } p,h2 { margin:.2rem 0; }
`;
