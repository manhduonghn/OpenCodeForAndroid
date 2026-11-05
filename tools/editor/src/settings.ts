import {
  closeBrackets,
  closeBracketsKeymap,
  autocompletion,
  completionKeymap,
  completeAnyWord,
} from "@codemirror/autocomplete";
import {
  defaultKeymap,
  history,
  historyKeymap,
  indentWithTab,
} from "@codemirror/commands";
import {
  bracketMatching,
  foldGutter,
  foldKeymap,
  indentOnInput,
  indentUnit,
} from "@codemirror/language";
import { highlightSelectionMatches } from "@codemirror/search";
import { EditorState, type Extension } from "@codemirror/state";
import {
  drawSelection,
  dropCursor,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  rectangularSelection,
  crosshairCursor,
} from "@codemirror/view";

export interface EditorSettings {
  fontSize: number;
  lineHeight: number;
  fontFamily: string;
  tabSize: number;
  softTab: boolean;
  wordWrap: boolean;
  lineNumbers: boolean;
  relativeLineNumbers: boolean;
  highlightActiveLine: boolean;
  bracketMatching: boolean;
  autoCloseBrackets: boolean;
  autoIndent: boolean;
  foldGutter: boolean;
  indentGuides: boolean;
  rainbowBrackets: boolean;
  autocomplete: string;
  showWhitespace: boolean;
}

export const DEFAULT_SETTINGS: EditorSettings = {
  fontSize: 14,
  lineHeight: 1.45,
  fontFamily: "monospace",
  tabSize: 4,
  softTab: true,
  wordWrap: false,
  lineNumbers: true,
  relativeLineNumbers: false,
  highlightActiveLine: true,
  bracketMatching: true,
  autoCloseBrackets: true,
  autoIndent: true,
  foldGutter: true,
  indentGuides: true,
  rainbowBrackets: false,
  autocomplete: "local",
  showWhitespace: false,
};

export function typography(settings: EditorSettings): Extension {
  return EditorView.theme({
    "&": {
      fontSize: `${Math.max(8, settings.fontSize)}px`,
      lineHeight: String(settings.lineHeight || 1.45),
    },
    ".cm-scroller": {
      fontFamily: settings.fontFamily || "monospace",
      lineHeight: String(settings.lineHeight || 1.45),
    },
  });
}

/** Feature extensions that do not change the document schema. */
export function editorFeatures(settings: EditorSettings): Extension {
  const extensions: Extension[] = [
    highlightSpecialChars(),
    drawSelection(),
    dropCursor(),
    EditorState.allowMultipleSelections.of(true),
    rectangularSelection(),
    crosshairCursor(),
    EditorState.tabSize.of(Math.max(1, settings.tabSize)),
    indentUnit.of(settings.softTab ? " ".repeat(Math.max(1, settings.tabSize)) : "\t"),
    history(),
    keymap.of([
      ...closeBracketsKeymap,
      ...defaultKeymap,
      ...historyKeymap,
      ...foldKeymap,
      ...completionKeymap,
      indentWithTab,
    ]),
  ];

  if (settings.lineNumbers) extensions.push(lineNumbers());
  if (settings.highlightActiveLine) {
    extensions.push(highlightActiveLine(), highlightActiveLineGutter());
  }
  if (settings.bracketMatching) extensions.push(bracketMatching());
  if (settings.autoCloseBrackets) extensions.push(closeBrackets());
  if (settings.autoIndent) extensions.push(indentOnInput());
  if (settings.foldGutter) extensions.push(foldGutter({ openText: "⌄", closedText: "›" }));
  if (settings.wordWrap) extensions.push(EditorView.lineWrapping);
  if (settings.autocomplete !== "off") {
    extensions.push(
      autocompletion({
        activateOnTyping: settings.autocomplete !== "off",
        override: [completeAnyWord],
      }),
    );
  }
  extensions.push(highlightSelectionMatches());
  return extensions;
}

export function readOnlyExtension(readOnly: boolean): Extension {
  return [EditorState.readOnly.of(readOnly), EditorView.editable.of(!readOnly)];
}
