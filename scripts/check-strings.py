#!/usr/bin/env python3
"""The translation checks scripts/lint.sh runs as "strings" (docs/translations.md):

  - no user-visible text is written into the Kotlin of a module that has a UI (Compose's Text("..."), a
    contentDescription = "...", a Toast's, a notification's or a dialog's text), as Android lint's HardcodedText
    looks only at XML layouts. A literal with no letters in it ("", "?", "${a} / ${b}") is not text. A line that must
    keep its literal says why in a comment on that line: // untranslated: <why>
  - every translatable <string>, <plurals> and <string-array> in a values/strings.xml has a comment for translators
    right before it, which Crowdin shows beside the text.

usage: check-strings.py [file...]   (the tracked and new .kt and values/strings.xml files of MODULES when none is given)
"""
import os
import re
import subprocess
import sys
import xml.parsers.expat

# The modules with a UI: :protocol has none
MODULES = ("app", "engine-service")
# Source sets that do not ship
TEST_SOURCE_SETS = ("/src/test/", "/src/androidTest/")

# Where a string literal is text a user sees, each pattern ending at the literal's opening quote
TEXT_CALLS = re.compile(
    r"""
    \b(?:Text|BasicText|ClickableText)\s*\(\s*"                     # Compose: Text("...")
    | \b(?P<named>text|contentDescription|stateDescription|label|placeholder|title|subtitle|message|supportingText
         |onClickLabel|onLongClickLabel|paneTitle|actionLabel|confirmLabel|dismissLabel)\s*=\s*"
                                                                      # Compose's and the semantics' named arguments
    | \b(?:showSnackbar|setText|setTitle|setMessage|setPositiveButton|setNegativeButton|setNeutralButton
         |setContentTitle|setContentText|setSubText|setTicker|setBigContentTitle|setSummaryText|bigText
         |setName|setDescription)\s*\(\s*"                            # a snackbar, a View, a dialog, a notification
    | \b(?:makeText|addAction|NotificationChannel|NotificationChannelGroup)\s*\([^,()]*,\s*"
                                                                      # the second argument: a Toast's text, a
                                                                      # notification action's or channel's name
    """,
    re.VERBOSE,
)
OPT_OUT = re.compile(r"//\s*untranslated:\s*\S")
LETTER = re.compile(r"[^\W\d_]")


def strip_comments(src):
    """src with its comments blanked out, newlines kept, and the start offsets of its string literals."""
    out, literals = list(src), []
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            end = src.find("\n", i)
            end = n if end < 0 else end
            out[i:end] = " " * (end - i)
            i = end
        elif src.startswith("/*", i):
            # Kotlin's block comments nest
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif src.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out[i:j] = [c if c == "\n" else " " for c in src[i:j]]
            i = j
        elif src[i] == '"':
            literals.append(i)
            i = read_string(src, i)[1]
        elif src[i] == "'":
            # a character literal: '"', '\'', 'A'
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            i = j + 1
        else:
            i += 1
    return "".join(out), literals


def read_string(src, i):
    """The string literal starting at src[i] as (its text outside ${...} templates, the offset after it)."""
    raw = src.startswith('"""', i)
    quote = '"""' if raw else '"'
    j, n, text = i + len(quote), len(src), []
    while j < n:
        if src.startswith(quote, j):
            # a raw string may end in more quotes than three: the last three close it
            if raw:
                while src.startswith('""""', j):
                    text.append('"')
                    j += 1
            return "".join(text), j + len(quote)
        if not raw and src[j] == "\\":
            j += 2
        elif src.startswith("${", j):
            j = skip_template(src, j + 2)
        elif src[j] == "$" and j + 1 < n and (src[j + 1].isalpha() or src[j + 1] == "_"):
            j += 1
            while j < n and (src[j].isalnum() or src[j] == "_"):
                j += 1
        else:
            text.append(src[j])
            j += 1
    return "".join(text), n


def skip_template(src, j):
    """The offset after the } that closes the ${ before src[j], past any strings and braces inside it."""
    depth, n = 1, len(src)
    while j < n:
        c = src[j]
        if c == '"':
            j = read_string(src, j)[1]
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    return n


