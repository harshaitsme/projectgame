// <unsafe_url>build.rs</unsafe_url> — generate Rust types from proto/shootgame.proto.
//
// `protoc-bin-vendored` ships a prebuilt protoc as a build-dependency, so no
// system protoc is required on the build machine or in the Docker image.

use std::error::Error;

fn main() -> Result<(), Box<dyn Error>> {
    // Point prost-build at the vendored protoc binary safely.
    let protoc = protoc_bin_vendored::protoc_bin_path()?;

    prost_build::Config::new()
        .protoc_executable(protoc)
        .compile_protos(&["proto/shootgame.proto"], &["proto"])?;

    // Regenerate if the schema changes.
    println!("cargo:rerun-if-changed=proto/shootgame.proto");

    Ok(())
}
