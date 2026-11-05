import { redo, redoDepth, undo, undoDepth } from "@codemirror/commands";
import {
  findNext,
  findPrevious,
  SearchQuery,
  search,
  setSearchQuery,
} from "@codemirror/search";
import {
  Compartment,
  EditorState,
  Text,
  type Extension,
} from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { languageLoader } from "./languages";
import {
  DEFAULT_SETTINGS,
  editorFeatures,
  readOnlyExtension,
  typography,
  type EditorSettings,
} from "./settings";
import { buildTheme, type EditorTheme } from "./theme";

const PROTOCOL = 1;
const MAX_TEXT = 4 * 1024 * 1024;

declare global {
  interface Window {
    OpenCodeEditor?: Record<string, (payload?: unknown) => void>;
    OpenCodeEditorBridge?: Record<string, (payload: string) => void>;
    __ocEditorSupported?: boolean;
  }
}

let view: EditorView | null = null;
let settings: EditorSettings = { ...DEFAULT_SETTINGS };
let theme: EditorTheme | null = null;
let readOnly = false;
let languageId = "plaintext";
let languageSeq = 0;
let savedDoc: Text = Text.empty;
let savedHash = "";
let version = 0;
let lastSnapshot: { version: number; doc: Text } | null = null;
let loading = false;
let initialized = false;
let readySent = false;
let dirty = false;
let cursorTimer: number | null = null;
let lastQuery: SearchQuery | null = null;

const themeCompartment = new Compartment();
const languageCompartment = new Compartment();
const settingsCompartment = new Compartment();
const readOnlyCompartment = new Compartment();
const searchCompartment = new Compartment();

function bridge(): Record<string, (payload: string) => void> | null {
  return window.OpenCodeEditorBridge || null;
}

function json(value: unknown): string {
  return JSON.stringify(value);
}

function error(code: string, message: string): void {
  const target = document.getElementById("error");
  if (target) {
    target.hidden = false;
    target.textContent = message;
  }
  try {
    bridge()?.onError(json({ code, message: String(message).slice(0, 2000) }));
  } catch (e) {
    // Nothing else can be reported before the bridge exists.
  }
}

function sendReady(): void {
  if (readySent) return;
  readySent = true;
  try {
    bridge()?.onReady(json({
      protocol: PROTOCOL,
      userAgent: navigator.userAgent,
      capabilities: {
        resizeObserver: !!window.ResizeObserver,
        intl: typeof Intl !== "undefined",
        css: !!window.CSS,
      },
    }));
  } catch (e) {
    error("ready", String(e));
  }
}

function updateDirty(state: EditorState): void {
  const next = !state.doc.eq(savedDoc);
  if (next === dirty) return;
  dirty = next;
  try {
    bridge()?.onDirtyChanged(json({ dirty, length: state.doc.length, version }));
  } catch (e) {
    // A missing native side is not fatal for the page.
  }
}

function updateHistory(state: EditorState): void {
  try {
    bridge()?.onHistoryChanged(json({
      canUndo: undoDepth(state) > 0,
      canRedo: redoDepth(state) > 0,
    }));
  } catch (e) {
    // Ignore.
  }
}

function selectionPayload(state: EditorState): { anchor: number; head: number } {
  const range = state.selection.main;
  return { anchor: range.anchor, head: range.head };
}

function scrollPayload(): { top: number; left: number } {
  if (!view) return { top: 0, left: 0 };
  return { top: view.scrollDOM.scrollTop, left: view.scrollDOM.scrollLeft };
}

function sendCursor(state: EditorState): void {
  const range = state.selection.main;
  const line = state.doc.lineAt(range.head);
  try {
    bridge()?.onCursorChanged(json({
      line: line.number,
      column: range.head - line.from + 1,
      selectedChars: Math.abs(range.head - range.anchor),
    }));
  } catch (e) {
    // Ignore.
  }
}

