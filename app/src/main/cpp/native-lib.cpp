#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL

Java_com_example_esp32_1ble_1interface_1android_MainActivity_stringFromJNI( JNIEnv* env, jobject /* this */)
{
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}