# Vendored dependencies

## winrm-java-2.1.00.jar

- **Source:** https://github.com/MetricsHub/winrm-java (tag `v2.1.00`, commit `984fcce5e5100e6a125dcd13352e00568633cac3`)
- **License:** Apache License 2.0
- **Why vendored instead of a normal Gradle dependency:** not published to Maven Central, and JitPack
  can't build it (its parent POM `org.metricshub:oss-parent:5` isn't resolvable there - confirmed via a
  404 from JitPack's artifact endpoint). Built directly from the tagged source with `javac` (zero
  runtime dependencies other than the JDK itself; `spotbugs-annotations` is `provided`/compile-time
  only in the upstream POM) and jarred with `jar`.
- **Why this library instead of `winrm4j`:** `winrm4j` (the previous dependency) wraps an old Apache
  CXF 3.3.9 stack whose NTLM implementation has a confirmed, long-standing, unfixed bug -
  [cloudsoft/winrm4j#110](https://github.com/cloudsoft/winrm4j/issues/110) (open since 2019, zero
  comments) - where the NTLM handshake completes successfully but the server rejects the subsequent
  encrypted WS-Man payload with a fresh 401, surfacing as CXF's own "Authorization loop detected"
  error. Reproduced against both real target machines this project talks to; confirmed root-caused via
  wire-level logging (the handshake genuinely succeeds - visible both in CXF's own debug logs and in
  the target's Windows Security event log, event 4624 - but the encrypted follow-up request is
  rejected). `winrm-java` 2.0.0+ removed the CXF backend entirely in favor of a from-scratch NTLM/WinRM
  implementation specifically to fix this class of interoperability issue; verified directly against
  both real targets with a standalone test harness before adopting it here.
- **To rebuild:** clone the tag, then from the repo root:
  ```
  javac -cp <spotbugs-annotations jar> -d out $(find src/main/java -iname '*.java')
  jar cf winrm-java-2.1.00.jar -C out .
  ```
- **To upgrade:** repeat the above against a newer tag, replace this jar, update the version in
  `build.gradle.kts`'s dependency comment and this file.
