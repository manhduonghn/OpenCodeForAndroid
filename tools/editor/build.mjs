// Builds the offline CodeMirror 6 bundle that ships in app/src/main/assets/editor.
//
// Node is only needed on a development machine or in the optional CI job; Gradle never runs
// npm. The generated assets are committed, so an Android build only needs the files that are
// already in the repository.
import { build } from "esbuild";
import { cp, mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const outdir = join(here, "..", "..", "app", "src", "main", "assets", "editor");
const check = process.argv.includes("--check");
const target = check ? join(here, ".check") : outdir;

async function listFiles(root) {
  const result = [];
  async function walk(dir) {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) await walk(path);
      else result.push(relative(root, path));
    }
  }
  await walk(root);
  return result.sort();
}

await rm(target, { recursive: true, force: true });
await mkdir(target, { recursive: true });

await build({
  entryPoints: [{ in: join(here, "src", "main.ts"), out: "editor" }],
  outdir: target,
  bundle: true,
  format: "esm",
  splitting: true,
  target: ["chrome90"],
  minify: true,
  sourcemap: false,
  legalComments: "none",
  // Dynamic imports become relative chunks; WebViewAssetLoader serves them from the same
  // https://appassets.androidplatform.net/editor/ origin.
  chunkNames: "chunks/[name]-[hash]",
  assetNames: "assets/[name]-[hash]",
  metafile: true,
  logLevel: "info",
});

// Small, hand-written files that are easier to review than a generated bundle.
await cp(join(here, "public"), target, { recursive: true });
await mkdir(join(target, "licenses"), { recursive: true });
for (const [source, name] of [
  [join(here, "node_modules", "@codemirror", "state", "LICENSE"), "CodeMirror-LICENSE"],
  [join(here, "node_modules", "@lezer", "common", "LICENSE"), "Lezer-LICENSE"],
  [join(here, "node_modules", "@lezer", "highlight", "LICENSE"), "Lezer-Highlight-LICENSE"],
]) {
  if (existsSync(source)) await cp(source, join(target, "licenses", name));
}

// A stable digest makes an accidental hand edit of a generated file visible.
const files = await listFiles(target);
const crypto = await import("node:crypto");
const digest = crypto.createHash("sha256");
for (const file of files) {
  if (file === "BUNDLE_SHA256") continue;
  const content = await readFile(join(target, file));
  digest.update(file);
  digest.update("\0");
  digest.update(content);
  digest.update("\0");
}
const hash = digest.digest("hex");
if (!check) await writeFile(join(target, "BUNDLE_SHA256"), hash + "\n");

if (check) {
  const current = join(here, "..", "..", "app", "src", "main", "assets", "editor");
  const expected = await listFiles(current).catch(() => []);
  const actual = await listFiles(target);
  let differs = expected.join("\n") !== actual.join("\n");
  for (const file of expected) {
    if (file === "BUNDLE_SHA256") continue;
    if (!actual.includes(file)) {
      differs = true;
      continue;
    }
    const a = await readFile(join(current, file));
    const b = await readFile(join(target, file)).catch(() => null);
    if (!b || !a.equals(b)) {
      differs = true;
      console.error(`changed: ${file}`);
    }
  }
  await rm(target, { recursive: true, force: true });
  if (differs) {
    console.error("editor bundle is out of date; run `npm run build` in tools/editor");
    process.exit(1);
  }
  console.log("editor bundle is up to date");
} else {
  console.log(`editor bundle: ${files.length} files, sha256 ${hash}`);
}
