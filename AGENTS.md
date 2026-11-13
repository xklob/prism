# Prism workflow

- After completing a feature or rework, run the relevant checks, commit and push the changes, merge them into `main`, and publish a stable GitHub release in the same session. This is the default delivery workflow; use a draft or prerelease only when requested or when a concrete unresolved issue prevents a stable release.
- Scope merges and releases to the requested work. Feature suggestions remain proposals until the user requests implementation.
- Increment Android `versionCode` for a new APK, keep the existing release signing identity, and update the version name and release documentation together. Update and package the Windows controller when its behavior changes.
- Verify the published artifacts against local checksums. Preserve human Git authorship and the verified primary-agent attribution required by the global instructions.
- Documentation-only updates normally do not require a new APK unless the user also requests a release.
- Use real application captures for screenshots. Label emulator captures and synthetic fixtures accurately; do not present them as physical-device or real-song evidence.
- Keep credentials, private recordings, signing material, and temporary review artifacts out of Git and release uploads. Keep physical-device performance and synchronization claims separate from emulator results.
