import type {Locator} from '@playwright/test';

export type ProfileAction = 'initial' | 'method';
export type ClickLimits = {deadline:number;signal:AbortSignal};
export type WaitForProfileClick = (action:ProfileAction, limits:ClickLimits)=>Promise<void>;

/** The native button is clicked only on receipt of the current operator gesture. */
export async function forwardProfileClick(button:Locator, action:ProfileAction, limits:ClickLimits,
    waitForClick:WaitForProfileClick, validate:()=>void) {
  await waitForClick(action,limits);
  validate();
  const timeout=limits.deadline-Date.now();
  if(limits.signal.aborted || timeout<=0 || !await button.isEnabled())throw Error('Profile click expired');
  validate();
  let remaining=limits.deadline-Date.now();
  if(remaining<=0||limits.signal.aborted)throw Error('Profile click expired');
  // Leaving an Elements field can resize its frame. Move focus before
  // Playwright chooses the coordinates for the single trusted click.
  await button.focus({timeout:Math.min(remaining,5000)});
  validate();
  remaining=limits.deadline-Date.now();
  if(remaining<=0||limits.signal.aborted)throw Error('Profile click expired');
  await button.click({timeout:Math.min(remaining,5000)});
}
