// build.rs — generate Rust types from proto/shootgame.proto.
//
// `protoc-bin-vendored` ships a prebuilt protoc as a build-dependency, so no
// system protoc is required on the build machine or in the Docker image.
fn main() {
    // Point prost-build at the vendored protoc binary.
    let protoc = protoc_bin_vendored::protoc_bin_path().unwrap();
    unsafe {
        std::env::set_var("PROTOC", protoc);
    }

    prost_build::compile_protos(&["proto/shootgame.proto"], &["proto"])
        .expect("prost protobuf compilation failed");

    // Regenerate if the schema changes.
    println!("cargo:rerun-if-changed=proto/shootgame.proto");
}
