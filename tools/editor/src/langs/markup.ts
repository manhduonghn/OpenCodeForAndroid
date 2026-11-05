import { html } from "@codemirror/lang-html";
import { markdown } from "@codemirror/lang-markdown";
import { xml } from "@codemirror/lang-xml";
import type { Extension } from "@codemirror/state";

export function htmlSupport(): Extension {
  return html();
}

export function xmlSupport(): Extension {
  return xml();
}

export function markdownSupport(): Extension {
  return markdown();
}
