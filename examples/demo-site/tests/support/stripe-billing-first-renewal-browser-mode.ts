/** Only the dedicated headed Chrome project may pause before the owned Pay click. */
export const firstRenewalBrowserMode = (projectName: string): {manualPay:true} | undefined =>
  projectName === 'owned-manual-chrome' ? {manualPay:true} : undefined;
