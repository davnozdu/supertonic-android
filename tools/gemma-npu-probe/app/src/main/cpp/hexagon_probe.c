#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>

JNIEXPORT jint JNICALL Java_com_davnozdu_gemma4_npuprobe_HexagonNative_benchmark(
        JNIEnv *env, jobject self, jstring directory, jobjectArray arguments,
        jstring stdout_path, jstring stderr_path) {
    (void)self;
    const char *libs = (*env)->GetStringUTFChars(env, directory, NULL);
    const char *out = (*env)->GetStringUTFChars(env, stdout_path, NULL);
    const char *err = (*env)->GetStringUTFChars(env, stderr_path, NULL);
    int out_fd = open(out, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    int err_fd = open(err, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    int saved_out = dup(STDOUT_FILENO), saved_err = dup(STDERR_FILENO);
    int result = -1;
    if (out_fd < 0 || err_fd < 0 || saved_out < 0 || saved_err < 0) goto cleanup;
    fflush(NULL);
    dup2(out_fd, STDOUT_FILENO);
    dup2(err_fd, STDERR_FILENO);
    setenv("ADSP_LIBRARY_PATH", libs, 1);
    char path[4096];
    snprintf(path, sizeof(path), "%s/libllama-bench-impl.so", libs);
    void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) { fprintf(stderr, "dlopen failed: %s\n", dlerror()); goto cleanup; }
    int (*bench)(int, char **) = (int (*)(int, char **)) dlsym(handle, "_Z11llama_benchiPPc");
    if (bench == NULL) { fprintf(stderr, "dlsym failed: %s\n", dlerror()); goto cleanup; }
    int argc = (*env)->GetArrayLength(env, arguments);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    jstring *strings = calloc((size_t)argc, sizeof(jstring));
    if (!argv || !strings) { free(argv); free(strings); goto cleanup; }
    for (int i = 0; i < argc; ++i) {
        strings[i] = (jstring)(*env)->GetObjectArrayElement(env, arguments, i);
        argv[i] = (char *)(*env)->GetStringUTFChars(env, strings[i], NULL);
    }
    result = bench(argc, argv);
    for (int i = 0; i < argc; ++i) {
        (*env)->ReleaseStringUTFChars(env, strings[i], argv[i]);
        (*env)->DeleteLocalRef(env, strings[i]);
    }
    free(argv); free(strings);
    // The library owns process-wide registries; release them by ending the
    // isolated APK process between trials, rather than dlclosing live globals.
cleanup:
    fflush(NULL);
    if (saved_out >= 0) { dup2(saved_out, STDOUT_FILENO); close(saved_out); }
    if (saved_err >= 0) { dup2(saved_err, STDERR_FILENO); close(saved_err); }
    if (out_fd >= 0) close(out_fd);
    if (err_fd >= 0) close(err_fd);
    (*env)->ReleaseStringUTFChars(env, directory, libs);
    (*env)->ReleaseStringUTFChars(env, stdout_path, out);
    (*env)->ReleaseStringUTFChars(env, stderr_path, err);
    return result;
}
