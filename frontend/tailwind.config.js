/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // Alta Monochrome Editorial Gallery semantic palette
        oat: {
          50: '#FAFAFA',
          100: '#F5F5F5',
          200: '#EBEBEB',
          300: '#E5E5E5',
          400: '#CCCCCC',
        },
        charcoal: {
          DEFAULT: '#000000',
          hover: '#262626',
          soft: '#333333',
        },
        terracotta: {
          DEFAULT: '#111111',
          light: '#F2F2F2',
          dark: '#000000',
        },
        warmgray: '#666666',
        alta: {
          bg: '#FFFFFF',
          surface: '#F5F5F5',
          pill: '#F2F2F2',
          border: '#E5E5E5',
          ink: '#000000',
          muted: '#666666',
          taobao: '#FF5000',
          jd: '#E1251B',
        },
      },
      fontFamily: {
        serif: ['"Inter"', '"Noto Sans SC"', '"Helvetica Neue"', 'sans-serif'],
        display: ['"Fraunces"', '"Noto Serif SC"', 'Georgia', 'serif'],
        sans: ['"Inter"', '"Noto Sans SC"', '"Helvetica Neue"', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      boxShadow: {
        editorial: '0 6px 24px -12px rgba(0, 0, 0, 0.08), 0 1px 4px rgba(0, 0, 0, 0.03)',
        card: '0 2px 12px -6px rgba(0, 0, 0, 0.06)',
        float: '0 16px 40px -12px rgba(0, 0, 0, 0.16)',
        dock: '0 8px 24px rgba(31, 38, 135, 0.14), inset 0 4px 20px rgba(255, 255, 255, 0.45)',
      },
    },
  },
  plugins: [],
};
