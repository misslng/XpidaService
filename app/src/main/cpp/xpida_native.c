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
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define XPIDA_NR     282
#define XPIDA_MAGIC  0x7870696461ULL

#define MAX_TEXT_BUF  (512 * 1024)
#define MAX_BIN_BUF   (64 * 1024 * 1024 + 4096)
#define DUMP_CHUNK    (64ULL * 1024 * 1024)

/*
 * Core syscall wrapper: syscall(282, magic, ctl_string, output_buf, buf_len)
 * Returns: rc from kernel (bytes written on success, negative on error)
 */
static long xpida_call(const char *ctl, char *buf, int buflen) {
    return syscall(XPIDA_NR, XPIDA_MAGIC, ctl, buf, (long)buflen);
}

/* ---------- ping ---------- */

JNIEXPORT jstring JNICALL
Java_com_xp_xpidaservice_XpidaNative_ping(JNIEnv *env, jclass clazz) {
    int buflen = MAX_TEXT_BUF;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) return (*env)->NewStringUTF(env, "ERROR: mmap failed");
    buf[0] = '\0';

    long rc = xpida_call("ping", buf, buflen);

    jstring result;
    if (rc < 0) {
        char err[128];
        snprintf(err, sizeof(err), "ERROR: rc=%ld", rc);
        result = (*env)->NewStringUTF(env, err);
    } else {
        result = (*env)->NewStringUTF(env, buf);
    }

    munmap(buf, buflen);
    return result;
}

/* ---------- ps ---------- */

JNIEXPORT jstring JNICALL
Java_com_xp_xpidaservice_XpidaNative_ps(JNIEnv *env, jclass clazz) {
    int buflen = MAX_TEXT_BUF;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) return (*env)->NewStringUTF(env, "ERROR: mmap failed");
    buf[0] = '\0';

    long rc = xpida_call("ps", buf, buflen);

    jstring result;
    if (rc < 0) {
        char err[128];
        snprintf(err, sizeof(err), "ERROR: rc=%ld", rc);
        result = (*env)->NewStringUTF(env, err);
    } else {
        result = (*env)->NewStringUTF(env, buf);
    }

    munmap(buf, buflen);
    return result;
}

/* ---------- find <name> ---------- */

JNIEXPORT jstring JNICALL
Java_com_xp_xpidaservice_XpidaNative_find(JNIEnv *env, jclass clazz, jstring name) {
    const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
    if (!cname) return (*env)->NewStringUTF(env, "ERROR: null name");

    int buflen = MAX_TEXT_BUF;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) {
        (*env)->ReleaseStringUTFChars(env, name, cname);
        return (*env)->NewStringUTF(env, "ERROR: mmap failed");
    }
    buf[0] = '\0';

    char ctl[512];
    snprintf(ctl, sizeof(ctl), "find %s", cname);
    (*env)->ReleaseStringUTFChars(env, name, cname);

    long rc = xpida_call(ctl, buf, buflen);

    jstring result;
    if (rc < 0) {
        char err[128];
        snprintf(err, sizeof(err), "ERROR: rc=%ld", rc);
        result = (*env)->NewStringUTF(env, err);
    } else {
        result = (*env)->NewStringUTF(env, buf);
    }

    munmap(buf, buflen);
    return result;
}

/* ---------- maps <pid> ---------- */

JNIEXPORT jstring JNICALL
Java_com_xp_xpidaservice_XpidaNative_maps(JNIEnv *env, jclass clazz, jint pid) {
    int buflen = MAX_TEXT_BUF;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) return (*env)->NewStringUTF(env, "ERROR: mmap failed");
    buf[0] = '\0';

    char ctl[256];
    snprintf(ctl, sizeof(ctl), "maps %d", pid);

    long rc = xpida_call(ctl, buf, buflen);

    jstring result;
    if (rc < 0) {
        char err[128];
        snprintf(err, sizeof(err), "ERROR: rc=%ld", rc);
        result = (*env)->NewStringUTF(env, err);
    } else {
        result = (*env)->NewStringUTF(env, buf);
    }

    munmap(buf, buflen);
    return result;
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
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)rc, (jbyte *)buf);
        }
    }

    munmap(buf, buflen);
    return result;
}

/* ---------- dumpChunk: single syscall, max DUMP_CHUNK bytes ----------
 * Java layer calls this in a loop to stream chunks over TCP.
 * start/end range must be <= DUMP_CHUNK (64MB).
 */

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
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)rc, (jbyte *)buf);
        }
    }

    munmap(buf, buflen);
    return result;
}

/* ---------- generic text command (for extensibility) ---------- */

JNIEXPORT jstring JNICALL
Java_com_xp_xpidaservice_XpidaNative_rawTextCommand(JNIEnv *env, jclass clazz, jstring ctlStr) {
    const char *ctl = (*env)->GetStringUTFChars(env, ctlStr, NULL);
    if (!ctl) return (*env)->NewStringUTF(env, "ERROR: null ctl");

    int buflen = MAX_TEXT_BUF;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) {
        (*env)->ReleaseStringUTFChars(env, ctlStr, ctl);
        return (*env)->NewStringUTF(env, "ERROR: mmap failed");
    }
    buf[0] = '\0';

    long rc = xpida_call(ctl, buf, buflen);
    (*env)->ReleaseStringUTFChars(env, ctlStr, ctl);

    jstring result;
    if (rc < 0) {
        char err[128];
        snprintf(err, sizeof(err), "ERROR: rc=%ld", rc);
        result = (*env)->NewStringUTF(env, err);
    } else {
        result = (*env)->NewStringUTF(env, buf);
    }

    munmap(buf, buflen);
    return result;
}

/* ---------- generic binary command (for extensibility) ---------- */

JNIEXPORT jbyteArray JNICALL
Java_com_xp_xpidaservice_XpidaNative_rawBinaryCommand(JNIEnv *env, jclass clazz, jstring ctlStr) {
    const char *ctl = (*env)->GetStringUTFChars(env, ctlStr, NULL);
    if (!ctl) return NULL;

    int buflen = MAX_BIN_BUF;
    char *buf = (char *)mmap(NULL, buflen, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) {
        (*env)->ReleaseStringUTFChars(env, ctlStr, ctl);
        return NULL;
    }

    long rc = xpida_call(ctl, buf, buflen);
    (*env)->ReleaseStringUTFChars(env, ctlStr, ctl);

    jbyteArray result = NULL;
    if (rc > 0) {
        result = (*env)->NewByteArray(env, (jsize)rc);
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)rc, (jbyte *)buf);
        }
    }

    munmap(buf, buflen);
    return result;
}
