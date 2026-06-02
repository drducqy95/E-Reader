import { URL } from "node:url";
import fs from "node:fs";

const WEB_SERVICE_READER_DIR = new URL(
  "../../../modules/web-service/src/main/resources/web/reader",
  import.meta.url,
);
const REACT_DIST_DIR = new URL("../dist", import.meta.url);

console.log("> sync", WEB_SERVICE_READER_DIR.pathname);
fs.rmSync(WEB_SERVICE_READER_DIR, { force: true, recursive: true });
fs.mkdirSync(WEB_SERVICE_READER_DIR, { recursive: true });
fs.cpSync(REACT_DIST_DIR, WEB_SERVICE_READER_DIR, { recursive: true });
console.log("> cp success");
