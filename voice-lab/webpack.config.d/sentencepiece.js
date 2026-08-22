// Browser-only shims for @sctg/sentencepiece-js.
// The package exposes browser APIs but also contains optional Node.js code paths.
// Webpack 5 no longer polyfills Node core modules, so mark those optional paths
// unavailable in the Kotlin/JS browser bundle.
config.resolve = config.resolve || {};
config.resolve.fallback = {
  ...(config.resolve.fallback || {}),
  fs: false,
  module: false,
};
