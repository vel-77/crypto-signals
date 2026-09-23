/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Overrides the API base path; defaults to /api so the dev proxy and nginx both work unchanged. */
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
