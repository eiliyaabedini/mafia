// Kotlin produces the same entry filename for the JS and Wasm distributions.
// Keep development maps locally; production serves the optimized executable only.
if (config.mode === 'production') {
  config.devtool = false;
}
