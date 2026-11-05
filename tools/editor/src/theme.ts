import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { EditorView } from "@codemirror/view";
import { tags as t } from "@lezer/highlight";
import type { Extension } from "@codemirror/state";

export interface EditorTheme {
  protocol: number;
  dark: boolean;
  colors: Record<string, string>;
  syntax: Record<string, string>;
  markdown: Record<string, string>;
}

function fallback(map: Record<string, string>, key: string, value: string): string {
  return map[key] || value;
}

/** Build the view theme and the Lezer highlight style from the native Material You palette. */
export function buildTheme(theme: EditorTheme): Extension {
  const c = theme.colors || {};
  const s = theme.syntax || {};
  const m = theme.markdown || {};
  const dark = !!theme.dark;

  const root = document.documentElement;
  const variables: Record<string, string> = {
    "--editor-background": fallback(c, "background", dark ? "#111318" : "#fffbfe"),
    "--editor-foreground": fallback(c, "foreground", dark ? "#e6e1e5" : "#1c1b1f"),
    "--editor-gutter": fallback(c, "gutter", dark ? "#16181e" : "#f3edf7"),
    "--editor-gutter-text": fallback(c, "gutterText", dark ? "#8a8f9a" : "#49454f"),
    "--editor-caret": fallback(c, "caret", dark ? "#d0bcff" : "#6750a4"),
    "--editor-selection": fallback(c, "selection", "rgba(103, 80, 164, 0.35)"),
    "--editor-active-line": fallback(c, "activeLine", "rgba(28, 27, 31, 0.05)"),
    "--editor-search": fallback(c, "searchMatch", dark ? "#633b48" : "#ffd8e4"),
    "--editor-search-selected": fallback(c, "searchMatchSelected", dark ? "#7d5260" : "#eaddff"),
    "--editor-bracket": fallback(c, "bracketMatch", dark ? "#938f99" : "#79747e"),
    "--editor-indent": fallback(c, "indentGuide", dark ? "#49454f" : "#cac4d0"),
    "--editor-invalid": fallback(c, "invalid", dark ? "#f2b8b5" : "#b3261e"),
    "--editor-fold": fallback(c, "foldPlaceholder", dark ? "#2b2930" : "#e7e0ec"),
  };
  for (const [name, value] of Object.entries(variables)) root.style.setProperty(name, value);

  const view = EditorView.theme(
    {
      "&": {
        backgroundColor: variables["--editor-background"],
        color: variables["--editor-foreground"],
      },
      ".cm-content": { caretColor: variables["--editor-caret"] },
      ".cm-cursor, .cm-dropCursor": { borderLeftColor: variables["--editor-caret"] },
      ".cm-selectionBackground, .cm-content ::selection": {
        backgroundColor: variables["--editor-selection"],
      },
      ".cm-activeLine": { backgroundColor: variables["--editor-active-line"] },
      ".cm-gutters": {
        backgroundColor: variables["--editor-gutter"],
        color: variables["--editor-gutter-text"],
        border: "none",
      },
      ".cm-activeLineGutter": { backgroundColor: "transparent" },
      ".cm-searchMatch": {
        backgroundColor: variables["--editor-search"],
        outline: `1px solid ${variables["--editor-search-selected"]}`,
      },
      ".cm-searchMatch.cm-searchMatch-selected": {
        backgroundColor: variables["--editor-search-selected"],
      },
      ".cm-matchingBracket": {
        outline: `1px solid ${variables["--editor-bracket"]}`,
        backgroundColor: "transparent",
      },
      ".cm-nonmatchingBracket": { color: variables["--editor-invalid"] },
      ".cm-foldPlaceholder": {
        backgroundColor: variables["--editor-fold"],
        border: "none",
        color: variables["--editor-gutter-text"],
      },
    },
    { dark },
  );

  const highlight = HighlightStyle.define(
    [
      {
        tag: [t.comment, t.lineComment, t.blockComment, t.docComment, t.meta, t.annotation],
        color: fallback(s, "comment", variables["--editor-gutter-text"]),
        fontStyle: "italic",
      },
      {
        tag: [
          t.keyword, t.controlKeyword, t.definitionKeyword, t.modifier,
          t.operatorKeyword, t.self, t.moduleKeyword,
        ],
        color: fallback(s, "keyword", variables["--editor-caret"]),
      },
      {
        tag: [t.string, t.special(t.string), t.regexp, t.character, t.attributeValue],
        color: fallback(s, "string", fallback(m, "code", variables["--editor-caret"])),
      },
      {
        tag: [t.number, t.integer, t.float, t.bool, t.null, t.atom],
        color: fallback(s, "primitive", variables["--editor-caret"]),
      },
      {
        tag: [t.propertyName, t.attributeName, t.labelName],
        color: fallback(s, "property", variables["--editor-foreground"]),
      },
      {
        tag: [t.typeName, t.className, t.namespace, t.tagName],
        color: fallback(s, "type", variables["--editor-caret"]),
      },
      {
        tag: [t.variableName, t.definition(t.variableName), t.local(t.variableName)],
        color: fallback(s, "variable", variables["--editor-foreground"]),
      },
      {
        tag: [t.function(t.variableName), t.function(t.propertyName), t.macroName],
        color: fallback(s, "function", fallback(m, "code", variables["--editor-caret"])),
      },
      { tag: [t.operator], color: fallback(s, "operator", variables["--editor-gutter-text"]) },
      {
        tag: [
          t.punctuation, t.bracket, t.separator, t.squareBracket,
          t.paren, t.brace, t.angleBracket,
        ],
        color: fallback(s, "punctuation", variables["--editor-gutter-text"]),
      },
      { tag: [t.invalid], color: fallback(s, "invalid", variables["--editor-invalid"]) },
      {
        tag: [t.heading, t.heading1, t.heading2, t.heading3, t.heading4, t.heading5, t.heading6],
        color: fallback(m, "heading", variables["--editor-caret"]),
        fontWeight: "600",
      },
      { tag: [t.strong], color: fallback(m, "strong", variables["--editor-caret"]), fontWeight: "600" },
      { tag: [t.emphasis], color: fallback(m, "emph", variables["--editor-foreground"]), fontStyle: "italic" },
      { tag: [t.link, t.url], color: fallback(m, "link", variables["--editor-caret"]) },
      { tag: [t.monospace], color: fallback(m, "code", variables["--editor-caret"]) },
      { tag: [t.quote], color: fallback(m, "quote", variables["--editor-gutter-text"]), fontStyle: "italic" },
      { tag: [t.list], color: fallback(m, "listItem", variables["--editor-caret"]) },
      {
        tag: [t.contentSeparator, t.horizontalRule],
        color: fallback(m, "rule", variables["--editor-indent"]),
      },
      { tag: [t.image], color: fallback(m, "image", variables["--editor-caret"]) },
    ],
    { themeType: dark ? "dark" : "light" },
  );

  return [view, syntaxHighlighting(highlight)];
}
