import { defineConfig, type UserConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // Base configuration for both environments
  const config: UserConfig = {
    plugins: [react()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    css: {
      modules: {
        localsConvention: 'camelCase',
      },
      postcss: './postcss.config.cjs',
    },
    server: {
      // These settings are for your EC2 instance to work behind Nginx
      host: '0.0.0.0',
      allowedHosts: ['calc.americanhousing.fund'],
      hmr: true,
      warmup: {
        clientFiles: ['./src/main.tsx', './src/App.tsx'],
      },
      watch: {
        ignored: ['**/node_modules/**', '**/.git/**', '**/dist/**'],
      },
    },
    optimizeDeps: {
      include: ['react', 'react-dom', 'react-router-dom'],
    },
  };

  // Add the proxy ONLY for local development
  if (mode === 'development' && config.server) {
    // VITE_BACKEND_PROXY_TARGET points at the backend.
    //   - Inside docker compose: http://backend:8080 (Compose service DNS)
    //   - Running vite on the host: http://localhost:8080
    const proxyTarget = process.env.VITE_BACKEND_PROXY_TARGET || 'http://localhost:8080';
    config.server.proxy = {
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
      },
    };
  }

  return config;
});