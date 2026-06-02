import { URL } from "node:url";
import fs from "node:fs";

const WEB_SERVICE_ADMIN_DIR = new URL(
  "../../../modules/web-service/src/main/resources/web/admin",
  import.meta.url,
);
const VUE_DIST_DIR = new URL("../dist", import.meta.url);

console.log("> sync", WEB_SERVICE_ADMIN_DIR.pathname);
fs.rmSync(WEB_SERVICE_ADMIN_DIR, { force: true, recursive: true });
fs.mkdirSync(WEB_SERVICE_ADMIN_DIR, { recursive: true });
fs.cpSync(VUE_DIST_DIR, WEB_SERVICE_ADMIN_DIR, { recursive: true });
console.log("> cp success");
