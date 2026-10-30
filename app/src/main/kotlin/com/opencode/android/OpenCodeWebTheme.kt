package com.opencode.android

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds a stylesheet that repaints the OpenCode web UI with the Material You palette of the
 * native app.
 *
 * The web app is themed with CSS custom properties: the semantic `--v2-*` tokens of
 * `packages/ui/src/styles/tokens/theme.css` plus a set of legacy `--text-*` / `--border-*`
 * variables that some components still read. Every value here is resolved from the *activity*
 * theme, so on Android 12+ the stylesheet follows the wallpaper palette that
 * [com.google.android.material.color.DynamicColors] applied, exactly like the native screens.
 *
 * The result is injected as a document-start script by [OpenCodeWebViewModel], which means the
 * page is painted with the right colors instead of flashing the stock theme first. The script is
 * idempotent: it reuses the `oc-android-theme` style element and can be evaluated again on the
 * live document (a rotation, a dark-mode change or the day the page finished loading).
 */
object OpenCodeWebTheme {

    /** A resolved stylesheet and the surface color to put behind the WebView. */
    class Resolved(
        val script: String,
        val surface: Int,
        val dark: Boolean,
    )

    /** Resolve the palette of [context] and build the injected script. */
    fun resolve(context: Context): Resolved {
        val palette = palette(context)
        val dark = isDark(context)
        val css = buildCss(palette, dark)
        val mode = if (dark) "dark" else "light"
        val background = hex(palette.surface)
        val script = """
            (function () {
              var css = ${JSONObject.quote(css)};
              function apply() {
                var root = document.documentElement;
                if (!root) return;
                var style = document.getElementById('oc-android-theme');
                if (!style) {
                  style = document.createElement('style');
                  style.id = 'oc-android-theme';
                }
                if (style.textContent !== css) style.textContent = css;
                var parent = document.head || root;
                if (style.parentNode !== parent) parent.appendChild(style);
                root.style.setProperty('background-color', '$background', 'important');
                root.style.setProperty('color-scheme', '$mode', 'important');
                if (document.body) {
                  document.body.style.setProperty('background-color', '$background', 'important');
                }
                var meta = document.querySelector("meta[name='theme-color']");
                if (meta) meta.setAttribute('content', '$background');
                // The web app picks its light/dark palette and its code theme from this
                // attribute; the native app is the source of truth so the two cannot disagree.
                if (root.getAttribute('data-color-scheme') !== '$mode') {
                  root.setAttribute('data-color-scheme', '$mode');
                }
              }
              window.__ocAndroidThemeApply = apply;
              apply();
              if (!document.documentElement && window.MutationObserver) {
                var observer = new MutationObserver(function () {
                  if (!document.documentElement) return;
                  observer.disconnect();
                  apply();
                });
                observer.observe(document, { childList: true, subtree: true });
              }
              if (!window.__ocAndroidThemeHooked) {
                window.__ocAndroidThemeHooked = true;
                var reapply = function () {
                  watchScheme();
                  if (window.__ocAndroidThemeApply) window.__ocAndroidThemeApply();
                };
                // Re-assert the color scheme if the web app writes its own preference after us.
                var watchScheme = function () {
                  if (window.__ocAndroidSchemeWatched) return;
                  var el = document.documentElement;
                  if (!el || !window.MutationObserver) return;
                  window.__ocAndroidSchemeWatched = true;
                  new MutationObserver(function () {
                    if (el.getAttribute('data-color-scheme') !== '$mode') reapply();
                  }).observe(el, { attributes: true, attributeFilter: ['data-color-scheme'] });
                };
                document.addEventListener('readystatechange', reapply);
                document.addEventListener('DOMContentLoaded', reapply);
                window.addEventListener('load', reapply);
                watchScheme();
              }
            })();
        """.trimIndent()
        return Resolved(script, palette.surface, dark)
    }

    private class Palette(
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondary: Int,
        val onSecondary: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val tertiary: Int,
        val onTertiary: Int,
        val tertiaryContainer: Int,
        val onTertiaryContainer: Int,
        val error: Int,
        val onError: Int,
        val errorContainer: Int,
        val onErrorContainer: Int,
        val surface: Int,
        val onSurface: Int,
        val surfaceVariant: Int,
        val onSurfaceVariant: Int,
        val outline: Int,
        val outlineVariant: Int,
        val surfaceContainerLowest: Int,
        val surfaceContainerLow: Int,
        val surfaceContainer: Int,
        val surfaceContainerHigh: Int,
        val surfaceContainerHighest: Int,
        val inverseSurface: Int,
        val onInverseSurface: Int,
        val scrim: Int,
    )

