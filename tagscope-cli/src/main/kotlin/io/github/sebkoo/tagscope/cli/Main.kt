package io.github.sebkoo.tagscope.cli

import kotlin.system.exitProcess

/**
 * tagscope — decode an EMV BER-TLV string into a labelled, nested tag tree.
 *
 * Decode is the only action today, and the default one, so a bare `tagscope <hex>` decodes; a
 * subcommand layer (`decode` / `encode` / …) is a deliberate later addition, not built yet.
 *
 * [main] is a thin shell around [runCli]: it supplies the input (the argument, or standard input
 * when none was given), prints what [runCli] produced, and exits with the code it returned. All the
 * logic — and the single place cardholder data is handled — sits under [runCli] and [describe],
 * where it is tested without a process.
 */
fun main(args: Array<String>) {
    val outcome = runCli(args) { System.`in`.readBytes().toString(Charsets.UTF_8) }

    if (outcome.stdout.isNotEmpty()) {
        println(outcome.stdout)
    }
    if (outcome.stderr.isNotEmpty()) {
        System.err.println(outcome.stderr)
    }
    if (outcome.exitCode != ExitCode.SUCCESS) {
        exitProcess(outcome.exitCode)
    }
}
