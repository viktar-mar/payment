import type { StripeElementsOptions } from '@stripe/stripe-js';
import { object } from '../contracts.js';
import { PaymentSdkError } from '../errors.js';

type Appearance = NonNullable<StripeElementsOptions['appearance']>;
function allowed(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Reflect.ownKeys(value).every(key => typeof key === 'string' && keys.includes(key));
}
/** Deliberately excludes rules, external fonts, URLs and arbitrary CSS. */
export function strictAppearance(value: StripeElementsOptions['appearance']): Appearance {
  try {
    if (value === undefined) return {};
    if (!object(value) || !allowed(value, ['theme', 'variables'])) throw 0;
    const result: Appearance = {};
    if (Object.hasOwn(value, 'theme')) {
      const theme = value.theme;
      if (theme !== 'stripe' && theme !== 'night' && theme !== 'flat') throw 0;
      result.theme = theme;
    }
    if (Object.hasOwn(value, 'variables')) {
      const variables = value.variables;
      if (!object(variables) || !allowed(variables, ['colorPrimary', 'colorBackground', 'colorText', 'colorDanger', 'fontFamily', 'spacingUnit', 'borderRadius'])) throw 0;
      const safe: NonNullable<Appearance['variables']> = {};
      for (const key of ['colorPrimary', 'colorBackground', 'colorText', 'colorDanger'] as const) {
        if (!Object.hasOwn(variables, key)) continue;
        const color = variables[key];
        if (typeof color !== 'string' || !/^#(?:[a-fA-F0-9]{3}|[a-fA-F0-9]{6})$/.test(color)) throw 0;
        safe[key] = color;
      }
      if (Object.hasOwn(variables, 'fontFamily')) {
        const font = variables.fontFamily;
        if (font !== 'system-ui' && font !== 'sans-serif') throw 0;
        safe.fontFamily = font;
      }
      for (const key of ['spacingUnit', 'borderRadius'] as const) {
        if (!Object.hasOwn(variables, key)) continue;
        const size = variables[key];
        if (typeof size !== 'string' || !/^(?:0|[1-9][0-9]?)px$/.test(size) || Number(size.slice(0, -2)) > (key === 'spacingUnit' ? 16 : 32)) throw 0;
        safe[key] = size;
      }
      result.variables = safe;
    }
    return result;
  } catch { throw new PaymentSdkError('INVALID_CONFIGURATION'); }
}
