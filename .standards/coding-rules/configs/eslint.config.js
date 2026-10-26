// ESLint — JavaScript/TypeScript lint
// https://eslint.org

import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactPlugin from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';

export default tseslint.config(
    {
        ignores: ['dist/**', 'node_modules/**', 'coverage/**', '*.config.js'],
    },
    js.configs.recommended,
    ...tseslint.configs.strict,
    {
        rules: {
            // TypeScript
            '@typescript-eslint/no-explicit-any': 'error',
            '@typescript-eslint/explicit-function-return-type': 'error',
            '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
            '@typescript-eslint/consistent-type-imports': 'error',
            '@typescript-eslint/no-non-null-assertion': 'error',

            // General
            'no-console': ['error', { allow: ['warn', 'error'] }],
            'prefer-const': 'error',
            'no-var': 'error',
            'eqeqeq': ['error', 'always'],
            'no-throw-literal': 'error',
            'no-return-await': 'error',
            'no-duplicate-imports': 'error',
            'no-unreachable': 'error',
            'no-self-compare': 'error',
            'no-template-curly-in-string': 'error',
        },
    },
    {
        files: ['**/*.{ts,tsx}'],
        languageOptions: {
            parserOptions: {
                projectService: true,
            },
        },
    },
    {
        files: ['**/*.tsx'],
        plugins: {
            react: reactPlugin,
            'react-hooks': reactHooks,
        },
        rules: {
            ...reactPlugin.configs.recommended.rules,
            ...reactHooks.configs.recommended.rules,
            'react/react-in-jsx-scope': 'off',
            'react/prop-types': 'off',
        },
    },
    {
        files: ['tests/**', '**/*.test.ts', '**/*.spec.ts'],
        rules: {
            '@typescript-eslint/no-explicit-any': 'off',
            'no-console': 'off',
        },
    }
);
