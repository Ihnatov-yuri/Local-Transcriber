# app/libs/

Local AAR dependencies that aren't published to a Maven repository we use,
so they can't go in `gradle/libs.versions.toml` as a normal coordinate.

Nothing here is committed to git (see `.gitignore`) — run the matching
fetch script after cloning, or before `./gradlew assembleDebug` in CI.

| File | Fetched by | Notes |
|---|---|---|
| `sherpa-onnx-<version>.aar` | `scripts/fetch-sherpa-onnx.sh` | Official k2-fsa Android AAR. Bundles `libonnxruntime.so` per ABI — no separate ONNX Runtime dependency needed. |