    private fun palette(context: Context): Palette {
        fun color(attr: Int, fallback: Int): Int =
            MaterialColors.getColor(context, attr, ContextCompat.getColor(context, fallback))

        return Palette(
            primary = color(androidx.appcompat.R.attr.colorPrimary, R.color.oc_primary),
            onPrimary = color(com.google.android.material.R.attr.colorOnPrimary, R.color.oc_on_primary),
            primaryContainer = color(
                com.google.android.material.R.attr.colorPrimaryContainer,
                R.color.oc_primary_container,
            ),
            onPrimaryContainer = color(
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                R.color.oc_on_primary_container,
            ),
            secondary = color(com.google.android.material.R.attr.colorSecondary, R.color.oc_secondary),
            onSecondary = color(com.google.android.material.R.attr.colorOnSecondary, R.color.oc_on_secondary),
            secondaryContainer = color(
                com.google.android.material.R.attr.colorSecondaryContainer,
                R.color.oc_secondary_container,
            ),
            onSecondaryContainer = color(
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                R.color.oc_on_secondary_container,
            ),
            tertiary = color(com.google.android.material.R.attr.colorTertiary, R.color.oc_tertiary),
            onTertiary = color(com.google.android.material.R.attr.colorOnTertiary, R.color.oc_on_tertiary),
            tertiaryContainer = color(
                com.google.android.material.R.attr.colorTertiaryContainer,
                R.color.oc_tertiary_container,
            ),
            onTertiaryContainer = color(
                com.google.android.material.R.attr.colorOnTertiaryContainer,
                R.color.oc_on_tertiary_container,
            ),
            error = color(androidx.appcompat.R.attr.colorError, R.color.oc_error),
            onError = color(com.google.android.material.R.attr.colorOnError, R.color.oc_on_error),
            errorContainer = color(
                com.google.android.material.R.attr.colorErrorContainer,
                R.color.oc_error_container,
            ),
            onErrorContainer = color(
                com.google.android.material.R.attr.colorOnErrorContainer,
                R.color.oc_on_error_container,
            ),
            surface = color(com.google.android.material.R.attr.colorSurface, R.color.oc_surface),
            onSurface = color(com.google.android.material.R.attr.colorOnSurface, R.color.oc_on_surface),
            surfaceVariant = color(
                com.google.android.material.R.attr.colorSurfaceVariant,
                R.color.oc_surface_variant,
            ),
            onSurfaceVariant = color(
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                R.color.oc_on_surface_variant,
            ),
            outline = color(com.google.android.material.R.attr.colorOutline, R.color.oc_outline),
            outlineVariant = color(
                com.google.android.material.R.attr.colorOutlineVariant,
                R.color.oc_outline_variant,
            ),
            surfaceContainerLowest = color(
                com.google.android.material.R.attr.colorSurfaceContainerLowest,
                R.color.oc_surface_container_lowest,
            ),
            surfaceContainerLow = color(
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                R.color.oc_surface_container_low,
            ),
            surfaceContainer = color(
                com.google.android.material.R.attr.colorSurfaceContainer,
                R.color.oc_surface_container,
            ),
            surfaceContainerHigh = color(
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                R.color.oc_surface_container_high,
            ),
            surfaceContainerHighest = color(
                com.google.android.material.R.attr.colorSurfaceContainerHighest,
                R.color.oc_surface_container_highest,
            ),
            inverseSurface = color(
                com.google.android.material.R.attr.colorSurfaceInverse,
                R.color.oc_surface_inverse,
            ),
            onInverseSurface = color(
                com.google.android.material.R.attr.colorOnSurfaceInverse,
                R.color.oc_on_surface_inverse,
            ),
            scrim = ContextCompat.getColor(context, R.color.oc_scrim),
        )
    }

