#include <android/log.h>

#define TAG "JamesDspWrapper_JNI"
#include <Log.h>

#include <string>
#include <cmath>
#include <cstdlib>
#include <jni.h>
#include <mutex>
#include <new>

#include "JamesDspWrapper.h"
#include "JArrayList.h"
#include "EelVmVariable.h"

extern "C" {
#include "../EELStdOutExtension.h"
#include <jdsp_header.h>
}

static JavaVM* javaVm = nullptr;
static std::mutex globalMemoryMutex;
static size_t globalMemoryUsers = 0;
static std::mutex stdoutHandlerMutex;
static JamesDspWrapper* stdoutHandlerOwner = nullptr;

static void retainGlobalMemory()
{
    std::lock_guard<std::mutex> lock(globalMemoryMutex);
    if(globalMemoryUsers++ == 0)
        JamesDSPGlobalMemoryAllocation();
}

static void releaseGlobalMemory()
{
    std::lock_guard<std::mutex> lock(globalMemoryMutex);
    if(globalMemoryUsers == 0)
    {
        LOGE("JamesDspWrapper: global memory reference count underflow")
        return;
    }
    if(--globalMemoryUsers == 0)
        JamesDSPGlobalMemoryDeallocation();
}

// C interop
inline JamesDSPLib* cast(void* raw){
    if(raw == nullptr)
    {
        LOGE("JamesDspWrapper::cast: JamesDSPLib pointer is NULL")
    }
    return static_cast<JamesDSPLib*>(raw);
}

inline JamesDspWrapper* castWrapper(jlong raw){
    if(raw == 0)
    {
        LOGE("JamesDspWrapper::castWrapper: JamesDspWrapper pointer is NULL")
    }
    return reinterpret_cast<JamesDspWrapper*>(raw);
}

#define RETURN_IF_NULL(name, retval) \
    if(name == nullptr)      \
        return retval;

#define DECLARE_WRAPPER(retval) \
     if(self == 0L) \
        return retval; \
     auto* wrapper = castWrapper(self); \
     RETURN_IF_NULL(wrapper, retval)

#define DECLARE_DSP(retval) \
    DECLARE_WRAPPER(retval) \
    auto* dsp = cast(wrapper->dsp); \
    RETURN_IF_NULL(dsp, retval)

#define DECLARE_WRAPPER_V DECLARE_WRAPPER()
#define DECLARE_DSP_V DECLARE_DSP()
#define DECLARE_WRAPPER_B DECLARE_WRAPPER(false)
#define DECLARE_DSP_B DECLARE_DSP(false)

inline bool normalizeProcessRange(JNIEnv* env, jarray input, jarray output, jint& offset, jint& size)
{
    if(input == nullptr || output == nullptr)
        return false;

    const jsize inputLength = env->GetArrayLength(input);
    const jsize outputLength = env->GetArrayLength(output);
    if(offset < 0)
        offset = 0;
    if(size < 0)
        size = inputLength - offset;
    if(offset > inputLength || size <= 0 || size > inputLength - offset ||
       size > outputLength || (size & 1) != 0)
    {
        LOGE("JamesDspWrapper::process: invalid range offset=%d size=%d input=%d output=%d",
             offset, size, inputLength, outputLength)
        return false;
    }
    return true;
}

inline int32_t arySearch(int32_t *array, int32_t N, int32_t x)
{
    for (int32_t i = 0; i < N; i++)
    {
        if (array[i] == x)
            return i;
    }
    return -1;
}

