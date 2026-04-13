module.exports = {
  content: ['./app/**/*.{js,ts,jsx,tsx}', './public/**/*.html'],
  darkMode: 'class', // 关键配置！
  theme: {
    extend: {
      animation: {
        fadeIn: 'fadeIn 0.2s ease-in-out',
      },
      keyframes: {
        fadeIn: {
          '0%': { opacity: '0', transform: 'translateY(-10px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
      },
    },
  },
  plugins: [require('tailwindcss-animate'), require('tailwind-scrollbar')({ nocompatible: true })],
};
