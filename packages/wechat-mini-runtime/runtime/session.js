function createSession({ wxApi, tokenKey, invalidate = () => {} }) {
  let generation = 0;
  const token = () => wxApi.getStorageSync(tokenKey) || "";
  const snapshot = () => ({ generation, token: token() });
  const current = (issued) =>
    issued.generation === generation && issued.token === token();
  function replace(value) {
    generation += 1;
    if (value) wxApi.setStorageSync(tokenKey, value);
    else wxApi.removeStorageSync(tokenKey);
    invalidate();
  }
  return {
    token,
    snapshot,
    current,
    replace,
    clear: () => replace(""),
    clearIfCurrent(issued) {
      if (!current(issued)) return false;
      replace("");
      return true;
    },
  };
}
module.exports = { createSession };
