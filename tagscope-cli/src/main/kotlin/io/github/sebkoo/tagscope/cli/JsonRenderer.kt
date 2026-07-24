package io.github.sebkoo.tagscope.cli

import io.github.sebkoo.tagscope.tlv.TagClass

/**
 * Serialises the described forest as JSON — the machine-readable decode behind `--json`.
 *
 * A hand-rolled writer, not a library. Emitting JSON is a handful of rules — quote strings, escape
 * a short list of characters, comma-separate — and writing them keeps the repo dependency-free.
 * Only the writing side is hand-rolled; nothing here parses JSON, where hand-rolling would be the
 * mistake this deliberately does not make.
 *
 * The masking guarantee is upstream: a masked node arrives here with no octets to leak, so this
 * writer only has to not invent a `hex` field for it. It prints `"sensitive": true` so a consumer
 * knows a value was withheld, and a mask token in place of the value.
 */
internal fun renderJson(nodes: List<RenderNode>): String =
    buildString {
        writeArray(nodes, indent = 0)
    }

private fun StringBuilder.writeArray(
    nodes: List<RenderNode>,
    indent: Int,
) {
    if (nodes.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    val pad = STEP.repeat(indent + 1)
    nodes.forEachIndexed { index, node ->
        append(pad)
        writeNode(node, indent + 1)
        if (index < nodes.lastIndex) append(',')
        append('\n')
    }
    append(STEP.repeat(indent)).append(']')
}

private fun StringBuilder.writeNode(
    node: RenderNode,
    indent: Int,
) {
    val fields = scalarFields(node)
    val hasChildren = node.constructed
    val inner = STEP.repeat(indent + 1)

    append("{\n")
    fields.forEachIndexed { index, (key, value) ->
        append(inner).append(jsonString(key)).append(": ").append(value)
        val last = index == fields.lastIndex && !hasChildren
        if (!last) append(',')
        append('\n')
    }
    if (hasChildren) {
        append(inner).append(jsonString("children")).append(": ")
        writeArray(node.children, indent + 1)
        append('\n')
    }
    append(STEP.repeat(indent)).append('}')
}

/** The object's scalar fields, in order, each value already a JSON fragment. */
private fun scalarFields(node: RenderNode): List<Pair<String, String>> =
    buildList {
        add("tag" to jsonString(node.tagHex))
        add("name" to jsonString(node.name))
        add("class" to jsonString(classLabel(node.tagClass)))
        add("constructed" to node.constructed.toString())
        add("length" to node.length.toString())
        if (node.sensitive) {
            add("sensitive" to "true")
        }
        when {
            node.masked -> add("value" to jsonString(MASK_JSON))
            node.value != null -> add("value" to jsonString(node.value))
        }
        if (node.valueHex != null) {
            add("hex" to jsonString(node.valueHex))
        }
        if (node.decodeNote != null) {
            add("note" to jsonString(node.decodeNote))
        }
        if (node.meanings.isNotEmpty()) {
            val meanings = node.meanings.joinToString(prefix = "[", postfix = "]", separator = ", ") { jsonString(it) }
            add("meanings" to meanings)
        }
    }

private fun classLabel(tagClass: TagClass): String =
    when (tagClass) {
        TagClass.UNIVERSAL -> "universal"
        TagClass.APPLICATION -> "application"
        TagClass.CONTEXT_SPECIFIC -> "context-specific"
        TagClass.PRIVATE -> "private"
    }

/**
 * A JSON string literal: quoted, with the characters JSON requires escaped and nothing else.
 * Control characters are compared by code point rather than by char literal, so the source carries
 * no invisible bytes and Kotlin's lack of a `\f` char escape does not matter.
 */
private fun jsonString(text: String): String =
    buildString(text.length + 2) {
        append('"')
        for (ch in text) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code == BACKSPACE -> append("\\b")
                ch.code == FORM_FEED -> append("\\f")
                ch.code < FIRST_PRINTABLE ->
                    append("\\u").append(ch.code.toString(16).padStart(UNICODE_DIGITS, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }

private const val STEP: String = "  "
private const val BACKSPACE: Int = 0x08
private const val FORM_FEED: Int = 0x0C
private const val FIRST_PRINTABLE: Int = 0x20
private const val UNICODE_DIGITS: Int = 4
