//
// Created by tim on 08.07.22.
//

#include "Log.h"

#ifdef NO_CRASHLYTICS
void log::toCrashlytics(const char *level, const char* tag, const char *fmt, ...) {
    // Stubbed
}
#else
// TODO clean up this abomination
void log::toCrashlytics(const char *level, const char* tag, const char *fmt, ...) {
    va_list arguments;
    va_start(arguments, fmt);
    va_list sizeArguments;
    va_copy(sizeArguments, arguments);
    int bufsz = vsnprintf(nullptr, 0, fmt, sizeArguments);
    va_end(sizeArguments);
    if (bufsz < 0) {
        va_end(arguments);
        return;
    }
    char* buf = static_cast<char *>(malloc(bufsz + 1));
    if (!buf) {
        va_end(arguments);
        return;
    }
    vsnprintf(buf, bufsz + 1, fmt, arguments);
    va_end(arguments);
    firebase::crashlytics::Log(("["+std::string(level)+"] "+tag+": " + std::string(buf)).c_str());
    free(buf);
}
#endif
