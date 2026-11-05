import { cpp } from "@codemirror/lang-cpp";
import { go } from "@codemirror/lang-go";
import { java } from "@codemirror/lang-java";
import { php } from "@codemirror/lang-php";
import { python } from "@codemirror/lang-python";
import { rust } from "@codemirror/lang-rust";
import { sql } from "@codemirror/lang-sql";
import type { Extension } from "@codemirror/state";

export function cppSupport(): Extension {
  return cpp();
}

export function goSupport(): Extension {
  return go();
}

export function javaSupport(): Extension {
  return java();
}

export function phpSupport(): Extension {
  return php();
}

export function pythonSupport(): Extension {
  return python();
}

export function rustSupport(): Extension {
  return rust();
}

export function sqlSupport(): Extension {
  return sql();
}