def check_kotlin(path, src):
    code, literals = strip_comments(src)
    starts = set(literals)
    lines = src.splitlines()
    problems = []
    for m in TEXT_CALLS.finditer(code):
        quote = m.end() - 1
        if quote not in starts:
            continue
        # "val title = ..." declares a variable; it is no argument
        if m.group("named") and re.search(r"\b(?:val|var)\s+$", code[max(0, m.start() - 8) : m.start()]):
            continue
        text = read_string(src, quote)[0]
        if not LETTER.search(text):
            continue
        line = src.count("\n", 0, quote) + 1
        if OPT_OUT.search(lines[line - 1]):
            continue
        shown = text if len(text) <= 40 else text[:37] + "..."
        problems.append((path, line, f'hard-coded text "{shown}": put it in strings.xml and use stringResource() '
                         "or getString(), or say why it stays with // untranslated: <why>"))
    return problems


def check_strings_xml(path, data):
    """Each translatable entry right under <resources> has a comment right before it."""
    problems = []
    parser = xml.parsers.expat.ParserCreate()
    state = {"depth": 0, "commented": False}

    def start(name, attrs):
        if state["depth"] == 1 and name in ("string", "plurals", "string-array"):
            if attrs.get("translatable") != "false" and not state["commented"]:
                problems.append((path, parser.CurrentLineNumber,
                                 f'<{name} name="{attrs.get("name", "?")}"> has no comment for translators right '
                                 "before it: say where the text is shown and what any %1$s stands for"))
        if state["depth"] == 1:
            state["commented"] = False
        state["depth"] += 1

    def end(name):
        state["depth"] -= 1

    def comment(text):
        if state["depth"] == 1:
            state["commented"] = bool(text.strip())

    def chars(text):
        if state["depth"] == 1 and text.strip():
            state["commented"] = False

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CommentHandler = comment
    parser.CharacterDataHandler = chars
    try:
        parser.Parse(data, True)
    except xml.parsers.expat.ExpatError as e:
        problems.append((path, e.lineno, f"not well-formed XML: {e}"))
    return problems


def is_strings_xml(path):
    return re.search(r"(^|/)src/[^/]+/res/values/strings\.xml$", path) is not None


def tracked_files():
    patterns = [f"{m}/src/*.kt" for m in MODULES] + [f"{m}/src/*/res/values/strings.xml" for m in MODULES]
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *patterns],
                         check=True, capture_output=True, text=True).stdout
    return [p for p in out.split("\0") if p and not any(s in "/" + p for s in TEST_SOURCE_SETS)]


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if argv:
        files = argv
    else:
        os.chdir(root)
        files = tracked_files()
    problems, kotlin, xml_files = [], 0, 0
    for path in files:
        if not os.path.exists(path):
            # deleted in the work tree but still in the index
            continue
        if path.endswith(".kt"):
            kotlin += 1
            with open(path, encoding="utf-8") as f:
                problems += check_kotlin(path, f.read())
        elif is_strings_xml(path):
            xml_files += 1
            with open(path, "rb") as f:
                problems += check_strings_xml(path, f.read())
        else:
            print(f"{path}: neither a .kt file nor a values/strings.xml", file=sys.stderr)
            return 2
    if not argv and (kotlin == 0 or xml_files == 0):
        print(f"found {kotlin} Kotlin files and {xml_files} strings.xml files: is this the repository?", file=sys.stderr)
        return 2
    annotate = os.environ.get("GITHUB_ACTIONS") == "true"
    for path, line, message in problems:
        print(f"{path}:{line}: {message}")
        if annotate:
            print(f"::error file={path},line={line}::{message}")
    if problems:
        print(f"{len(problems)} problem(s) in {kotlin} Kotlin and {xml_files} strings.xml files "
              "(docs/translations.md)", file=sys.stderr)
        return 1
    print(f"no hard-coded text in {kotlin} Kotlin files; every string in {xml_files} strings.xml files has a comment")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
