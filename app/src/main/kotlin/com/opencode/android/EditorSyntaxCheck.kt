package com.opencode.android

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONTokener
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

/**
 * Light syntax check for the two formats that have a parser available on every Android version.
 * This is not a linter: it only answers "does this parse?" and, for XML, where it stopped.
 */
object EditorSyntaxCheck {

    data class Result(val ok: Boolean, val message: String)

    fun supports(languageId: String): Boolean = languageId == "json" || languageId == "xml"

    fun check(languageId: String, text: String): Result = when (languageId) {
        "json" -> checkJson(text)
        "xml" -> checkXml(text)
        else -> Result(false, "unsupported")
    }

    private fun checkJson(text: String): Result {
        if (text.isBlank()) return Result(false, "JSON rỗng")
        return try {
            val tokener = JSONTokener(text)
            tokener.nextValue()
            if (tokener.nextClean() != 0.toChar()) {
                Result(false, "Còn ký tự thừa sau giá trị JSON")
            } else {
                Result(true, "")
            }
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun checkXml(text: String): Result {
        if (text.isBlank()) return Result(false, "XML rỗng")
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.setExpandEntityReferences(false)
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            // Android's XMLConstants omits these Java 8 fields; the attribute names are the
            // standard property URIs and unsupported parsers simply reject them.
            runCatching {
                factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
            Result(true, "")
        } catch (e: SAXParseException) {
            val where = buildString {
                if (e.lineNumber > 0) {
                    append("dòng ")
                    append(e.lineNumber)
                    if (e.columnNumber > 0) {
                        append(", cột ")
                        append(e.columnNumber)
                    }
                    append(": ")
                }
                append(e.message ?: "XML không hợp lệ")
            }
            Result(false, where)
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
