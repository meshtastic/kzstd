# Security policy

## Reporting a vulnerability

**Do not open a public GitHub issue.** Instead:

- File a private [GitHub Security Advisory](https://github.com/meshtastic/kzstd/security/advisories/new)
  on this repo, or
- Email the Meshtastic security address (see [meshtastic.org](https://meshtastic.org)).

We aim to acknowledge reports within 5 business days and to ship a fix or
mitigation within 90 days, depending on severity. You will be credited in the
advisory unless you prefer to remain anonymous.

## Supported versions

kzstd is pre-1.0. Only the latest published release receives security fixes; there
is no LTS branch. Once 1.0 ships, a support window will be published here.

## Scope

kzstd decodes **untrusted input** — zstd frames and dictionaries supplied by a
peer — so the security surface is decompression of hostile data. In scope:

- Decoder memory-safety and termination on malformed or adversarial frames and
  dictionaries: no unbounded allocation, no infinite loops, and typed `ZstdException`
  failures rather than crashes.
- The decompression-bomb guard (the required `maxSize` cap).
- Build and release infrastructure (`.github/workflows/`, dependency sources).

Out of scope (report upstream):

- The Zstandard format itself ([RFC 8878](https://datatracker.ietf.org/doc/rfc8878/)).
- A consumer's handling of already-decompressed output.

## Disclosure

After a fix ships, we publish the advisory with a CVE ID where applicable.
Coordinated disclosure is preferred.
