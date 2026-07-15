#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Bridge/Dns", __VA_ARGS__)

extern JavaVM *g_jvm;
static jclass g_dnsResolverClass = NULL;
static jmethodID g_onResolutionCompleteMethod = NULL;

extern void StartResolveBootstrap(int64_t id, const char* host, const char* protocol, const char* resolvedUpstream, const char* originalUpstream, int bypass);

void setupDnsJni(JNIEnv* env) {
    jclass clazz = (*env)->FindClass(env, "com/zaneschepke/tunnel/backend/dns/NativeDnsResolver");
    if (clazz == NULL) {
        LOGE("Failed to find NativeDnsResolver class");
        return;
    }
    g_dnsResolverClass = (*env)->NewGlobalRef(env, clazz);
    (*env)->DeleteLocalRef(env, clazz);

    g_onResolutionCompleteMethod = (*env)->GetStaticMethodID(
            env, g_dnsResolverClass, "onResolutionComplete", "(JLjava/lang/String;)V"
    );
}

void teardownDnsJni(JNIEnv* env) {
    if (g_dnsResolverClass != NULL) {
        (*env)->DeleteGlobalRef(env, g_dnsResolverClass);
        g_dnsResolverClass = NULL;
    }
    g_onResolutionCompleteMethod = NULL;
}

// Called by Go to push the result back to Kotlin
void NotifyDnsResult(int64_t id, const char* result) {
    if (g_jvm == NULL || g_dnsResolverClass == NULL || g_onResolutionCompleteMethod == NULL) return;

    JNIEnv *env = NULL;
    jint rs = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);

    if (rs == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (JNIEnv **)&env, NULL) != JNI_OK) return;
    } else if (rs != JNI_OK) {
        return;
    }

    jstring jresult = (*env)->NewStringUTF(env, result);
    (*env)->CallStaticVoidMethod(env, g_dnsResolverClass, g_onResolutionCompleteMethod, (jlong)id, jresult);

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, jresult);
}

JNIEXPORT void JNICALL
Java_com_zaneschepke_tunnel_backend_dns_NativeDnsResolver_startBootstrapResolution(
        JNIEnv* env,
        jclass clazz,
        jlong id,
        jstring host,
        jstring protocol,
        jstring resolvedUpstream,
        jstring originalUpstream,
        jint bypass)
{

    const char* chost = host ? (*env)->GetStringUTFChars(env, host, NULL) : "";
    const char* cprotocol = protocol ? (*env)->GetStringUTFChars(env, protocol, NULL) : "";
    const char* cresolvedUpstream = resolvedUpstream ? (*env)->GetStringUTFChars(env, resolvedUpstream, NULL) : "";
    const char* coriginalUpstream = originalUpstream ? (*env)->GetStringUTFChars(env, originalUpstream, NULL) : "";

    // Function will synchronously copy before returning
    StartResolveBootstrap(
            (int64_t)id,
            chost,
            cprotocol,
            cresolvedUpstream,
            coriginalUpstream,
            bypass ? 1 : 0
    );

    // Release everything after copied
    if (host) (*env)->ReleaseStringUTFChars(env, host, chost);
    if (protocol) (*env)->ReleaseStringUTFChars(env, protocol, cprotocol);
    if (resolvedUpstream) (*env)->ReleaseStringUTFChars(env, resolvedUpstream, cresolvedUpstream);
    if (originalUpstream) (*env)->ReleaseStringUTFChars(env, originalUpstream, coriginalUpstream);
}