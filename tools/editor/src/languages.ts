import type { Extension } from "@codemirror/state";

export type LanguageLoader = () => Promise<Extension>;

const plaintext: LanguageLoader = async () => [];

/**
 * Language id -> lazy loader. Kotlin resolves the language from the filename and sends the id;
 * the page never guesses again, so both sides always agree.
 */
const LOADERS: Record<string, LanguageLoader> = {
  plaintext,
  text: plaintext,
  kotlin: () => import("./langs/legacy").then((m) => m.kotlinSupport()),
  java: () => import("./langs/compiled").then((m) => m.javaSupport()),
  xml: () => import("./langs/markup").then((m) => m.xmlSupport()),
  json: () => import("./langs/data").then((m) => m.jsonSupport()),
  jsonc: () => import("./langs/data").then((m) => m.jsonSupport()),
  yaml: () => import("./langs/data").then((m) => m.yamlSupport()),
  markdown: () => import("./langs/markup").then((m) => m.markdownSupport()),
  javascript: () => import("./langs/javascript").then((m) => m.javascriptSupport()),
  typescript: () => import("./langs/javascript").then((m) => m.typescriptSupport()),
  jsx: () => import("./langs/javascript").then((m) => m.jsxSupport()),
  tsx: () => import("./langs/javascript").then((m) => m.tsxSupport()),
  html: () => import("./langs/markup").then((m) => m.htmlSupport()),
  css: () => import("./langs/styles").then((m) => m.cssSupport()),
  scss: () => import("./langs/styles").then((m) => m.scssSupport()),
  less: () => import("./langs/styles").then((m) => m.lessSupport()),
  shell: () => import("./langs/legacy").then((m) => m.shellSupport()),
  python: () => import("./langs/compiled").then((m) => m.pythonSupport()),
  groovy: () => import("./langs/legacy").then((m) => m.groovySupport()),
  properties: () => import("./langs/legacy").then((m) => m.propertiesSupport()),
  toml: () => import("./langs/legacy").then((m) => m.tomlSupport()),
  dockerfile: () => import("./langs/legacy").then((m) => m.dockerfileSupport()),
  diff: () => import("./langs/legacy").then((m) => m.diffSupport()),
  c: () => import("./langs/legacy").then((m) => m.cSupport()),
  cpp: () => import("./langs/compiled").then((m) => m.cppSupport()),
  go: () => import("./langs/compiled").then((m) => m.goSupport()),
  rust: () => import("./langs/compiled").then((m) => m.rustSupport()),
  sql: () => import("./langs/compiled").then((m) => m.sqlSupport()),
  php: () => import("./langs/compiled").then((m) => m.phpSupport()),
  csharp: () => import("./langs/legacy").then((m) => m.csharpSupport()),
  scala: () => import("./langs/legacy").then((m) => m.scalaSupport()),
  dart: () => import("./langs/legacy").then((m) => m.dartSupport()),
  objectivec: () => import("./langs/legacy").then((m) => m.objectiveCSupport()),
  ini: () => import("./langs/legacy").then((m) => m.propertiesSupport()),
  makefile: () => import("./langs/legacy").then((m) => m.shellSupport()),
  cmake: () => import("./langs/legacy").then((m) => m.shellSupport()),
  nginx: () => import("./langs/legacy").then((m) => m.propertiesSupport()),
};

export function languageLoader(id: string): LanguageLoader {
  return LOADERS[id] || plaintext;
}

export function hasLanguage(id: string): boolean {
  return Object.prototype.hasOwnProperty.call(LOADERS, id);
}
