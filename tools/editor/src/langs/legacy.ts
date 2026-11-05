import { LanguageSupport, StreamLanguage } from "@codemirror/language";
import { c, cpp, csharp, dart, java, kotlin, objectiveC, scala } from "@codemirror/legacy-modes/mode/clike";
import { diff } from "@codemirror/legacy-modes/mode/diff";
import { dockerFile } from "@codemirror/legacy-modes/mode/dockerfile";
import { groovy } from "@codemirror/legacy-modes/mode/groovy";
import { properties } from "@codemirror/legacy-modes/mode/properties";
import { shell } from "@codemirror/legacy-modes/mode/shell";
import { toml } from "@codemirror/legacy-modes/mode/toml";
import type { Extension } from "@codemirror/state";

function legacy(mode: Parameters<typeof StreamLanguage.define>[0]): Extension {
  return new LanguageSupport(StreamLanguage.define(mode));
}

export function kotlinSupport(): Extension {
  return legacy(kotlin);
}

export function cSupport(): Extension {
  return legacy(c);
}

export function cppLegacySupport(): Extension {
  return legacy(cpp);
}

export function csharpSupport(): Extension {
  return legacy(csharp);
}

export function scalaSupport(): Extension {
  return legacy(scala);
}

export function dartSupport(): Extension {
  return legacy(dart);
}

export function objectiveCSupport(): Extension {
  return legacy(objectiveC);
}

export function javaLegacySupport(): Extension {
  return legacy(java);
}

export function shellSupport(): Extension {
  return legacy(shell);
}

export function propertiesSupport(): Extension {
  return legacy(properties);
}

export function tomlSupport(): Extension {
  return legacy(toml);
}

export function dockerfileSupport(): Extension {
  return legacy(dockerFile);
}

export function diffSupport(): Extension {
  return legacy(diff);
}

export function groovySupport(): Extension {
  return legacy(groovy);
}
