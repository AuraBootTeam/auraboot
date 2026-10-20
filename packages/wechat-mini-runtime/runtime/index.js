// Keep dependencies at the top level for the WeChat compiler dependency scan.
const logger = require("./logger.js");
const session = require("./session.js");
const environment = require("./environment.js");
const http = require("./http.js");
module.exports = { ...logger, ...session, ...environment, ...http };
