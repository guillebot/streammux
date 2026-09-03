/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Release version from CI build-arg / build-app-version.sh, baked at build time. */
  readonly VITE_APP_VERSION?: string;
  /** Baked into "New job" / catalog template `routeAppConfig.streamProperties.bootstrap.servers` at build time. */
  readonly VITE_EXAMPLE_KAFKA_BOOTSTRAP?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
