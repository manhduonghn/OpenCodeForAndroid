package com.opencode.android

import java.io.File

/**
 * One syntax mode that the editor page knows how to load.
 *
 * [id] is the value sent over the bridge; the JavaScript side maps it to a lazy CodeMirror
 * chunk. [extensions] are stored without the leading dot, so `d.ts` wins over `ts` because the
 * resolver picks the longest matching extension.
 */
data class EditorLanguage(
    val id: String,
    val displayName: String,
    val chunk: String?,
    val extensions: Set<String> = emptySet(),
    val filenames: List<Regex> = emptyList(),
    val aliases: Set<String> = emptySet(),
)

/**
 * Filename to language resolution. Both the Kotlin controller and the JavaScript page use the
 * result of this registry; the page never guesses a second time.
 */
object EditorLanguageRegistry {

    private val plain = EditorLanguage("plaintext", "Plain text", null)

    private val languages: List<EditorLanguage> = listOf(
        plain,
        EditorLanguage(
            "kotlin", "Kotlin", "chunks/legacy-clike.js",
            setOf("kt", "kts"),
            listOf(Regex("^build\\.gradle\\.kts$"), Regex("^settings\\.gradle\\.kts$")),
            setOf("kotlin", "kts"),
        ),
        EditorLanguage(
            "java", "Java", "chunks/lang-java.js",
            setOf("java"),
            emptyList(),
            setOf("java"),
        ),
        EditorLanguage(
            "xml", "XML", "chunks/lang-xml.js",
            setOf("xml", "xsd", "xsl", "xslt", "svg", "plist", "drawable"),
            emptyList(),
            setOf("xml"),
        ),
        EditorLanguage(
            "json", "JSON", "chunks/lang-json.js",
            setOf("json", "jsonc", "json5", "webmanifest"),
            listOf(
                Regex("^\\.?prettierrc$"),
                Regex("^\\.?eslintrc$"),
                Regex("^\\.?babelrc$"),
                Regex("^bun\\.lock$"),
                Regex("^package-lock\\.json$"),
                Regex("^composer\\.lock$"),
            ),
            setOf("json", "jsonc"),
        ),
        EditorLanguage(
            "yaml", "YAML", "chunks/lang-yaml.js",
            setOf("yaml", "yml"),
            listOf(Regex("^yarn\\.lock$"), Regex("^\\.?yamllint$")),
            setOf("yaml", "yml"),
        ),
        EditorLanguage(
            "markdown", "Markdown", "chunks/lang-markdown.js",
            setOf("md", "markdown", "mdown", "mkd", "mkdn"),
            emptyList(),
            setOf("md", "markdown"),
        ),
        EditorLanguage(
            "javascript", "JavaScript", "chunks/lang-javascript.js",
            setOf("js", "mjs", "cjs", "jsx"),
            listOf(Regex("^\\.?babelrc\\.js$")),
            setOf("js", "javascript", "node"),
        ),
        EditorLanguage(
            "typescript", "TypeScript", "chunks/lang-javascript.js",
            setOf("ts", "mts", "cts", "d.ts"),
            listOf(Regex("^.*\\.d\\.ts$")),
            setOf("ts", "typescript"),
        ),
        EditorLanguage(
            "jsx", "JSX", "chunks/lang-javascript.js",
            setOf("jsx"),
            emptyList(),
            setOf("jsx"),
        ),
        EditorLanguage(
            "tsx", "TSX", "chunks/lang-javascript.js",
            setOf("tsx"),
            emptyList(),
            setOf("tsx"),
        ),
        EditorLanguage(
            "html", "HTML", "chunks/lang-html.js",
            setOf("html", "htm", "xhtml", "vue", "svelte"),
            emptyList(),
            setOf("html"),
        ),
        EditorLanguage(
            "css", "CSS", "chunks/lang-css.js",
            setOf("css"),
            emptyList(),
            setOf("css"),
        ),
        EditorLanguage(
            "scss", "SCSS", "chunks/lang-sass.js",
            setOf("scss", "sass"),
            emptyList(),
            setOf("scss", "sass"),
        ),
        EditorLanguage(
            "less", "Less", "chunks/lang-less.js",
            setOf("less"),
            emptyList(),
            setOf("less"),
        ),
        EditorLanguage(
            "shell", "Shell", "chunks/legacy-shell.js",
            setOf("sh", "bash", "zsh", "fish", "ksh", "csh"),
            listOf(
                Regex("^\\.?bashrc$"),
                Regex("^\\.?zshrc$"),
                Regex("^\\.?profile$"),
                Regex("^\\.?bash_profile$"),
                Regex("^\\.?zprofile$"),
                Regex("^gradlew$"),
                Regex("^.*\\.bash_history$"),
                Regex("^Makefile$"),
                Regex("^makefile$"),
                Regex("^CMakeLists\\.txt$"),
            ),
            setOf("sh", "shell", "bash", "zsh"),
        ),
        EditorLanguage(
            "python", "Python", "chunks/lang-python.js",
            setOf("py", "pyw", "pyi"),
            listOf(Regex("^SConstruct$"), Regex("^SConscript$")),
            setOf("py", "python"),
        ),
        EditorLanguage(
            "groovy", "Groovy", "chunks/legacy-groovy.js",
            setOf("groovy", "gradle"),
            listOf(
                Regex("^build\\.gradle$"),
                Regex("^settings\\.gradle$"),
                Regex("^init\\.gradle$"),
            ),
            setOf("groovy", "gradle"),
        ),
        EditorLanguage(
            "properties", "Properties / INI", "chunks/legacy-properties.js",
            setOf("properties", "ini", "cfg", "conf", "env"),
            listOf(
                Regex("^\\.gitconfig$"),
                Regex("^\\.gitmodules$"),
                Regex("^\\.editorconfig$"),
                Regex("^\\.npmrc$"),
                Regex("^\\.env(\\..+)?$"),
                Regex("^gradle\\.properties$"),
                Regex("^local\\.properties$"),
            ),
            setOf("properties", "ini", "conf"),
        ),
        EditorLanguage(
            "toml", "TOML", "chunks/legacy-toml.js",
            setOf("toml"),
            listOf(Regex("^Cargo\\.lock$"), Regex("^poetry\\.lock$"), Regex("^Pipfile$")),
            setOf("toml"),
        ),
        EditorLanguage(
            "dockerfile", "Dockerfile", "chunks/legacy-dockerfile.js",
            setOf("dockerfile"),
            listOf(Regex("^Dockerfile(\\..+)?$"), Regex("^Containerfile(\\..+)?$")),
            setOf("dockerfile", "docker"),
        ),
        EditorLanguage(
            "diff", "Diff", "chunks/legacy-diff.js",
            setOf("diff", "patch"),
            emptyList(),
            setOf("diff", "patch"),
        ),
        EditorLanguage(
            "c", "C", "chunks/legacy-clike.js",
            setOf("c", "h"),
            emptyList(),
            setOf("c"),
        ),
        EditorLanguage(
            "cpp", "C++", "chunks/lang-cpp.js",
            setOf("cpp", "cc", "cxx", "hpp", "hh", "hxx", "ino"),
            emptyList(),
            setOf("cpp", "c++"),
        ),
        EditorLanguage(
            "go", "Go", "chunks/lang-go.js",
            setOf("go"),
            listOf(Regex("^go\\.mod$"), Regex("^go\\.sum$")),
            setOf("go", "golang"),
        ),
        EditorLanguage(
            "rust", "Rust", "chunks/lang-rust.js",
            setOf("rs"),
            emptyList(),
            setOf("rust"),
        ),
        EditorLanguage(
            "sql", "SQL", "chunks/lang-sql.js",
            setOf("sql", "sqlite", "db"),
            emptyList(),
            setOf("sql", "sqlite"),
        ),
        EditorLanguage(
            "php", "PHP", "chunks/lang-php.js",
            setOf("php", "phtml", "blade.php"),
            listOf(Regex("^.*\\.blade\\.php$")),
            setOf("php"),
        ),
        EditorLanguage(
            "csharp", "C#", "chunks/legacy-clike.js",
            setOf("cs", "csx"),
            emptyList(),
            setOf("cs", "csharp"),
        ),
        EditorLanguage(
            "scala", "Scala", "chunks/legacy-clike.js",
            setOf("scala", "sc"),
            emptyList(),
            setOf("scala"),
        ),
        EditorLanguage(
            "dart", "Dart", "chunks/legacy-clike.js",
            setOf("dart"),
            emptyList(),
            setOf("dart"),
        ),
        EditorLanguage(
            "objectivec", "Objective-C", "chunks/legacy-clike.js",
            setOf("m", "mm"),
            emptyList(),
            setOf("objectivec", "objc"),
        ),
    )

