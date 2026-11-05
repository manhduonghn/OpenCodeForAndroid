import { json } from "@codemirror/lang-json";
import { yaml } from "@codemirror/lang-yaml";
import type { Extension } from "@codemirror/state";

export function jsonSupport(): Extension {
  return json();
}

export function yamlSupport(): Extension {
  return yaml();
}
