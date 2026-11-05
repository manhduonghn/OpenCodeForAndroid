import { javascript } from "@codemirror/lang-javascript";
import type { Extension } from "@codemirror/state";

export function javascriptSupport(): Extension {
  return javascript({ jsx: false, typescript: false });
}

export function typescriptSupport(): Extension {
  return javascript({ jsx: false, typescript: true });
}

export function jsxSupport(): Extension {
  return javascript({ jsx: true, typescript: false });
}

export function tsxSupport(): Extension {
  return javascript({ jsx: true, typescript: true });
}
