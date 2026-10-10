#!/usr/bin/env node
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const root = path.resolve(__dirname, "..");
function digest(directory) {
  const hash = crypto.createHash("sha256");
  function walk(dir, prefix = "") {
    for (const name of fs.readdirSync(dir).sort()) {
      const filename = path.join(dir, name),
        relative = prefix + name;
      if (fs.lstatSync(filename).isSymbolicLink())
        throw new Error("Runtime sources cannot contain symlinks");
      if (fs.statSync(filename).isDirectory()) walk(filename, relative + "/");
      else {
        hash.update(relative);
        hash.update(fs.readFileSync(filename));
      }
    }
  }
  walk(directory);
  return hash.digest("hex");
}
function copy(source, destination) {
  if (fs.existsSync(destination)) {
    if (
      fs.lstatSync(destination).isSymbolicLink() ||
      !fs.existsSync(path.join(destination, ".aura-vendor.json"))
    )
      throw new Error("Refuse to overwrite unowned vendor directory");
    fs.rmSync(destination, { recursive: true });
  }
  fs.mkdirSync(destination, { recursive: true });
  fs.cpSync(source, destination, {
    recursive: true,
    filter: (entry) => path.basename(entry) !== "materialize.cjs",
  });
  const receipt = {
    package: require("../package.json").name,
    version: require("../package.json").version,
    sha256: digest(destination),
  };
  fs.writeFileSync(
    path.join(destination, ".aura-vendor.json"),
    JSON.stringify(receipt, null, 2) + "\n",
  );
  return receipt;
}
function materialize(product) {
  product = fs.realpathSync(product);
  if (!fs.existsSync(path.join(product, "mini-program/app.json")))
    throw new Error("Product mini-program required");
  const receipt = copy(
    path.join(root, "runtime"),
    path.join(product, "mini-program/vendor/wechat-mini-runtime"),
  );
  copy(
    path.join(root, "runtime"),
    path.join(product, "scripts/vendor/wechat-mini-runtime/runtime"),
  );
  copy(
    path.join(root, "tools"),
    path.join(product, "scripts/vendor/wechat-mini-runtime/tools"),
  );
  fs.writeFileSync(
    path.join(product, "scripts/vendor/wechat-mini-runtime/package.json"),
    JSON.stringify(
      {
        name: require("../package.json").name,
        version: require("../package.json").version,
        type: "commonjs",
        private: true,
      },
      null,
      2,
    ) + "\n",
  );
  return receipt;
}
if (require.main === module) {
  if (!process.argv[2]) throw new Error("Product checkout required");
  console.log(JSON.stringify(materialize(process.argv[2])));
}
module.exports = { materialize, digest };
