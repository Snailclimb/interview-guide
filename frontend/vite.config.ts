import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import wasm from 'vite-plugin-wasm'
import topLevelAwait from 'vite-plugin-top-level-await'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    wasm(),
    topLevelAwait(),
    react(),
  ],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'ui-vendor': ['framer-motion', 'lucide-react'],
          'syntax-highlighter': ['react-syntax-highlighter'],
        },
      },
    },
  },
  server: {
      host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
    // 忽略 @ricky0123/vad-web 的 sourcemap 警告
    sourcemapIgnoreList: (relativeSourcePath) => {
      return relativeSourcePath.includes('node_modules/.pnpm/@ricky0123+vad-web');
    },
  },
  optimizeDeps: {
    // No need to optimize vad-web since we load it via script tag
  },
});
