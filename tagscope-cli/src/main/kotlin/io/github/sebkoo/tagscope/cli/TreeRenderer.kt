package io.github.sebkoo.tagscope.cli

/**
 * Renders the described forest as indented text — the human decode, and the CLI's headline output.
 *
 * One line per data object: the tag, the name EMV gives it, its length, and its value. A
 * constructed template's children follow it, indented a level; a decoded bit field's meanings
 * follow their node, indented deeper and one to a line, because a TVR with six bits set says six
 * things and a single line would bury them.
 *
 * The tag, name and length columns are aligned to the widest entry in the whole tree, so the values
 * line up down the page — this is the README screenshot, and a ragged left edge reads as noise.
 */
internal fun renderTree(nodes: List<RenderNode>): String {
    val rows = mutableListOf<Row>()
    for (node in nodes) {
        collectRows(node, depth = 0, rows)
    }
    if (rows.isEmpty()) {
        return ""
    }

    val tagWidth = rows.maxOf { it.depth * INDENT_WIDTH + it.node.tagHex.length }
    val nameWidth = rows.maxOf { it.node.name.length }
    val lengthWidth = rows.maxOf { lengthColumn(it.node).length }

    return buildString {
        for (row in rows) {
            val node = row.node
            val tagColumn = "  ".repeat(row.depth) + node.tagHex
            val line =
                buildString {
                    append(tagColumn.padEnd(tagWidth))
                    append(GAP).append(node.name.padEnd(nameWidth))
                    append(GAP).append(lengthColumn(node).padEnd(lengthWidth))
                    valueColumn(node)?.let { append(GAP).append(it) }
                }
            append(line.trimEnd()).append('\n')
            for (meaning in node.meanings) {
                append("  ".repeat(row.depth)).append(MEANING_PREFIX).append(meaning).append('\n')
            }
        }
    }.trimEnd('\n')
}

private class Row(
    val node: RenderNode,
    val depth: Int,
)

private fun collectRows(
    node: RenderNode,
    depth: Int,
    into: MutableList<Row>,
) {
    into.add(Row(node, depth))
    for (child in node.children) {
        collectRows(child, depth + 1, into)
    }
}

private fun lengthColumn(node: RenderNode): String = "[${node.length}]"

private fun valueColumn(node: RenderNode): String? =
    when {
        node.constructed -> null
        node.masked -> MASK_TREE
        node.value == null -> null
        node.decodeNote != null -> "${node.value}  <undecodable: ${node.decodeNote}>"
        else -> node.value
    }

private const val INDENT_WIDTH: Int = 2
private const val GAP: String = "  "
private const val MEANING_PREFIX: String = "      - "
