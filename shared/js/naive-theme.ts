import type { GlobalThemeOverrides } from 'naive-ui'

export const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2D7FF9',
    primaryColorHover: '#5AA3FF',
    primaryColorPressed: '#1E63D6',
    primaryColorSuppl: '#2D7FF9',
    borderRadius: '10px',
    borderRadiusSmall: '8px',
    textColorBase: '#1E2632',
    textColor1: '#1E2632',
    textColor2: '#5A6474',
    textColor3: '#98A1AF',
    borderColor: '#E7E9EE',
    dividerColor: '#E7E9EE',
    bodyColor: '#F6F7FA',
    cardColor: '#FFFFFF',
    popoverColor: '#FFFFFF',
  },
  Button: { borderRadiusMedium: '10px', fontWeight: '600' },
  Tag: { borderRadius: '999px' },
}