    private fun buildCss(p: Palette, dark: Boolean): String {
        val accentBg = p.primary
        val onAccentBg = p.onPrimary
        // "Contrast" surfaces are the filled-button surface of the palette. In dark mode a dark
        // surface with white text would no longer look like the light primary M3 button, so the
        // contrast pair follows primary/onPrimary there too.
        val contrastBg = if (dark) p.primary else p.inverseSurface
        val accentHover = if (dark) mix(p.primary, 82, 0xFFFFFF) else mix(p.primary, 82, 0x000000)

        val tokens = LinkedHashMap<String, String>()

        // ── Surfaces ──
        tokens["--v2-background-bg-base"] = color(p.surface)
        tokens["--v2-background-bg-deep"] = color(p.surfaceContainerLowest)
        tokens["--v2-background-bg-layer-01"] = color(p.surfaceContainerLow)
        tokens["--v2-background-bg-layer-02"] = color(p.surfaceContainer)
        tokens["--v2-background-bg-layer-03"] = color(p.surfaceContainerHigh)
        tokens["--v2-background-bg-layer-04"] = color(p.surfaceContainerHighest)
        tokens["--v2-background-bg-inverse"] = color(p.inverseSurface)
        tokens["--v2-background-bg-contrast"] = color(contrastBg)
        tokens["--v2-background-bg-icon-button-contrast"] = color(contrastBg)
        tokens["--v2-background-bg-button-neutral"] = color(p.surfaceContainerLow)
        tokens["--v2-background-bg-accent"] = color(accentBg)
        tokens["--v2-background-bg-code-path"] = color(p.secondaryContainer)

        // ── Text ──
        tokens["--v2-text-text-base"] = color(p.onSurface)
        tokens["--v2-text-text-muted"] = color(p.onSurfaceVariant)
        tokens["--v2-text-text-faint"] = color(p.outline)
        tokens["--v2-text-text-inverse"] = color(p.onInverseSurface)
        tokens["--v2-text-text-contrast"] = color(onAccentBg)
        tokens["--v2-text-text-accent"] = color(p.primary)
        tokens["--v2-text-text-accent-hover"] = accentHover
        tokens["--v2-text-text-code-accent"] = color(p.primary)
        tokens["--v2-text-text-code-path"] = color(p.primary)

        // ── Icons ──
        tokens["--v2-icon-icon-base"] = color(p.onSurfaceVariant)
        tokens["--v2-icon-icon-muted"] = color(p.onSurfaceVariant)
        tokens["--v2-icon-icon-faint"] = color(p.outline)
        tokens["--v2-icon-icon-inverse"] = color(p.onInverseSurface)
        tokens["--v2-icon-icon-contrast"] = color(onAccentBg)
        tokens["--v2-icon-icon-accent"] = color(p.primary)
        tokens["--v2-icon-icon-accent-hover"] = accentHover

        // ── Borders ──
        tokens["--v2-border-border-muted"] = fade(p.outlineVariant, 55)
        tokens["--v2-border-border-base"] = color(p.outlineVariant)
        tokens["--v2-border-border-strong"] = color(p.outline)
        tokens["--v2-border-border-inverse"] = color(p.onInverseSurface)
        tokens["--v2-border-border-focus"] = color(p.primary)

        // ── Overlays ──
        tokens["--v2-overlay-simple-overlay-hover"] = fade(p.onSurface, 6)
        tokens["--v2-overlay-simple-overlay-pressed"] = fade(p.onSurface, 12)
        tokens["--v2-overlay-simple-overlay-contrast-hover"] = fade(onAccentBg, 12)
        tokens["--v2-overlay-simple-overlay-contrast-pressed"] = fade(onAccentBg, 24)
        tokens["--v2-overlay-simple-overlay-scrim"] = fade(p.scrim, if (dark) 60 else 40)
        tokens["--v2-overlay-gradient-depth-overlay-depth-top"] = fade(p.surface, 100)
        tokens["--v2-overlay-gradient-depth-overlay-depth-bot"] = fade(p.surface, 0)
        tokens["--v2-overlay-simple-tab-active-scrim"] = fade(p.surface, 0)
        tokens["--v2-overlay-simple-tab-hover-scrim"] = fade(p.surface, 0)
        tokens["--v2-overlay-simple-tab-scrim"] = fade(p.surface, 0)

        // ── States: Material roles instead of the fixed blue/green/red ramp ──
        state(tokens, "success", p.tertiaryContainer, p.onTertiaryContainer, p.tertiary)
        state(tokens, "warning", p.secondaryContainer, p.onSecondaryContainer, p.secondary)
        state(tokens, "danger", p.errorContainer, p.onErrorContainer, p.error)
        state(tokens, "info", p.primaryContainer, p.onPrimaryContainer, p.primary)

        // ── Agents ──
        agent(tokens, "plan", p.tertiary)
        agent(tokens, "build", p.primary)
        agent(tokens, "explore", p.secondary)
        agent(tokens, "review", p.tertiary)
        agent(tokens, "writer", p.primary)

        // ── Accent ramp: components that read the primitive blue steps directly ──
        val ramp = if (dark) {
            listOf(
                mix(p.primary, 12, p.surface),
                mix(p.primary, 18, p.surface),
                mix(p.primary, 28, p.surface),
                color(p.primary),
                mix(p.primary, 80, 0xFFFFFF),
                color(p.primary),
                mix(p.primary, 82, 0x000000),
                color(p.primaryContainer),
                color(p.primaryContainer),
                mix(p.primaryContainer, 82, 0x000000),
                mix(p.primaryContainer, 68, 0x000000),
                mix(p.primaryContainer, 45, p.surface),
            )
        } else {
            listOf(
                mix(p.primary, 10, p.surface),
                mix(p.primary, 18, p.surface),
                mix(p.primary, 30, p.surface),
                mix(p.primary, 46, p.surface),
                mix(p.primary, 64, p.surface),
                color(p.primary),
                accentHover,
                color(p.onPrimaryContainer),
                color(p.onPrimaryContainer),
                mix(p.onPrimaryContainer, 85, 0x000000),
                mix(p.onPrimaryContainer, 72, 0x000000),
                mix(p.onPrimaryContainer, 55, p.surface),
            )
        }
        val steps = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1100, 1200)
        for ((index, step) in steps.withIndex()) {
            tokens["--v2-blue-$step"] = ramp[index]
        }
        tokens["--v2-cyan-100"] = color(p.secondaryContainer)
        tokens["--v2-cyan-1200"] = color(p.onSecondaryContainer)

