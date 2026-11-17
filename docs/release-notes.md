# Release notes

What each stable release of Mudlet for Android says is new. `.github/workflows/release.yml` takes the section headed
with the release's version, `## <version>` exactly as in its tag `android-v<version>`, as Google Play's "What's new in
this version" and as the start of the GitHub release's notes; a tag with no section here fails before it builds
([`release.md`](release.md), "Cutting a release"). Play shows the text as it is, so write plain lines (a leading "- "
reads fine as a list), at most 500 characters, in English: Play's other languages fall back to it.

Each release has a section below this example, the newest first:

```text
## x.y.z

- What a player will notice first.
- Then the rest, in a line each.
```
