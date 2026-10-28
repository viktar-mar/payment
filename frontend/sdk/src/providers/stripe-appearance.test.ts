import { describe, expect, it } from 'vitest';
import { strictAppearance } from './stripe-appearance.js';

describe('strictAppearance', () => {
  it('copies only approved bounded design tokens', () => {
    const input = { theme: 'night' as const, variables: { colorPrimary: '#abc', colorBackground: '#112233', colorText: '#fff', colorDanger: '#f00', fontFamily: 'system-ui', spacingUnit: '4px', borderRadius: '8px' } };
    const result = strictAppearance(input);
    expect(result).toEqual({ theme: 'night', variables: { colorPrimary: '#abc', colorBackground: '#112233', colorText: '#fff', colorDanger: '#f00', fontFamily: 'system-ui', spacingUnit: '4px', borderRadius: '8px' } });
    input.variables.colorPrimary = '#000';
    expect(result.variables?.colorPrimary).toBe('#abc');
  });
  it.each([
    { rules: { '.Input': { background: 'url(https://evil.test)' } } },
    { variables: { colorPrimary: 'url(https://evil.test)' } },
    { variables: { fontFamily: 'https://evil.test' } },
    { variables: { spacingUnit: '17px' } },
    { variables: { borderRadius: '33px' } },
    { variables: { colorText: 'red; background: url(x)' } },
    { variables: { fontSizeBase: '18px' } },
    { theme: 'other' }, null, [],
  ])('rejects arbitrary CSS, URLs and unapproved keys: %j', input => {
    expect(() => strictAppearance(input as never)).toThrowError(expect.objectContaining({ code: 'INVALID_CONFIGURATION' }));
  });
  it('accepts omitted appearance as an empty safe presentation', () => { expect(strictAppearance(undefined)).toEqual({}); });
  it('validates and forwards the same theme/font values when caller properties are accessors', () => {
    let themeReads = 0; let fontReads = 0;
    const input = { get theme() { return (++themeReads === 1 ? 'stripe' : 'unsafe-theme') as 'stripe'; }, variables: { get fontFamily() { return ++fontReads === 1 ? 'system-ui' : 'url(https://evil.test)'; } } };
    expect(strictAppearance(input)).toEqual({ theme: 'stripe', variables: { fontFamily: 'system-ui' } });
  });
});
