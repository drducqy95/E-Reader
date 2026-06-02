/** @type {import('tailwindcss').Config} */
module.exports = {
    darkMode: 'class',
    content: [
        "./index.html",
        "./src/**/*.{vue,js,ts,jsx,tsx}",
    ],
    theme: {
        extend: {
            colors: {
                theme: {
                    paper: { bg: 'var(--theme-paper-bg)', text: 'var(--theme-paper-text)' },
                    sepia: { bg: 'var(--theme-sepia-bg)', text: 'var(--theme-sepia-text)' },
                    night: { bg: 'var(--theme-night-bg)', text: 'var(--theme-night-text)' },
                    forest: { bg: 'var(--theme-forest-bg)', text: 'var(--theme-forest-text)' },
                    ocean: { bg: 'var(--theme-ocean-bg)', text: 'var(--theme-ocean-text)' },
                },
                navy: {
                    dark: 'var(--color-dark-bg)',
                    card: 'var(--color-dark-card)'
                },
                warm: {
                    paper: 'var(--color-light-bg)',
                    accent: 'var(--color-light-accent)'
                }
            }
        },
    },
    plugins: [],
}