#define FLOIDX 20000
/*inline void* GetStringForIndex(eel_string_context_state *st, float val, int32_t write)
{
    auto castedValue = (int32_t)(val + 0.5f);
    if (castedValue < FLOIDX)
        return nullptr;
    int32_t idx = arySearch(st->map, st->slot, castedValue);
    if (idx < 0)
        return nullptr;
    if (!write)
    {
        s_str *tmp = &st->m_literal_strings[idx];
        const char *s = s_str_c_str(tmp);
        return (void*)s;
    }
    else
        return (void*)&st->m_literal_strings[idx];
}*/

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_alloc(JNIEnv *env, jobject obj, jobject callback)
{
    auto* self = new(std::nothrow) JamesDspWrapper{};
    if(self == nullptr)
        return 0;

    self->callbackInterface = env->NewGlobalRef(callback);
    auto cleanup = [&]() {
        if(self->callbackInterface != nullptr)
            env->DeleteGlobalRef(self->callbackInterface);
        delete self;
    };
    if(self->callbackInterface == nullptr)
    {
        cleanup();
        return 0;
    }

    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr)
    {
        LOGE("JamesDspWrapper::ctor: Cannot find callback class");
        cleanup();
        return 0;
    }
    else
    {
        self->callbackOnLiveprogOutput = env->GetMethodID(callbackClass, "onLiveprogOutput",
                                                      "(Ljava/lang/String;)V");
        self->callbackOnLiveprogExec = env->GetMethodID(callbackClass, "onLiveprogExec",
                                                    "(Ljava/lang/String;)V");
        self->callbackOnLiveprogResult = env->GetMethodID(callbackClass, "onLiveprogResult",
                                                          "(ILjava/lang/String;Ljava/lang/String;)V");
        self->callbackOnVdcParseError = env->GetMethodID(callbackClass, "onVdcParseError",
                                                          "()V");
        if (self->callbackOnLiveprogOutput == nullptr || self->callbackOnLiveprogExec == nullptr ||
            self->callbackOnLiveprogResult == nullptr || self->callbackOnVdcParseError == nullptr)
        {
            LOGE("JamesDspWrapper::ctor: Cannot find callback method");
            env->DeleteLocalRef(callbackClass);
            cleanup();
            return 0;
        }
    }
    env->DeleteLocalRef(callbackClass);

    auto* _dsp = (JamesDSPLib*)calloc(1, sizeof(JamesDSPLib));
    if(!_dsp)
    {
        LOGE("JamesDspWrapper::ctor: Failed to allocate memory for libjamesdsp class object");
        cleanup();
        return 0;
    }

    retainGlobalMemory();
    JamesDSPInit(_dsp, 128, 48000);

    if(!JamesDSPGetMutexStatus(_dsp))
    {
        LOGE("JamesDspWrapper::ctor: JamesDSPGetMutexStatus returned false. "
                    "Cannot run safely in multi-threaded environment.");
        JamesDSPFree(_dsp);
        free(_dsp);
        releaseGlobalMemory();
        cleanup();
        return 0;
    }

    self->dsp = _dsp;

    LOGD("JamesDspWrapper::ctor: memory allocated at %lx", (long)self);
    return (long)self;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_free(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP_V

    LOGD("JamesDspWrapper::dtor: freeing memory allocated at %lx", (long)self);

    {
        std::lock_guard<std::mutex> lock(stdoutHandlerMutex);
        if(stdoutHandlerOwner == wrapper)
        {
            setStdOutHandler(nullptr, nullptr);
            stdoutHandlerOwner = nullptr;
        }
    }

    JamesDSPFree(dsp);
    free(dsp);
    wrapper->dsp = nullptr;

    releaseGlobalMemory();

    env->DeleteGlobalRef(wrapper->callbackInterface);
    delete wrapper;

    LOGD("JamesDspWrapper::dtor: memory freed");
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_getBenchmarkSize(JNIEnv *env, jobject obj) {
    return MAX_BENCHMARK;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_runBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::runBenchmark: started");

    if(jc0 == nullptr || jc1 == nullptr || env->GetArrayLength(jc0) != MAX_BENCHMARK ||
       env->GetArrayLength(jc1) != MAX_BENCHMARK)
    {
        LOGE("JamesDspWrapper::runBenchmark: invalid output arrays")
        return;
    }

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    if(c0 == nullptr)
        return;
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);
    if(c1 == nullptr)
    {
        env->ReleaseDoubleArrayElements(jc0, c0, JNI_ABORT);
        return;
    }

    JamesDSP_Start_benchmark();
    JamesDSP_Save_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, 0);
    env->ReleaseDoubleArrayElements(jc1, c1, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_loadBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::loadBenchmark: loading data");

    if(jc0 == nullptr || jc1 == nullptr || env->GetArrayLength(jc0) != MAX_BENCHMARK ||
       env->GetArrayLength(jc1) != MAX_BENCHMARK)
    {
        LOGE("JamesDspWrapper::loadBenchmark: invalid input arrays")
        return;
    }

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    if(c0 == nullptr)
        return;
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);
    if(c1 == nullptr)
    {
        env->ReleaseDoubleArrayElements(jc0, c0, JNI_ABORT);
        return;
    }

    JamesDSP_Load_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jc1, c1, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setSamplingRate(JNIEnv *env,
                                                                                 jobject obj,
                                                                                 jlong self,
                                                                                 jfloat sample_rate,
                                                                                 jboolean force_refresh)
{
    DECLARE_DSP_V
    if(!std::isfinite(sample_rate) || sample_rate <= 0.0f)
    {
        LOGE("JamesDspWrapper::setSamplingRate: invalid sample rate %f", sample_rate)
        return;
    }
    JamesDSPSetSampleRate(dsp, sample_rate, force_refresh);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setBlockSize(JNIEnv *env,
                                                                              jobject obj,
                                                                              jlong self,
                                                                              jint frames)
{
    DECLARE_DSP_B
    if(frames <= 0)
        return false;
    JamesDSPSetBlockSize(dsp, static_cast<size_t>(frames));
    return true;
}


extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_isHandleValid(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP_B // This macro returns false if the DSP object can't be accessed
    return true;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt16(JNIEnv *env, jobject obj, jlong self, jshortArray inputObj, jshortArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V
    if(!normalizeProcessRange(env, inputObj, outputObj, offset, size))
        return;

    auto input = env->GetShortArrayElements(inputObj, nullptr);
    if(input == nullptr)
        return;
    auto output = env->GetShortArrayElements(outputObj, nullptr);
    if(output == nullptr)
    {
        env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
        return;
    }
    dsp->processInt16Multiplexd(dsp, input + offset, output, size / 2);
    env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseShortArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt32(JNIEnv *env, jobject obj, jlong self, jintArray inputObj, jintArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V
    if(!normalizeProcessRange(env, inputObj, outputObj, offset, size))
        return;

    auto input = env->GetIntArrayElements(inputObj, nullptr);
    if(input == nullptr)
        return;
    auto output = env->GetIntArrayElements(outputObj, nullptr);
    if(output == nullptr)
    {
        env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
        return;
    }
    dsp->processInt32Multiplexd(dsp, input + offset, output, size / 2);
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT jbooleanArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt24Packed(JNIEnv *env, jobject obj, jlong self, jbooleanArray inputObj)
{
    /* We need to use jbooleanArray (= unsigned 8-bit) instead of jbyteArray (= signed 8-bit) here! */

    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    if(inputObj == nullptr)
        return nullptr;

    auto inputLength = env->GetArrayLength(inputObj);
    if(inputLength == 0 || inputLength % 6 != 0)
    {
        LOGE("JamesDspWrapper::processInt24Packed: input must contain complete stereo frames")
        return inputObj;
    }
    auto outputObj = env->NewBooleanArray(inputLength);
    if(outputObj == nullptr)
        return inputObj;

    auto input = env->GetBooleanArrayElements(inputObj, nullptr);
    if(input == nullptr)
        return inputObj;
    auto output = env->GetBooleanArrayElements(outputObj, nullptr);
    if(output == nullptr)
    {
        env->ReleaseBooleanArrayElements(inputObj, input, JNI_ABORT);
        return inputObj;
    }
    dsp->processInt24PackedMultiplexd(dsp, input, output, inputLength / 6);
    env->ReleaseBooleanArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseBooleanArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt8U24(JNIEnv *env, jobject obj, jlong self, jintArray inputObj)
{
    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    if(inputObj == nullptr)
        return nullptr;

    auto inputLength = env->GetArrayLength(inputObj);
    if(inputLength == 0 || (inputLength & 1) != 0)
    {
        LOGE("JamesDspWrapper::processInt8U24: input must contain complete stereo frames")
        return inputObj;
    }
    auto outputObj = env->NewIntArray(inputLength);
    if(outputObj == nullptr)
        return inputObj;

    auto input = env->GetIntArrayElements(inputObj, nullptr);
    if(input == nullptr)
        return inputObj;
    auto output = env->GetIntArrayElements(outputObj, nullptr);
    if(output == nullptr)
    {
        env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
        return inputObj;
    }
    dsp->processInt8_24Multiplexd(dsp, input, output, inputLength / 2);
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processFloat(JNIEnv *env, jobject obj, jlong self, jfloatArray inputObj, jfloatArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V
    if(!normalizeProcessRange(env, inputObj, outputObj, offset, size))
        return;

    auto input = env->GetFloatArrayElements(inputObj, nullptr);
    if(input == nullptr)
        return;
    auto output = env->GetFloatArrayElements(outputObj, nullptr);
    if(output == nullptr)
    {
        env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
        return;
    }

    dsp->processFloatMultiplexd(dsp, input + offset, output, size / 2);

    env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputObj, output, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLimiter(JNIEnv *env, jobject obj, jlong self, jfloat threshold, jfloat release)
{
    DECLARE_DSP_B
    if(!std::isfinite(threshold) || !std::isfinite(release))
        return false;
    JLimiterSetCoefficients(dsp, threshold, release);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLimiterEnabled(JNIEnv *env, jobject obj, jlong self, jboolean enabled)
{
    DECLARE_DSP_B
    JLimiterSetEnabled(dsp, enabled);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setPostGain(JNIEnv *env, jobject obj, jlong self, jfloat gain)
{
    DECLARE_DSP_B
    if(!std::isfinite(gain))
        return false;
    JamesDSPSetPostGain(dsp, gain);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setMultiEqualizer(JNIEnv *env, jobject obj, jlong self,
                                                                                   jboolean enable, jint filterType, jint interpolationMode,
                                                                                   jdoubleArray bands)
{
    DECLARE_DSP_B

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setMultiEqualizer: EQ band pointer is NULL. Disabling EQ");
        MultimodalEqualizerDisable(dsp);
        return !enable;
    }

    if(env->GetArrayLength(bands) != 30)
    {
        LOGE("JamesDspWrapper::setMultiEqualizer: Invalid EQ data. 30 semicolon-separated fields expected, "
                      "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(enable)
    {
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        if(nativeBands == nullptr)
            return false;
        MultimodalEqualizerAxisInterpolation(dsp, interpolationMode, filterType, nativeBands, nativeBands + 15);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        MultimodalEqualizerEnable(dsp, 1);
    }
    else
    {
        MultimodalEqualizerDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVdc(JNIEnv *env, jobject obj, jlong self,
                                                                       jboolean enable, jstring vdcContents)
{
    DECLARE_DSP_B
    if(enable)
    {
        if(vdcContents == nullptr)
            return false;
        const char *nativeString = env->GetStringUTFChars(vdcContents, nullptr);
        if(nativeString == nullptr)
            return false;
        DDCStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(vdcContents, nativeString);

        int ret = DDCEnable(dsp, 1);
        if (ret <= 0)
        {
            LOGE("JamesDspWrapper::setVdc: Call to DDCEnable(wrapper->dsp) failed. Invalid DDC parameter?");
            LOGE("JamesDspWrapper::setVdc: Disabling DDC engine");
            env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnVdcParseError);

            DDCDisable(dsp);
            return false;
        }
    }
    else
    {
        DDCDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCompander(JNIEnv *env, jobject obj, jlong self,
                                                                              jboolean enable, jfloat timeConstant, jint granularity, jint tfresolution, jdoubleArray bands)
{
    DECLARE_DSP_B

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setCompander: Compander band pointer is NULL. Disabling compander");
        CompressorDisable(dsp);
        return !enable;
    }

    if(env->GetArrayLength(bands) != 14)
    {
        LOGE("JamesDspWrapper::setCompander: Invalid compander data. 14 semicolon-separated fields expected, "
             "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(enable)
    {
        CompressorSetParam(dsp, timeConstant, granularity, tfresolution, 0);
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        if(nativeBands == nullptr)
            return false;
        CompressorSetGain(dsp, nativeBands, nativeBands + 7, 1);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        CompressorEnable(dsp, 1);
    }
    else
    {
        CompressorDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setReverb(JNIEnv *env, jobject obj, jlong self,
                                                                          jboolean enable, jint preset)
{
    DECLARE_DSP_B
    if(enable)
    {
        Reverb_SetParam(dsp, preset);
        ReverbEnable(dsp);
    }
    else
    {
        ReverbDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setConvolver(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloatArray impulseResponse,
                                                                             jint irChannels, jint irFrames)
{
    DECLARE_DSP_B

    if(!enable)
    {
        Convolver1DDisable(dsp);
        return true;
    }

    if(impulseResponse == nullptr || irChannels <= 0 || irFrames <= 0)
    {
        LOGW("JamesDspWrapper::setConvolver: Invalid impulse response metadata");
        return false;
    }

    const jsize impulseLength = env->GetArrayLength(impulseResponse);
    const jlong expectedLength = static_cast<jlong>(irChannels) * irFrames;
    if(expectedLength != impulseLength)
    {
        LOGW("JamesDspWrapper::setConvolver: Invalid impulse response length: expected=%lld actual=%d",
             static_cast<long long>(expectedLength), impulseLength);
        return false;
    }

    LOGD("JamesDspWrapper::setConvolver: Impulse response loaded: channels=%d, frames=%d", irChannels, irFrames);
    auto* nativeImpulse = env->GetFloatArrayElements(impulseResponse, nullptr);
    if(nativeImpulse == nullptr)
        return false;

    Convolver1DDisable(dsp);
    const int success = Convolver1DLoadImpulseResponse(dsp, nativeImpulse, irChannels, irFrames, 1);
    env->ReleaseFloatArrayElements(impulseResponse, nativeImpulse, JNI_ABORT);

    if(success > 0)
        Convolver1DEnable(dsp);
    else
    {
        LOGD("JamesDspWrapper::setConvolver: Failed to update convolver. Convolver1DLoadImpulseResponse returned an error.");
        return false;
    }

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setGraphicEq(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jstring graphicEq)
{
    DECLARE_DSP_B
    if(graphicEq == nullptr || env->GetStringUTFLength(graphicEq) <= 0)
    {
        LOGE("JamesDspWrapper::setGraphicEq: graphicEq is empty or NULL. Disabling graphic eq.");
        enable = false;
    }

    if(enable)
    {
        const char *nativeString = env->GetStringUTFChars(graphicEq, nullptr);
        if(nativeString == nullptr)
            return false;
        ArbitraryResponseEqualizerStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(graphicEq, nativeString);

        ArbitraryResponseEqualizerEnable(dsp, 1);
    }
    else
        ArbitraryResponseEqualizerDisable(dsp);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCrossfeed(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jint mode, jint customFcut, jint customFeed)
{
    DECLARE_DSP_B
    if(mode == 99)
    {
        memset(&dsp->advXF.bs2b, 0, sizeof(dsp->advXF.bs2b));
        BS2BInit(&dsp->advXF.bs2b[1], (unsigned int)dsp->fs, ((unsigned int)customFcut | ((unsigned int)customFeed << 16)));
        dsp->advXF.mode = 1;
    }
    else
    {
       CrossfeedChangeMode(dsp, mode);
    }

    if(enable)
        CrossfeedEnable(dsp, 1);
    else
        CrossfeedDisable(dsp);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setBassBoost(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloat maxGain)
{
    DECLARE_DSP_B
    if(enable)
    {
        BassBoostSetParam(dsp, maxGain);
        BassBoostEnable(dsp);
    }
    else
    {
        BassBoostDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setStereoEnhancement(JNIEnv *env, jobject obj, jlong self,
                                                                                     jboolean enable, jfloat level)
{
    DECLARE_DSP_B
    StereoEnhancementDisable(dsp);
    StereoEnhancementSetParam(dsp, level / 100.0f);
    if(enable)
    {
        StereoEnhancementEnable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTube(JNIEnv *env, jobject obj, jlong self,
                                                                              jboolean enable, jfloat level)
{
    DECLARE_DSP_B
    if(!std::isfinite(level))
        return false;
    if(enable)
    {
        VacuumTubeSetGain(dsp, level / 100.0f);
        VacuumTubeEnable(dsp);
    }
    else
    {
        VacuumTubeDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTubeHarmonicGain(JNIEnv *env, jobject obj,
                                                                                           jlong self, jfloat amount)
{
    DECLARE_DSP_B
    if(!std::isfinite(amount))
        return false;
    VacuumTubeSetHarmonicGain(dsp, amount);
    return true;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_getLimiterGainReduction(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP(0.0f)
    if(!dsp->limiter.enabled || dsp->limiter.envOverThreshold <= dsp->limiter.threshold)
        return 0.0f;
    return 20.0f * std::log10(dsp->limiter.envOverThreshold / dsp->limiter.threshold);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLiveprog(JNIEnv *env, jobject obj, jlong self,
                                                                            jboolean enable, jstring id, jstring liveprogContent)
{
    DECLARE_DSP_B

    if(id == nullptr || liveprogContent == nullptr)
        return false;

    // Attach log listener
    {
        std::lock_guard<std::mutex> lock(stdoutHandlerMutex);
        setStdOutHandler(receiveLiveprogStdOut, wrapper);
        stdoutHandlerOwner = wrapper;
    }

    const char *nativeString = env->GetStringUTFChars(liveprogContent, nullptr);
    if(nativeString == nullptr)
        return false;
    if(strlen(nativeString) < 1) {
        LOGD("JamesDspWrapper::setLiveprog: empty file")
        env->ReleaseStringUTFChars(liveprogContent, nativeString);
        LiveProgDisable(dsp);
        return true;
    }

    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogExec, id);

    char errorBuffer[512] = { 0 };
    int ret = LiveProgStringParser(dsp, (char*)nativeString, errorBuffer, sizeof(errorBuffer)); // Ignore constness, libjamesdsp does not modify it
    env->ReleaseStringUTFChars(liveprogContent, nativeString);

    const char* errorString = errorBuffer[0] == '\0' ? nullptr : errorBuffer;
    if(errorString != nullptr)
    {
        LOGW("JamesDspWrapper::setLiveprog: NSEEL_code_getcodeerror: Syntax error in script file, cannot load. Reason: %s", errorString);
    }
    if(ret <= 0)
    {
        LOGW("JamesDspWrapper::setLiveprog: %s", checkErrorCode(ret));
    }

    jstring errorStringJni = errorString == nullptr ? nullptr : env->NewStringUTF(errorString);
    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogResult, ret, id, errorStringJni);
    if(errorStringJni != nullptr)
        env->DeleteLocalRef(errorStringJni);

    if(ret > 0)
    {
        if(enable)
            LiveProgEnable(dsp);
        else
            LiveProgDisable(dsp);
    }
    else if(!enable)
        LiveProgDisable(dsp);
    return ret > 0;
}


extern "C" JNIEXPORT jobject JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_enumerateEelVariables(JNIEnv *env, jobject obj, jlong self)
{
    auto array = JArrayList(env);

    // Return empty array if DECLARE failed
    DECLARE_DSP(array.getJavaReference())

    if(dsp->eel.vm == nullptr)
        return array.getJavaReference();

    auto *ctx = (compileContext*)dsp->eel.vm;
    for (int i = 0; i < ctx->varTable_numBlocks; i++)
    {
        for (int j = 0; j < NSEEL_VARS_PER_BLOCK; j++)
        {
            // TODO fix string handling (broke after last libjamesdsp update)
            const char *valid = nullptr;//(char*)GetStringForIndex(ctx->region_context, ctx->varTable_Values[i][j], 1);
            bool isString = valid;

            if (ctx->varTable_Names[i][j])
            {
                const char* name = ctx->varTable_Names[i][j];
                const std::string value = isString
                    ? valid
                    : std::to_string(ctx->varTable_Values[i][j]);

                auto var = EelVmVariable(env, name, value.c_str(), isString);
                array.add(var.getJavaReference());
            }
        }
    }

    return array.getJavaReference();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_manipulateEelVariable(JNIEnv *env, jobject obj, jlong self,
                                                                                      jstring name, jfloat value)
{
    DECLARE_DSP_B
    if(dsp->eel.vm == nullptr || name == nullptr)
        return false;

    const char *nativeName = env->GetStringUTFChars(name, nullptr);
    if(nativeName == nullptr)
        return false;
    const bool updated = LiveProgSetVariable(dsp, nativeName, value) != 0;
    if(!updated)
        LOGE("JamesDspWrapper::manipulateEelVariable: invalid or unknown variable '%s'", nativeName);
    env->ReleaseStringUTFChars(name, nativeName);
    return updated;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_freezeLiveprogExecution(JNIEnv *env, jobject obj, jlong self,
                                                                                        jboolean freeze)
{
    DECLARE_DSP_V
    jdsp_lock(dsp);
    dsp->eel.active = !freeze;
    jdsp_unlock(dsp);
    LOGD("JamesDspWrapper::freezeLiveprogExecution: Liveprog execution has been %s", (freeze ? "frozen" : "resumed"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_eelErrorCodeToString(JNIEnv *env,
                                                                                     jobject obj,
                                                                                     jint error_code)
{
    return env->NewStringUTF(checkErrorCode(error_code));
}

void receiveLiveprogStdOut(const char *buffer, void* userData)
{
    auto* self = static_cast<JamesDspWrapper*>(userData);
    if(self == nullptr || javaVm == nullptr || buffer == nullptr)
    {
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Self reference is NULL");
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Unhandled output: %s", buffer);
        return;
    }

    JNIEnv* env = nullptr;
    bool detachThread = false;
    jint status = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if(status == JNI_EDETACHED)
    {
        if(javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK)
            return;
        detachThread = true;
    }
    else if(status != JNI_OK)
    {
        return;
    }

    jobject callback = nullptr;
    jmethodID callbackMethod = nullptr;
    {
        std::lock_guard<std::mutex> lock(stdoutHandlerMutex);
        if(stdoutHandlerOwner == self)
        {
            callback = env->NewLocalRef(self->callbackInterface);
            callbackMethod = self->callbackOnLiveprogOutput;
        }
    }

    jstring message = callback == nullptr ? nullptr : env->NewStringUTF(buffer);
    if(message != nullptr && callbackMethod != nullptr)
    {
        env->CallVoidMethod(callback, callbackMethod, message);
        env->DeleteLocalRef(message);
    }
    if(callback != nullptr)
        env->DeleteLocalRef(callback);
    if(detachThread)
        javaVm->DetachCurrentThread();
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *)
{
    javaVm = vm;
#ifndef NO_CRASHLYTICS
    firebase::crashlytics::Initialize();
#endif
    LOGD("JNI_OnLoad called")
    return JNI_VERSION_1_6;
}
