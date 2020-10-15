package org.globalmeshlabs.rust_android_lib

external fun hello(to: String): String
external fun helloDirect(to: String): String

fun loadRustyLib() {
    System.loadLibrary("rustylib")
}