function scheduleCursor(state: EditorState): void {
  if (cursorTimer !== null) window.clearTimeout(cursorTimer);
  cursorTimer = window.setTimeout(() => {
    cursorTimer = null;
    if (view) sendCursor(view.state);
  }, 60);
}

function baseExtensions(): Extension[] {
  return [
    themeCompartment.of(theme ? buildTheme(theme) : []),
    languageCompartment.of([]),
    settingsCompartment.of([editorFeatures(settings), typography(settings)]),
    readOnlyCompartment.of(readOnlyExtension(readOnly)),
    searchCompartment.of(search({ top: true })),
    EditorView.updateListener.of((update) => {
      if (update.docChanged && !loading) {
        version += 1;
        updateDirty(update.state);
        updateHistory(update.state);
      }
      if (update.selectionSet || update.docChanged) scheduleCursor(update.state);
      if (update.focusChanged) {
        try {
          bridge()?.onFocusChanged(json({ focused: update.view.hasFocus }));
        } catch (e) {
          // Ignore.
        }
      }
    }),
  ];
}

function makeState(doc: Text, selection?: { anchor: number; head: number }): EditorState {
  const safeAnchor = Math.max(0, Math.min(doc.length, selection?.anchor ?? 0));
  const safeHead = Math.max(0, Math.min(doc.length, selection?.head ?? safeAnchor));
  return EditorState.create({
    doc,
    selection: { anchor: safeAnchor, head: safeHead },
    extensions: baseExtensions(),
  });
}

function createView(): void {
  const parent = document.getElementById("editor");
  if (!parent) {
    error("dom", "Editor host element is missing");
    return;
  }
  view = new EditorView({ state: makeState(Text.empty), parent });
  sendReady();
}

function applyLanguage(id: string, source: string): void {
  languageId = id || "plaintext";
  const seq = ++languageSeq;
  const loader = languageLoader(languageId);
  loader().then((extension) => {
    if (!view || seq !== languageSeq) return;
    view.dispatch({ effects: languageCompartment.reconfigure(extension) });
    try {
      bridge()?.onLanguageResolved(json({ id: languageId, source }));
    } catch (e) {
      // Ignore.
    }
  }).catch((e) => {
    if (!view || seq !== languageSeq) return;
    error("language", `Cannot load ${languageId}: ${String(e)}`);
  });
}

function countMatches(query: SearchQuery): { count: number; index: number } {
  if (!view || !query.valid || !query.search) return { count: 0, index: -1 };
  const iterator = query.getCursor(view.state);
  const head = view.state.selection.main.head;
  let count = 0;
  let index = -1;
  let step = iterator.next();
  while (!step.done) {
    const range = step.value;
    if (index < 0 && range.to >= head) index = count;
    count += 1;
    if (count >= 20000) break;
    step = iterator.next();
  }
  if (index < 0 && count > 0) index = 0;
  return { count, index };
}

function reportSearch(): void {
  if (!view || !lastQuery) return;
  const result = countMatches(lastQuery);
  try {
    bridge()?.onSearchResult(json({ count: result.count, index: result.index }));
  } catch (e) {
    // Ignore.
  }
}