        // ── Syntax and markdown: keep code readable, tint it with the same palette ──
        tokens["--syntax-comment"] = color(p.outline)
        tokens["--syntax-keyword"] = color(p.primary)
        tokens["--syntax-string"] = color(p.tertiary)
        tokens["--syntax-primitive"] = color(p.primary)
        tokens["--syntax-property"] = color(p.secondary)
        tokens["--syntax-type"] = color(p.tertiary)
        tokens["--syntax-constant"] = color(p.secondary)
        tokens["--syntax-operator"] = color(p.onSurfaceVariant)
        tokens["--syntax-punctuation"] = color(p.onSurfaceVariant)
        tokens["--syntax-variable"] = color(p.onSurface)
        tokens["--syntax-object"] = color(p.onSurface)
        tokens["--syntax-success"] = color(p.tertiary)
        tokens["--syntax-warning"] = color(p.secondary)
        tokens["--syntax-critical"] = color(p.error)
        tokens["--syntax-info"] = color(p.primary)
        tokens["--markdown-heading"] = color(p.primary)
        tokens["--markdown-text"] = color(p.onSurface)
        tokens["--markdown-link"] = color(p.primary)
        tokens["--markdown-link-text"] = color(p.secondary)
        tokens["--markdown-code"] = color(p.tertiary)
        tokens["--markdown-block-quote"] = color(p.outline)
        tokens["--markdown-emph"] = color(p.secondary)
        tokens["--markdown-strong"] = color(p.primary)
        tokens["--markdown-horizontal-rule"] = color(p.outlineVariant)
        tokens["--markdown-list-item"] = color(p.primary)
        tokens["--markdown-list-enumeration"] = color(p.secondary)
        tokens["--markdown-image"] = color(p.primary)
        tokens["--markdown-image-text"] = color(p.secondary)
        tokens["--markdown-code-block"] = color(p.onSurface)

