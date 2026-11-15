// Pre-build step: strip the comments from the JS/HTML/CSS inside assets/web.
// It takes effect only in the CI build output. It does not change the repo source.
// terser handles the .js (compress/mangle all off; comments removed only).
// HTML/CSS use a simple regex to remove <!-- --> and /* */.
// .json is skipped on purpose (JSON has no comment syntax; deleting // or /* */
// carelessly would hit legal string content). The third-party compressed
// libraries (blockly_compressed.js and the other *_compressed.js / blockly.css)
// are skipped because they have almost no comments to remove.
//
// 2026-09-09 guard: this script edits the source in place (it does not write to the build output).
// Without --ci it is a no-op, so an accidental local run does not pollute the working tree.
// CI call: node scripts/strip-web-comments.js --ci
"use strict";

if (!process.argv.includes("--ci")) {
  console.log("strip-web-comments: local mode refused (it edits the source in place). Add --ci for CI.");
  process.exit(0);
}

const fs = require("fs");
const path = require("path");
const { minify } = require("terser");

const WEB_DIR = path.join(__dirname, "..", "app", "src", "main", "assets", "web");
const SKIP_FILES = new Set([
  "blockly_compressed.js",
  "blocks_compressed.js",
  // 2026-09-09 added: the vendor i18n (terser beautify changes the line numbers a lot, which breaks the logcat line-number cross-reference)
  "blockly_msg_en.js",
  "blockly_msg_zh-hant.js",
]);

async function stripJs(filePath) {
  const src = fs.readFileSync(filePath, "utf8");
  const result = await minify(src, {
    compress: false,
    mangle: false,
    format: { comments: false, beautify: true, indent_level: 2 },
  });
  if (result.error) {
    throw result.error;
  }
  fs.writeFileSync(filePath, result.code, "utf8");
}

function stripHtml(filePath) {
  const src = fs.readFileSync(filePath, "utf8");
  const out = src.replace(/<!--[\s\S]*?-->/g, "");
  fs.writeFileSync(filePath, out, "utf8");
}

function stripCss(filePath) {
  const src = fs.readFileSync(filePath, "utf8");
  const out = src.replace(/\/\*[\s\S]*?\*\//g, "");
  fs.writeFileSync(filePath, out, "utf8");
}

async function walk(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      await walk(full);
      continue;
    }
    if (SKIP_FILES.has(entry.name)) {
      console.log("skip:", full);
      continue;
    }
    if (entry.name.endsWith(".js")) {
      console.log("strip js:", full);
      await stripJs(full);
    } else if (entry.name.endsWith(".html")) {
      console.log("strip html:", full);
      stripHtml(full);
    } else if (entry.name.endsWith(".css")) {
      console.log("strip css:", full);
      stripCss(full);
    }
  }
}

walk(WEB_DIR).then(() => {
  console.log("done");
}).catch((err) => {
  console.error("strip-web-comments failed:", err);
  process.exit(1);
});
