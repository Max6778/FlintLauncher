// Empty stub library.
// Its only purpose is to satisfy dlopen()'s DT_NEEDED check for
// glibc-only SONAMEs (libpthread.so.0, libm.so.6, etc.) that don't
// exist on Android. Bionic already provides the real symbols
// (pthread_*, math functions) directly inside libc.so/libm.so, so
// once this "empty" library is found by name, the actual function
// calls resolve fine against the libc/libm already loaded.

void __glibc_shim_placeholder(void) {}