        // ── Legacy tokens ──
        // The web app still renders several areas with the flat OC-2 variable names
        // (`--background-*`, `--text-*`, `--icon-*`, `--border-*`, the state and diff
        // surfaces). They are mapped here too, otherwise those panels would keep the old
        // blue/green palette next to a Material You frame. Neutral overlays the app defines
        // per `prefers-color-scheme` are left alone: WebView resolves them in the same mode.
        tokens["--background-base"] = color(p.surface)
        tokens["--background-weak"] = color(p.surfaceContainerLow)
        tokens["--background-strong"] = color(p.surface)
        tokens["--background-stronger"] = color(p.surfaceContainerLowest)
        tokens["--surface-base"] = fade(p.onSurface, 3)
        tokens["--base"] = fade(p.onSurface, 3)
        tokens["--base2"] = fade(p.onSurface, 3)
        tokens["--base3"] = fade(p.onSurface, 3)
        tokens["--surface-base-hover"] = fade(p.onSurface, 6)
        tokens["--surface-base-active"] = fade(p.onSurface, 6)
        tokens["--surface-base-interactive-active"] = fade(p.primary, 12)
        tokens["--surface-weak"] = fade(p.onSurface, 6)
        tokens["--surface-weaker"] = fade(p.onSurface, 8)
        tokens["--surface-strong"] = color(p.surfaceContainerHighest)
        tokens["--surface-stronger-non-alpha"] = color(p.surfaceContainerHigh)
        tokens["--surface-raised-stronger-non-alpha"] = color(p.surfaceContainerHigh)

        // ── Inputs and buttons ──
        tokens["--input-base"] = color(p.surfaceContainerHigh)
        tokens["--input-hover"] = color(p.surfaceContainerHighest)
        tokens["--input-active"] = fade(p.primary, 20)
        tokens["--input-focus"] = color(p.primary)
        tokens["--input-selected"] = color(p.primaryContainer)
        tokens["--input-disabled"] = fade(p.onSurface, 12)
        tokens["--button-primary-base"] = color(p.primary)
        tokens["--button-secondary-base"] = color(p.surfaceContainerHigh)
        tokens["--button-secondary-hover"] = color(p.surfaceContainerHighest)
        tokens["--button-ghost-hover"] = fade(p.onSurface, 4)
        tokens["--button-ghost-hover2"] = fade(p.onSurface, 8)

        // ── Text ──
        tokens["--text-base"] = color(p.onSurface)
        tokens["--text-weak"] = color(p.onSurfaceVariant)
        tokens["--text-weaker"] = color(p.outline)
        tokens["--text-strong"] = color(p.onSurface)
        tokens["--text-stronger"] = color(p.onSurface)
        tokens["--text-invert-base"] = color(p.onInverseSurface)
        tokens["--text-invert-weak"] = fade(p.onInverseSurface, 85)
        tokens["--text-invert-weaker"] = fade(p.onInverseSurface, 70)
        tokens["--text-invert-strong"] = color(p.onInverseSurface)
        tokens["--text-interactive-base"] = color(p.primary)
        tokens["--text-on-brand-base"] = color(onAccentBg)
        tokens["--text-on-brand-weak"] = fade(onAccentBg, 70)
        tokens["--text-on-brand-weaker"] = fade(onAccentBg, 45)
        tokens["--text-on-brand-strong"] = color(onAccentBg)
        tokens["--text-on-interactive-base"] = color(onAccentBg)
        tokens["--text-on-interactive-weak"] = fade(onAccentBg, 60)
        tokens["--text-on-success-base"] = color(p.onTertiaryContainer)
        tokens["--text-on-success-weak"] = fade(p.onTertiaryContainer, 60)
        tokens["--text-on-success-strong"] = color(p.onTertiaryContainer)
        tokens["--text-on-warning-base"] = color(p.onSecondaryContainer)
        tokens["--text-on-warning-weak"] = fade(p.onSecondaryContainer, 60)
        tokens["--text-on-warning-strong"] = color(p.onSecondaryContainer)
        tokens["--text-on-critical-base"] = color(p.onErrorContainer)
        tokens["--text-on-critical-weak"] = fade(p.onErrorContainer, 70)
        tokens["--text-on-critical-strong"] = color(p.onErrorContainer)
        tokens["--text-on-info-base"] = color(p.onPrimaryContainer)
        tokens["--text-on-info-weak"] = fade(p.onPrimaryContainer, 60)
        tokens["--text-on-info-strong"] = color(p.onPrimaryContainer)
        tokens["--text-diff-add-base"] = color(p.tertiary)
        tokens["--text-diff-add-strong"] = color(p.tertiary)
        tokens["--text-diff-delete-base"] = color(p.error)
        tokens["--text-diff-delete-strong"] = color(p.error)

