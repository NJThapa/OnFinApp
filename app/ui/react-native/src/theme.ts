import { MD3DarkTheme, MD3LightTheme, type MD3Theme, useTheme } from 'react-native-paper';

// The app's design tokens: Material Design 3 colour roles built around the green brand colour.
// Screens read these through useTheme(); nothing else should hard-code colours.
export const lightTheme: MD3Theme = {
  ...MD3LightTheme,
  roundness: 3,
  colors: {
    ...MD3LightTheme.colors,
    primary: '#0B6E4F',
    onPrimary: '#FFFFFF',
    primaryContainer: '#B1F1D5',
    onPrimaryContainer: '#002117',
    secondary: '#4C6359',
    onSecondary: '#FFFFFF',
    secondaryContainer: '#CFE9DA',
    onSecondaryContainer: '#092017',
    background: '#F6F7F9',
    onBackground: '#101828',
    surface: '#FFFFFF',
    onSurface: '#101828',
    surfaceVariant: '#E4E8E5',
    onSurfaceVariant: '#5B645E',
    outline: '#717973',
    outlineVariant: '#D5DAD6',
    error: '#B42318',
    onError: '#FFFFFF',
    errorContainer: '#FEE4E2',
    onErrorContainer: '#7A271A',
    elevation: {
      level0: 'transparent',
      level1: '#FFFFFF',
      level2: '#F3F6F4',
      level3: '#EEF2F0',
      level4: '#ECF0EE',
      level5: '#E8EDEA',
    },
  },
};

export const darkTheme: MD3Theme = {
  ...MD3DarkTheme,
  roundness: 3,
  colors: {
    ...MD3DarkTheme.colors,
    primary: '#7FD9B6',
    onPrimary: '#003826',
    primaryContainer: '#005139',
    onPrimaryContainer: '#B1F1D5',
    secondary: '#B3CCBF',
    onSecondary: '#1F352C',
    secondaryContainer: '#354B41',
    onSecondaryContainer: '#CFE9DA',
    background: '#0F1412',
    onBackground: '#E6EAE7',
    surface: '#151B18',
    onSurface: '#E6EAE7',
    surfaceVariant: '#3A423D',
    onSurfaceVariant: '#B9C2BB',
    outline: '#8B938C',
    outlineVariant: '#3A423D',
    error: '#FDA29B',
    onError: '#55160C',
    errorContainer: '#7A271A',
    onErrorContainer: '#FEE4E2',
    elevation: {
      level0: 'transparent',
      level1: '#1A211E',
      level2: '#1E2622',
      level3: '#222B27',
      level4: '#242D29',
      level5: '#27312C',
    },
  },
};

/** Colours for price moves and ratings, which Material's colour roles do not cover. */
const marketColors = {
  light: { gain: '#067647', loss: '#B42318', flat: '#5B645E' },
  dark: { gain: '#75E0A7', loss: '#FDA29B', flat: '#B9C2BB' },
};

export function useMarketColors() {
  return useTheme().dark ? marketColors.dark : marketColors.light;
}
