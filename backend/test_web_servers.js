const fs = require("node:fs");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const source = fs.readFileSync(__dirname + "/web/app.js", "utf8");
const storage = new Map();
const context = {
  window: { addEventListener() {} }, document: { addEventListener() {} },
  location: { hash: "#/overview" },
  localStorage: { getItem: key => storage.get(key) || null, removeItem: key => storage.delete(key) },
  console, setTimeout, clearTimeout, setInterval, clearInterval
};
vm.createContext(context);
vm.runInContext(source.replace(/\}\)\(\);\s*$/, "window.test = { syncServers, cache, renderOverview, renderTools, renderSecurity, renderDisks }; })();"), context);
const app = context.window.test;
const nas = { id: "nas", name: "NAS" };
const extra = { id: "srv_example", name: "<备用服务器>" };
app.syncServers([nas, extra]);
app.cache.overview = { nas: { status: "up" }, srv_example: { status: "up", disks: [{ mount: "/", percent: 30 }] }, servers: [nas, extra], alerts: [], timestamp: "2026-10-04T00:00:00" };
assert.match(app.renderOverview(), /card-host-srv_example/);
assert.doesNotMatch(app.renderOverview(), /card-host-tencent/);
assert.match(app.renderTools("srv_example"), /&lt;备用服务器&gt;/);
assert.match(app.renderSecurity("srv_example"), /尚未接入独立安全采集器/);
assert.match(app.renderDisks(), /&lt;备用服务器&gt;/);
app.cache.detail_srv_example = { secret: "old" };
storage.set("nas_containers_page_srv_example", "2");
storage.set("nas_card_order_services/srv_example", "[]");
context.location.hash = "#/services/srv_example";
app.syncServers([nas]);
assert.equal(app.cache.detail_srv_example, undefined);
assert.equal(app.cache.overview.srv_example, undefined);
assert.equal(app.cache.overview.servers.length, 1);
assert.equal(storage.size, 0);
assert.equal(context.location.hash, "overview");
assert.doesNotMatch(app.renderOverview(), /card-host-srv_example/);
console.log("Web dynamic cards/tabs/security/disks, escaping, deletion cleanup and route fallback: PASS");