        // ── Icons ──
        tokens["--icon-base"] = color(p.onSurfaceVariant)
        tokens["--icon-hover"] = color(p.onSurface)
        tokens["--icon-active"] = color(p.onSurface)
        tokens["--icon-selected"] = color(p.onSurface)
        tokens["--icon-focus"] = color(p.onSurface)
        tokens["--icon-disabled"] = fade(p.onSurface, 38)
        tokens["--icon-invert-base"] = color(p.onInverseSurface)
        tokens["--icon-weak-base"] = color(p.outline)
        tokens["--icon-weak-hover"] = color(p.onSurfaceVariant)
        tokens["--icon-weak-active"] = color(p.onSurface)
        tokens["--icon-weak-selected"] = color(p.onSurface)
        tokens["--icon-weak-focus"] = color(p.onSurface)
        tokens["--icon-weak-disabled"] = fade(p.onSurface, 32)
        tokens["--icon-strong-base"] = color(p.onSurface)
        tokens["--icon-strong-hover"] = color(p.onSurface)
        tokens["--icon-strong-active"] = color(p.onSurface)
        tokens["--icon-strong-selected"] = color(p.onSurface)
        tokens["--icon-strong-focus"] = color(p.onSurface)
        tokens["--icon-strong-disabled"] = fade(p.onSurface, 38)
        tokens["--icon-brand-base"] = color(onAccentBg)
        tokens["--icon-interactive-base"] = color(p.primary)
        tokens["--icon-success-base"] = color(p.tertiary)
        tokens["--icon-success-hover"] = color(p.tertiary)
        tokens["--icon-success-active"] = color(p.tertiary)
        tokens["--icon-warning-base"] = color(p.secondary)
        tokens["--icon-warning-hover"] = color(p.secondary)
        tokens["--icon-warning-active"] = color(p.secondary)
        tokens["--icon-critical-base"] = color(p.error)
        tokens["--icon-critical-hover"] = color(p.error)
        tokens["--icon-critical-active"] = color(p.error)
        tokens["--icon-info-base"] = color(p.primary)
        tokens["--icon-info-hover"] = color(p.primary)
        tokens["--icon-info-active"] = color(p.primary)
        tokens["--icon-on-brand-base"] = color(onAccentBg)
        tokens["--icon-on-brand-hover"] = color(onAccentBg)
        tokens["--icon-on-brand-selected"] = color(onAccentBg)
        tokens["--icon-on-interactive-base"] = color(onAccentBg)
        tokens["--icon-on-success-base"] = color(p.onTertiaryContainer)
        tokens["--icon-on-success-hover"] = color(p.onTertiaryContainer)
        tokens["--icon-on-success-selected"] = color(p.onTertiaryContainer)
        tokens["--icon-on-warning-base"] = color(p.onSecondaryContainer)
        tokens["--icon-on-warning-hover"] = color(p.onSecondaryContainer)
        tokens["--icon-on-warning-selected"] = color(p.onSecondaryContainer)
        tokens["--icon-on-critical-base"] = color(p.onErrorContainer)
        tokens["--icon-on-critical-hover"] = color(p.onErrorContainer)
        tokens["--icon-on-critical-selected"] = color(p.onErrorContainer)
        tokens["--icon-on-info-base"] = color(p.onPrimaryContainer)
        tokens["--icon-on-info-hover"] = color(p.onPrimaryContainer)
        tokens["--icon-on-info-selected"] = color(p.onPrimaryContainer)
        tokens["--icon-agent-plan-base"] = color(p.tertiary)
        tokens["--icon-agent-docs-base"] = color(p.secondary)
        tokens["--icon-agent-ask-base"] = color(p.primary)
        tokens["--icon-agent-build-base"] = color(p.primary)
        tokens["--icon-diff-add-base"] = color(p.tertiary)
        tokens["--icon-diff-add-hover"] = color(p.tertiary)
        tokens["--icon-diff-add-active"] = color(p.tertiary)
        tokens["--icon-diff-delete-base"] = color(p.error)
        tokens["--icon-diff-delete-hover"] = color(p.error)
        tokens["--icon-diff-modified-base"] = color(p.secondary)