function loadDocument(payload: Record<string, unknown>): void {
  if (!view) return;
  const text = typeof payload.text === "string" ? payload.text : "";
  if (text.length > MAX_TEXT) {
    error("too-large", "The document is too large for the editor");
    return;
  }
  if (payload.readOnly === true || payload.readOnly === false) {
    readOnly = payload.readOnly === true;
  }
  // `text` can be a recovery draft while `baseText` is the last saved content on disk. Keeping
  // both lets dirty tracking, save and conflict detection behave exactly like a normal edit.
  const baseText = typeof payload.baseText === "string" ? payload.baseText : text;
  const doc = Text.of(text.split("\n"));
  savedDoc = Text.of(baseText.split("\n"));
  savedHash = typeof payload.baseHash === "string" ? payload.baseHash : "";
  version = typeof payload.version === "number" ? payload.version : 0;
  dirty = !doc.eq(savedDoc);
  lastSnapshot = null;
  loading = true;
  const selection = payload.selection as { anchor?: number; head?: number } | undefined;
  const scroll = payload.scroll as { top?: number; left?: number } | undefined;
  try {
    view.setState(makeState(doc, selection ? {
      anchor: selection.anchor || 0,
      head: selection.head || 0,
    } : undefined));
    view.dispatch({
      effects: [
        searchCompartment.reconfigure(search({ top: true })),
        readOnlyCompartment.reconfigure(readOnlyExtension(readOnly)),
      ],
    });
    applyLanguage(
      typeof payload.language === "string" ? payload.language : "plaintext",
      typeof payload.languageSource === "string" ? payload.languageSource : "detected",
    );
    updateDirty(view.state);
    updateHistory(view.state);
    sendCursor(view.state);
    if (scroll) {
      requestAnimationFrame(() => {
        if (!view) return;
        view.scrollDOM.scrollTop = scroll.top || 0;
        view.scrollDOM.scrollLeft = scroll.left || 0;
      });
    }
  } finally {
    loading = false;
  }
}

function requestSnapshot(payload: Record<string, unknown>): void {
  if (!view) return;
  const requestId = typeof payload.requestId === "number" ? payload.requestId : 0;
  const reason = typeof payload.reason === "string" ? payload.reason : "save";
  const doc = view.state.doc;
  lastSnapshot = { version, doc };
  try {
    bridge()?.onSnapshot(json({
      reason,
      requestId,
      text: doc.toString(),
      version,
      baseHash: savedHash,
      selection: selectionPayload(view.state),
      scroll: scrollPayload(),
      dirty: !doc.eq(savedDoc),
      language: languageId,
    }));
  } catch (e) {
    error("snapshot", String(e));
  }
}

function confirmSaved(payload: Record<string, unknown>): void {
  if (typeof payload.hash === "string") savedHash = payload.hash;
  if (lastSnapshot) {
    savedDoc = lastSnapshot.doc;
    lastSnapshot = null;
  }
  if (view) updateDirty(view.state);
}

function setSettings(payload: Record<string, unknown>): void {
  settings = { ...settings, ...payload } as EditorSettings;
  if (!view) return;
  view.dispatch({
    effects: settingsCompartment.reconfigure([editorFeatures(settings), typography(settings)]),
  });
}

function setTheme(payload: EditorTheme): void {
  theme = payload;
  if (!view) return;
  view.dispatch({ effects: themeCompartment.reconfigure(buildTheme(theme)) });
}

function setReadOnly(payload: Record<string, unknown>): void {
  readOnly = payload.readOnly === true;
  if (!view) return;
  view.dispatch({ effects: readOnlyCompartment.reconfigure(readOnlyExtension(readOnly)) });
}

function applyFind(payload: Record<string, unknown>): void {
  if (!view) return;
  let query: SearchQuery;
  try {
    query = new SearchQuery({
      search: typeof payload.query === "string" ? payload.query : "",
      caseSensitive: payload.caseSensitive === true,
      regexp: payload.regexp === true,
      wholeWord: payload.wholeWord === true,
    });
  } catch (e) {
    error("search", String(e));
    return;
  }
  lastQuery = query;
  view.dispatch({ effects: setSearchQuery.of(query) });
  if (query.valid && query.search) findNext(view);
  reportSearch();
}

