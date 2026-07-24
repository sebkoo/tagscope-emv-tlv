# Tagscope terminal demo — VHS tape (https://github.com/charmbracelet/vhs)
#
# Regenerate from the repository root:
#
#   ./gradlew :tagscope-cli:installDist
#   vhs docs/demo.tape
#
# This file is the source of the GIF; docs/demo.gif is its output and nothing else —
# no post-processing, so `vhs docs/demo.tape` reproduces the committed file.
#
# Every command below is a real tagscope invocation. Nothing is staged or hand-written.
# The data is the project's own synthetic golden vectors:
#   6F15840E...  = vectors/01-pse-fci.hex (PSE FCI, published spec sample)
#   5A081111222233334440 = a 5A carrying a deliberately unusable account number: 1111 is an
#                          unassigned issuer identifier and the digits fail the Luhn check a
#                          real account number satisfies, the same fixture principle as
#                          NumericDecodeTest and Track2DecodeTest. It is shown masked and
#                          then revealed, so no Luhn-valid card number is ever rendered.
#   6F02A500     = vectors/lint/07-fci-missing-df-name.hex (ERROR, exits 1)

Output docs/demo.gif

Set Shell "bash"
Set FontSize 14
Set Width 880
Set Height 300
Set Padding 20
Set Theme "Catppuccin Mocha"
Set Framerate 24
Set TypingSpeed 30ms

# Put the built CLI on PATH and use a bare prompt, so no local path or hostname is recorded.
Hide
Type `export PATH="$PWD/tagscope-cli/build/install/tagscope/bin:$PATH"` Enter
Type `export PS1="$ " && clear` Enter
Show

Sleep 1s

# 1 — structure: raw bytes become a labelled, nested tag tree.
Type "# EMV bytes in, labelled tag tree out" Enter
Sleep 500ms
Type "tagscope 6F15840E315041592E5359532E4444463031A503880101" Sleep 400ms Enter
Sleep 3s

Ctrl+L
Sleep 500ms

# 2 — the PAN is masked by default; revealing it is an explicit opt-in.
Type "# a PAN is masked by default" Enter
Sleep 500ms
Type "tagscope 5A081111222233334440" Sleep 400ms Enter
Sleep 3s
Type "tagscope --reveal 5A081111222233334440" Sleep 400ms Enter
Sleep 2500ms

Ctrl+L
Sleep 500ms

# 3 — lint finds the EMV inconsistency and exits non-zero, so it can gate a cert script.
Type "# and it gates a script: errors exit non-zero" Enter
Sleep 500ms
Type "tagscope lint 6F02A500" Sleep 400ms Enter
Sleep 2500ms
Type "echo $?" Sleep 300ms Enter
Sleep 3s