        // ── Borders ──
        tokens["--border-base"] = color(p.outlineVariant)
        tokens["--border-hover"] = fade(p.onSurface, 24)
        tokens["--border-active"] = fade(p.onSurface, 46)
        tokens["--border-selected"] = color(p.primary)
        tokens["--border-disabled"] = fade(p.onSurface, 24)
        tokens["--border-focus"] = color(p.primary)
        tokens["--border-color"] = color(p.surface)
        tokens["--border-weak-base"] = fade(p.outlineVariant, 60)
        tokens["--border-weak-hover"] = fade(p.onSurface, 12)
        tokens["--border-weak-active"] = fade(p.onSurface, 15)
        tokens["--border-weak-selected"] = fade(p.primary, 24)
        tokens["--border-weak-disabled"] = fade(p.onSurface, 12)
        tokens["--border-weak-focus"] = fade(p.onSurface, 15)
        tokens["--border-weaker-base"] = fade(p.onSurface, 8)
        tokens["--border-weaker-hover"] = fade(p.onSurface, 8)
        tokens["--border-weaker-active"] = fade(p.onSurface, 12)
        tokens["--border-weaker-selected"] = fade(p.primary, 16)
        tokens["--border-weaker-disabled"] = fade(p.onSurface, 4)
        tokens["--border-weaker-focus"] = fade(p.onSurface, 12)
        tokens["--border-strong-base"] = color(p.outline)
        tokens["--border-strong-hover"] = fade(p.onSurface, 23)
        tokens["--border-strong-active"] = fade(p.onSurface, 15)
        tokens["--border-strong-selected"] = fade(p.primary, 31)
        tokens["--border-strong-disabled"] = fade(p.onSurface, 12)
        tokens["--border-strong-focus"] = fade(p.onSurface, 15)
        tokens["--border-interactive-base"] = mix(p.primary, 40, p.surface)
        tokens["--border-interactive-hover"] = mix(p.primary, 60, p.surface)
        tokens["--border-interactive-active"] = color(p.primary)
        tokens["--border-interactive-selected"] = color(p.primary)
        tokens["--border-interactive-disabled"] = color(p.outline)
        tokens["--border-interactive-focus"] = color(p.primary)
        tokens["--border-success-base"] = mix(p.tertiary, 45, p.surface)
        tokens["--border-success-hover"] = mix(p.tertiary, 65, p.surface)
        tokens["--border-success-selected"] = color(p.tertiary)
        tokens["--border-warning-base"] = mix(p.secondary, 45, p.surface)
        tokens["--border-warning-hover"] = mix(p.secondary, 65, p.surface)
        tokens["--border-warning-selected"] = color(p.secondary)
        tokens["--border-critical-base"] = mix(p.error, 35, p.surface)
        tokens["--border-critical-hover"] = mix(p.error, 55, p.surface)
        tokens["--border-critical-selected"] = color(p.error)
        tokens["--border-info-base"] = mix(p.primary, 40, p.surface)
        tokens["--border-info-hover"] = mix(p.primary, 60, p.surface)
        tokens["--border-info-selected"] = color(p.primary)

        // ── Brand and state surfaces ──
        tokens["--surface-brand-base"] = color(accentBg)
        tokens["--surface-brand-hover"] = accentHover
        tokens["--surface-interactive-base"] = color(accentBg)
        tokens["--surface-interactive-hover"] = accentHover
        tokens["--surface-interactive-weak"] = fade(p.primary, 15)
        tokens["--surface-interactive-weak-hover"] = fade(p.primary, 12)
        tokens["--surface-success-base"] = color(p.tertiaryContainer)
        tokens["--surface-success-weak"] = fade(p.tertiary, 12)
        tokens["--surface-success-strong"] = color(p.tertiary)
        tokens["--surface-warning-base"] = color(p.secondaryContainer)
        tokens["--surface-warning-weak"] = fade(p.secondary, 12)
        tokens["--surface-warning-strong"] = color(p.secondary)
        tokens["--surface-critical-base"] = color(p.errorContainer)
        tokens["--surface-critical-weak"] = fade(p.error, 12)
        tokens["--surface-critical-strong"] = color(p.error)
        tokens["--surface-info-base"] = color(p.primaryContainer)
        tokens["--surface-info-weak"] = fade(p.primary, 12)
        tokens["--surface-info-strong"] = color(p.primary)