    private val byId: Map<String, EditorLanguage> = languages.associateBy { it.id }
    private val aliases: Map<String, EditorLanguage> = buildMap {
        for (language in languages) {
            put(language.id.lowercase(), language)
            for (alias in language.aliases) put(alias.lowercase(), language)
        }
    }

    /** The language with [id], or plain text when the id is unknown. */
    fun byId(id: String?): EditorLanguage =
        if (id == null) plain else aliases[id.trim().lowercase()] ?: plain

    fun all(): List<EditorLanguage> = languages.filter { it.id != "plaintext" }

    /** Resolve from a real file, reading a shebang only when the extension is not conclusive. */
    fun resolve(file: File): EditorLanguage {
        val shebang = try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(256)
                val read = stream.read(buffer)
                if (read <= 0) null else String(buffer, 0, read, Charsets.UTF_8)
                    .lineSequence()
                    .firstOrNull()
                    ?.takeIf { it.startsWith("#!") }
            }
        } catch (e: Exception) {
            null
        }
        return resolve(file.name, shebang)
    }

    fun resolve(filename: String, shebang: String? = null): EditorLanguage {
        val name = filename.substringAfterLast('/').lowercase()
        if (name.isEmpty()) return plain
        resolveExact(name)?.let { return it }
        resolveExtension(name)?.let { return it }
        resolveShebang(shebang)?.let { return it }
        return plain
    }

    private fun resolveExact(name: String): EditorLanguage? {
        // Exact names are intentionally checked before extensions: Dockerfile has no suffix and
        // `build.gradle.kts` must be Kotlin although it also ends with `.kts`.
        if (name == "build.gradle.kts" || name == "settings.gradle.kts") return byId("kotlin")
        if (name == "build.gradle" || name == "settings.gradle") return byId("groovy")
        if (name == "gradle.properties" || name == "local.properties") return byId("properties")
        if (name == ".gitignore" || name == ".dockerignore" || name == ".npmignore") return plain
        if (name == ".editorconfig" || name == ".gitconfig" || name == ".gitmodules" ||
            name == ".npmrc" || name == ".env" || name.startsWith(".env.")
        ) {
            return byId("properties")
        }
        for (language in languages) {
            if (language.filenames.any { it.matches(name) }) return language
        }
        return null
    }

    private fun resolveExtension(name: String): EditorLanguage? {
        var best: EditorLanguage? = null
        var bestLength = 0
        for (language in languages) {
            for (extension in language.extensions) {
                if (name.endsWith(".$extension") && extension.length > bestLength) {
                    best = language
                    bestLength = extension.length
                }
            }
        }
        return best
    }

    private fun resolveShebang(shebang: String?): EditorLanguage? {
        val line = shebang?.lowercase() ?: return null
        return when {
            line.contains("python") -> byId("python")
            line.contains("node") || line.contains("deno") || line.contains("bun") -> byId("javascript")
            line.contains("bash") || line.contains("zsh") || line.contains("sh") -> byId("shell")
            line.contains("ruby") -> plain
            line.contains("perl") -> plain
            else -> null
        }
    }
}
