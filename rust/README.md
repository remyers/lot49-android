orignally from tutorial here: https://robertohuertas.com/2019/10/27/rust-for-android-ios-flutter/

# Android targets
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

# iOS targets
rustup target add aarch64-apple-ios armv7-apple-ios armv7s-apple-ios x86_64-apple-ios i386-apple-ios

# iOS tools

## install the Xcode build tools.
xcode-select --install
## this cargo subcommand will help you create a universal library for use with iOS.
cargo install cargo-lipo
## this tool will let you automatically create the C/C++11 headers of the library.
cargo install cbindgen

# Android tools

cargo install cargo-ndk


