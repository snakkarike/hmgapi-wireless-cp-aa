/*
 * libcarplaylog.so - CarPlay's own log switch (troubleshooting only).
 *
 * The CarPlay stack (libCarPlay*.so) logs through Apple's LogUtils in the head unit's
 * libCarPlayCoreUtils.so, which writes to logcat (tag "TccCarPlay") with
 * __android_log_print. On some builds its categories sit at a level that logs nothing,
 * so a session that ends shows no reason. LogControl("<category regex>:level=<level>")
 * raises them; LogUtils keeps the action and applies it to categories created later.
 *
 * One JNI call, hmgapi.mods.wireless.dipo.CarPlayLog.control(String): finds LogControl
 * in the already loaded stock library (dlopen by name) and passes the command on.
 * Returns LogControl's OSStatus (0 = done), or -1 no command, -2 no library,
 * -3 no LogControl, -4 the string couldn't be read.
 *
 * No libc or NDK: only dlopen and dlsym, from the unit's libdl.so. JNIEnv is used through
 * its function table (GetStringUTFChars = 169, ReleaseStringUTFChars = 170, as in every
 * JNI 1.2+ table).
 */
extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);

typedef int (*LogControlFn)(const char *);
typedef const char *(*GetStringUTFCharsFn)(void *env, void *str, unsigned char *isCopy);
typedef void (*ReleaseStringUTFCharsFn)(void *env, void *str, const char *chars);

__attribute__((visibility("default")))
int Java_hmgapi_mods_wireless_dipo_CarPlayLog_control(void *env, void *cls, void *command)
{
    void **table = *(void ***)env;
    void *lib;
    LogControlFn control;
    const char *s;
    int r;
    (void)cls;
    if (!command) return -1;
    lib = dlopen("libCarPlayCoreUtils.so", 0);
    if (!lib) return -2;
    control = (LogControlFn)dlsym(lib, "LogControl");
    if (!control) return -3;
    s = ((GetStringUTFCharsFn)table[169])(env, command, 0);
    if (!s) return -4;
    r = control(s);
    ((ReleaseStringUTFCharsFn)table[170])(env, command, s);
    return r;
}
