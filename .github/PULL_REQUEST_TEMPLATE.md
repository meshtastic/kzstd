<!--
Thank you for contributing to kzstd!
Fill out the sections below. Delete any that don't apply.
-->

## Summary

<!-- One or two sentences: what does this PR do? -->

## Type of change

- [ ] Bug fix (non-breaking)
- [ ] New feature (non-breaking)
- [ ] Breaking change (will require a SemVer-MINOR pre-1.0 / SemVer-MAJOR post-1.0 bump)
- [ ] Documentation only
- [ ] Infrastructure / CI / build

## Related issue / discussion

<!-- Link the issue: Fixes #123 / Refs #456. For non-trivial changes, link the design discussion. -->

## Affirmations

- [ ] All commits are signed off (DCO — `git commit -s`).
- [ ] I have read [`CONTRIBUTING.md`](../CONTRIBUTING.md).
- [ ] If this changes the public API, I have run `./gradlew apiDump` and committed the regenerated `api/*.api` files.
- [ ] If this changes codec behavior or the wire frame, I have verified round-trip and libzstd interop (`./gradlew jvmTest`).
- [ ] I have run `./gradlew build` locally and it passes.

## How was this verified?

<!--
Describe testing — unit tests, the zstd-jni interop oracle, cross-target runs.
For codec changes, note any before/after ratio or interop checks.
-->

## Notes for reviewers

<!-- Anything else the reviewer should know. Tricky areas, open questions, follow-ups. -->