        // ── Diff panels of the code review view ──
        tokens["--surface-diff-unchanged-base"] = fade(p.surface, 0)
        tokens["--surface-diff-skip-base"] = color(p.surfaceContainerLow)
        tokens["--surface-diff-hidden-base"] = color(p.primaryContainer)
        tokens["--surface-diff-hidden-weak"] = fade(p.primary, 12)
        tokens["--surface-diff-hidden-weaker"] = fade(p.primary, 7)
        tokens["--surface-diff-hidden-strong"] = mix(p.primary, 30, p.surface)
        tokens["--surface-diff-hidden-stronger"] = color(p.primary)
        tokens["--surface-diff-add-base"] = color(p.tertiaryContainer)
        tokens["--surface-diff-add-weak"] = fade(p.tertiary, 12)
        tokens["--surface-diff-add-weaker"] = fade(p.tertiary, 7)
        tokens["--surface-diff-add-strong"] = mix(p.tertiary, 30, p.surface)
        tokens["--surface-diff-add-stronger"] = color(p.tertiary)
        tokens["--surface-diff-delete-base"] = color(p.errorContainer)
        tokens["--surface-diff-delete-weak"] = fade(p.error, 12)
        tokens["--surface-diff-delete-weaker"] = fade(p.error, 7)
        tokens["--surface-diff-delete-strong"] = mix(p.error, 30, p.surface)
        tokens["--surface-diff-delete-stronger"] = color(p.error)

        val declarations = tokens.entries.joinToString("\n") { (name, value) ->
            "  $name: $value !important;"
        }

        return """
            html, html[data-color-scheme] {
              color-scheme: ${if (dark) "dark" else "light"} !important;
              --text-mix-blend-mode: ${if (dark) "plus-lighter" else "multiply"} !important;
            $declarations
            }
            html {
              background-color: ${color(p.surface)} !important;
            }
            body {
              background-color: ${color(p.surface)} !important;
            }
        """.trimIndent()
    }

    private fun state(
        tokens: MutableMap<String, String>,
        name: String,
        background: Int,
        foreground: Int,
        border: Int,
    ) {
        tokens["--v2-state-bg-$name"] = color(background)
        tokens["--v2-state-fg-$name"] = color(foreground)
        tokens["--v2-state-border-$name"] = mix(border, 40, background)
    }

    private fun agent(tokens: MutableMap<String, String>, name: String, solid: Int) {
        tokens["--v2-agent-$name-solid"] = color(solid)
        tokens["--v2-agent-$name-border"] = fade(solid, 32)
        tokens["--v2-agent-$name-background"] = fade(solid, 12)
    }

    private fun isDark(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun color(value: Int): String = hex(value)

    private fun hex(value: Int): String = String.format(Locale.US, "#%06X", value and 0xFFFFFF)

    /** Blend [percent]% of [value] with the rest of [other], computed here instead of color-mix(). */
    private fun mix(value: Int, percent: Int, other: Int): String {
        val part = percent.coerceIn(0, 100) / 100f
        val rest = 1f - part
        val red = ((value shr 16 and 0xFF) * part + (other shr 16 and 0xFF) * rest).roundToInt()
        val green = ((value shr 8 and 0xFF) * part + (other shr 8 and 0xFF) * rest).roundToInt()
        val blue = ((value and 0xFF) * part + (other and 0xFF) * rest).roundToInt()
        return hex((0xFF shl 24) or (red shl 16) or (green shl 8) or blue)
    }

    /** [percent]% opaque version of [value]; rgba() is understood by every WebView the app supports. */
    private fun fade(value: Int, percent: Int): String {
        val alpha = percent.coerceIn(0, 100) / 100f
        val red = value shr 16 and 0xFF
        val green = value shr 8 and 0xFF
        val blue = value and 0xFF
        return String.format(Locale.US, "rgba(%d, %d, %d, %.3f)", red, green, blue, alpha)
    }
}