function commands(): Record<string, (payload?: unknown) => void> {
  return {
    init: (value) => {
      const payload = (value || {}) as Record<string, unknown>;
      if (payload.protocol !== PROTOCOL) {
        error("protocol", `Unsupported editor protocol ${String(payload.protocol)}`);
        return;
      }
      settings = { ...DEFAULT_SETTINGS, ...(payload.settings || {}) } as EditorSettings;
      theme = (payload.theme || null) as EditorTheme | null;
      readOnly = payload.readOnly === true;
      languageId = typeof payload.language === "string" ? payload.language : "plaintext";
      if (!initialized) {
        initialized = true;
        createView();
      }
    },
    loadDocument: (value) => loadDocument((value || {}) as Record<string, unknown>),
    unloadDocument: () => {
      if (!view) return;
      languageSeq += 1;
      savedDoc = Text.empty;
      savedHash = "";
      version = 0;
      dirty = false;
      lastSnapshot = null;
      loading = true;
      view.setState(makeState(Text.empty));
      loading = false;
    },
    setReadOnly: (value) => setReadOnly((value || {}) as Record<string, unknown>),
    setTheme: (value) => setTheme((value || {}) as EditorTheme),
    setSettings: (value) => setSettings((value || {}) as Record<string, unknown>),
    setLanguage: (value) => {
      const payload = (value || {}) as Record<string, unknown>;
      applyLanguage(typeof payload.id === "string" ? payload.id : "plaintext", "manual");
    },
    requestSnapshot: (value) => requestSnapshot((value || {}) as Record<string, unknown>),
    confirmSaved: (value) => confirmSaved((value || {}) as Record<string, unknown>),
    saveFailed: () => {
      // The buffer stays dirty; the native side already showed the conflict or error.
      if (view) updateDirty(view.state);
    },
    find: (value) => applyFind((value || {}) as Record<string, unknown>),
    findNext: (value) => {
      if (!view || !lastQuery) return;
      const payload = (value || {}) as Record<string, unknown>;
      if (payload.forward === false) findPrevious(view);
      else findNext(view);
      reportSearch();
    },
    clearFind: () => {
      lastQuery = null;
      if (!view) return;
      view.dispatch({ effects: setSearchQuery.of(new SearchQuery({ search: "" })) });
      try {
        bridge()?.onSearchResult(json({ count: 0, index: -1 }));
      } catch (e) {
        // Ignore.
      }
    },
    undo: () => {
      if (view) undo(view);
    },
    redo: () => {
      if (view) redo(view);
    },
    gotoLine: (value) => {
      if (!view) return;
      const payload = (value || {}) as Record<string, unknown>;
      const line = Math.max(1, Math.min(view.state.doc.lines, Number(payload.line) || 1));
      const target = view.state.doc.line(line);
      view.dispatch({
        selection: { anchor: target.from },
        effects: EditorView.scrollIntoView(target.from, { y: "center" }),
      });
      view.focus();
    },
    focus: () => view?.focus(),
    restoreView: (value) => {
      if (!view) return;
      const payload = (value || {}) as Record<string, unknown>;
      const selection = payload.selection as { anchor?: number; head?: number } | undefined;
      if (selection) {
        const anchor = Math.max(0, Math.min(view.state.doc.length, selection.anchor || 0));
        const head = Math.max(0, Math.min(view.state.doc.length, selection.head || anchor));
        view.dispatch({ selection: { anchor, head } });
      }
      const scroll = payload.scroll as { top?: number; left?: number } | undefined;
      if (scroll) {
        requestAnimationFrame(() => {
          if (!view) return;
          view.scrollDOM.scrollTop = scroll.top || 0;
          view.scrollDOM.scrollLeft = scroll.left || 0;
        });
      }
    },
    ping: () => {
      try {
        bridge()?.onMetrics(json({
          alive: true,
          version,
          lines: view?.state.doc.lines || 0,
          language: languageId,
        }));
      } catch (e) {
        // Ignore.
      }
    },
  };
}

export function start(): void {
  if (window.__ocEditorSupported !== true) return;
  const api = commands();
  window.OpenCodeEditor = api;
  window.addEventListener("error", (event) => {
    error("script", event.message || "Unknown script error");
  });
  window.addEventListener("unhandledrejection", (event) => {
    error("promise", String(event.reason));
  });
  // The native side calls init() after the page finished loading, but the page can also be
  // reloaded on its own (WebView crash recovery); an empty editor is safer than a blank screen.
  if (!initialized && window.OpenCodeEditorBridge) {
    // Wait for init; the native controller sends protocol, theme and settings together.
  }
}
