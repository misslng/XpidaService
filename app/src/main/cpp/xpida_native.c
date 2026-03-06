#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <stdint.h>
#include <android/log.h>

#define TAG "XpidaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define XPIDA_NR     282
#define XPIDA_MAGIC  0x7870696461ULL

#define MAX_TEXT_BUF  (1 * 1024 * 1024)
#define MAX_BIN_BUF   (64 * 1024 * 1024 + 4096)
#define DUMP_CHUNK    (64ULL * 1024 * 1024)

static long xpida_call(const char *ctl, char *buf, int buflen) {
    return syscall(XPIDA_NR, XPIDA_MAGIC, ctl, buf, (long)buflen);
}

/*
 * All text commands return byte[] instead of jstring.
 * This avoids NewStringUTF which requires null-terminated Modified UTF-8
 * and crashes on large or binary-containing output.
 */
static jbyteArray do_text_cmd(JNIEnv *env, const char *ctl) {
    int buflen = MAX_TEXT_BUF;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) {
        const char *err = "ERROR: mmap failed";
        jbyteArray r = (*env)->NewByteArray(env, (jsize)strlen(err));
        if (r) (*env)->SetByteArrayRegion(env, r, 0, (jsize)strlen(err), (jbyte *)err);
        return r;
    }
    buf[0] = '\0';

    long rc = xpida_call(ctl, buf, buflen);

    jbyteArray result;
    if (rc < 0) {
        char err[128];
        int len = snprintf(err, sizeof(err), "ERROR: rc=%ld", rc);
        result = (*env)->NewByteArray(env, (jsize)len);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)len, (jbyte *)err);
    } else {
        jsize len = (rc > 0 && rc < buflen) ? (jsize)rc : (jsize)strlen(buf);
        result = (*env)->NewByteArray(env, len);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, len, (jbyte *)buf);
    }

    munmap(buf, buflen);
    return result;
}

/* ---------- ping ---------- */

JNIEXPORT jbyteArray JNICALL
Java_com_xp_xpidaservice_XpidaNative_ping(JNIEnv *env, jclass clazz) {
    return do_text_cmd(env, "ping");
}

/* ---------- ps ---------- */

JNIEXPORT jbyteArray JNICALL
Java_com_xp_xpidaservice_XpidaNative_ps(JNIEnv *env, jclass clazz) {
    return do_text_cmd(env, "ps");
}

/* ---------- find <name> ---------- */

JNIEXPORT jbyteArray JNICALL
Java_com_xp_xpidaservice_XpidaNative_find(JNIEnv *env, jclass clazz, jstring name) {
    const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
    if (!cname) return NULL;

    char ctl[512];
    snprintf(ctl, sizeof(ctl), "find %s", cname);
    (*env)->ReleaseStringUTFChars(env, name, cname);

    return do_text_cmd(env, ctl);
}

/* ---------- maps <pid> ---------- */

JNIEXPORT jbyteArray JNICALL
Java_com_xp_xpidaservice_XpidaNative_maps(JNIEnv *env, jclass clazz, jint pid) {
    char ctl[256];
    snprintf(ctl, sizeof(ctl), "maps %d", pid);
    return do_text_cmd(env, ctl);
}

/* ---------- read <pid> <hex_addr> <size> -> byte[] ---------- */

JNIEXPORT jbyteArray JNICALL
Java_com_xp_xpidaservice_XpidaNative_readMem(JNIEnv *env, jclass clazz,
                                               jint pid, jlong addr, jint size) {
    if (size <= 0) return NULL;
    int buflen = size + 4096;
    if (buflen > MAX_BIN_BUF) buflen = MAX_BIN_BUF;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) return NULL;

    char ctl[256];
    snprintf(ctl, sizeof(ctl), "read %d %llx %d", pid, (unsigned long long)addr, size);

    long rc = xpida_call(ctl, buf, buflen);

    jbyteArray result = NULL;
    if (rc > 0) {
        result = (*env)->NewByteArray(env, (jsize)rc);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)rc, (jbyte *)buf);
    }

    munmap(buf, buflen);
    return result;
}

/* ---------- dumpChunk: single syscall, max DUMP_CHUNK bytes ---------- */

JNIEXPORT jbyteArray JNICALL
Java_com_xp_xpidaservice_XpidaNative_dumpChunk(JNIEnv *env, jclass clazz,
                                                 jint pid, jlong start, jlong end) {
    if (end <= start) return NULL;
    uint64_t range = (uint64_t)(end - start);
    if (range > DUMP_CHUNK) range = DUMP_CHUNK;

    int buflen = (int)range + 4096;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) return NULL;

    char ctl[256];
    snprintf(ctl, sizeof(ctl), "dump %d %llx %llx",
             pid, (unsigned long long)start, (unsigned long long)(start + range));

    long rc = xpida_call(ctl, buf, buflen);

    LOGI("dumpChunk %llx-%llx: %ld bytes",
         (unsigned long long)start, (unsigned long long)(start + range), rc);

    jbyteArray result = NULL;
    if (rc > 0) {
        result = (*env)->NewByteArray(env, (jsize)rc);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)rc, (jbyte *)buf);
    }

    munmap(buf, buflen);
    return result;
}

/* ---------- rawCommand: unified entry, always returns byte[] ---------- */

JNIEXPORT jbyteArray JNICALL
Java_com_xp_xpidaservice_XpidaNative_rawCommand(JNIEnv *env, jclass clazz,
                                                  jstring ctlStr, jint maxBuf) {
    const char *ctl = (*env)->GetStringUTFChars(env, ctlStr, NULL);
    if (!ctl) return NULL;

    int buflen = maxBuf > 0 ? maxBuf : MAX_TEXT_BUF;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) {
        (*env)->ReleaseStringUTFChars(env, ctlStr, ctl);
        return NULL;
    }
    buf[0] = '\0';

    long rc = xpida_call(ctl, buf, buflen);
    (*env)->ReleaseStringUTFChars(env, ctlStr, ctl);

    jbyteArray result = NULL;
    if (rc > 0) {
        result = (*env)->NewByteArray(env, (jsize)rc);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)rc, (jbyte *)buf);
    } else {
        jsize len = (jsize)strlen(buf);
        if (len > 0) {
            result = (*env)->NewByteArray(env, len);
            if (result) (*env)->SetByteArrayRegion(env, result, 0, len, (jbyte *)buf);
        }
    }

    munmap(buf, buflen);
    return result;
}
