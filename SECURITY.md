# Security policy

This library parses untrusted package metadata, so parsing bugs can be security
bugs. Examples: inputs that take super-linear time, exhaust memory or the stack,
or are accepted differently from pip (a "parser differential").

Please report vulnerabilities privately through GitHub's
[private vulnerability reporting](https://github.com/greenflagged/python-packaging-kotlin/security/advisories/new),
not in a public issue. Include the input that causes the problem if you can.

A difference in behaviour from Python `packaging` 26.3 that is not listed under
"Known differences" in the README counts as a bug. If it changes what a package
index, installer or vetting tool built on this library would accept, treat it as
a security report.
