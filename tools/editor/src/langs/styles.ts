import { css } from "@codemirror/lang-css";
import { less } from "@codemirror/lang-less";
import { sass } from "@codemirror/lang-sass";
import type { Extension } from "@codemirror/state";

export function cssSupport(): Extension {
  return css();
}

export function scssSupport(): Extension {
  return sass({ indented: false });
}

export function lessSupport(): Extension {
  return less();
}
