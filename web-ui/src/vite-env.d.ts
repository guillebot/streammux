/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Baked into "New job" / catalog template `routeAppConfig.streamProperties.bootstrap.servers` at build time. */
  readonly VITE_EXAMPLE_KAFKA_BOOTSTRAP?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
