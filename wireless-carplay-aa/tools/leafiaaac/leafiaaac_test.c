#define HOST 1
#include <assert.h>
#include <pthread.h>
#include <unistd.h>
#include "../../native/leafiaaac/leafiaaac.c"

static CStreamInfoHead fake_info = { 44100, 1024, 2 };
static fdk_handle fake_fdk_open(s32 fmt, u32 layers) { (void)fmt; (void)layers; return (void *)0x1234; }
static s32 fake_fdk_cfg(fdk_handle h, u8 *c[], const u32 l[]) { (void)h; (void)c; return l[0] == 2 ? 0 : -1; }
static s32 fake_fdk_fill(fdk_handle h, u8 *b[], const u32 s[], u32 *v) { (void)h; (void)b; *v = 0; return s[0] ? 0 : -1; }
static s32 fake_fdk_dec(fdk_handle h, s16 *pcm, s32 n, u32 f) {
    (void)h; (void)f;
    if (n < 2048) return -1;
    for (int i = 0; i < 2048; i++) pcm[i] = (s16)(i & 0x7fff);
    return 0;
}
static CStreamInfoHead *fake_fdk_info(fdk_handle h) { (void)h; return &fake_info; }
static s32 fake_fdk_param(fdk_handle h, s32 p, s32 v) { (void)h; (void)p; (void)v; return 0; }
static void fake_fdk_close(fdk_handle h) { (void)h; }

static void *fake_opus_dec_create(s32 fs, s32 ch, s32 *err) { (void)fs; (void)ch; *err = 0; return (void *)0x2345; }
static s32 fake_opus_decode(void *st, const u8 *d, s32 len, s16 *pcm, s32 fs, s32 fec) {
    (void)st; (void)d; (void)len; (void)fs; (void)fec;
    for (int i = 0; i < 320; i++) pcm[i] = 42;
    return 320;
}
static void fake_opus_destroy(void *st) { (void)st; }
static void *fake_opus_enc_create(s32 fs, s32 ch, s32 app, s32 *err) { (void)fs; (void)ch; (void)app; *err = 0; return (void *)0x3456; }
static s32 fake_opus_encode(void *st, const s16 *pcm, s32 fs, u8 *data, s32 maxb) {
    (void)st; (void)pcm; (void)fs;
    if (maxb < 8) return -1;
    memset(data, 0x5a, 8);
    return 8;
}
static s32 fake_opus_ctl(void *st, s32 req, ...) { (void)st; (void)req; return 0; }

void *dlopen(const char *name, int flags) {
    (void)flags;
    usleep(5000); /* intentionally widen race window for concurrent load_fdk/load_opus test */
    if (strcmp(name, "libstagefright_soft_aacdec.so") == 0) return (void *)1;
    if (strcmp(name, "libopus.so") == 0) return (void *)2;
    return 0;
}

void *dlsym(void *h, const char *sym) {
    usleep(1000);
    if (h == (void *)1) {
        if (strcmp(sym, "aacDecoder_Open") == 0) return (void *)fake_fdk_open;
        if (strcmp(sym, "aacDecoder_ConfigRaw") == 0) return (void *)fake_fdk_cfg;
        if (strcmp(sym, "aacDecoder_Fill") == 0) return (void *)fake_fdk_fill;
        if (strcmp(sym, "aacDecoder_DecodeFrame") == 0) return (void *)fake_fdk_dec;
        if (strcmp(sym, "aacDecoder_GetStreamInfo") == 0) return (void *)fake_fdk_info;
        if (strcmp(sym, "aacDecoder_SetParam") == 0) return (void *)fake_fdk_param;
        if (strcmp(sym, "aacDecoder_Close") == 0) return (void *)fake_fdk_close;
    }
    if (h == (void *)2) {
        if (strcmp(sym, "opus_decoder_create") == 0) return (void *)fake_opus_dec_create;
        if (strcmp(sym, "opus_decode") == 0) return (void *)fake_opus_decode;
        if (strcmp(sym, "opus_decoder_destroy") == 0) return (void *)fake_opus_destroy;
        if (strcmp(sym, "opus_decoder_ctl") == 0) return (void *)fake_opus_ctl;
        if (strcmp(sym, "opus_encoder_create") == 0) return (void *)fake_opus_enc_create;
        if (strcmp(sym, "opus_encode") == 0) return (void *)fake_opus_encode;
        if (strcmp(sym, "opus_encoder_destroy") == 0) return (void *)fake_opus_destroy;
        if (strcmp(sym, "opus_encoder_ctl") == 0) return (void *)fake_opus_ctl;
    }
    return 0;
}

static void *worker(void *arg) {
    int idx = *(int *)arg;
    void *conv = 0;
    if (idx & 1) {
        ASBD in = { 44100, FMT_AAC, 0, 0, 1024, 0, 2, 0, 0 };
        ASBD out = { 44100, FMT_LPCM, 0, 0, 1, 0, 2, 0, 0 }; /* bitsPerChannel == 0 */
        assert(LeafiaAudioConverterNew(&in, &out, &conv) == 0);
        assert(conv != 0);
        Conv *c = (Conv *)conv;
        assert(c->out.bitsPerChannel == 16);
        assert(c->out.bytesPerFrame == 4);
        assert(LeafiaAudioConverterDispose(conv) == 0);
    } else {
        ASBD in = { 16000, FMT_LPCM, 0, 0, 1, 0, 1, 0, 0 }; /* bitsPerChannel == 0 */
        ASBD out = { 16000, FMT_OPUS, 0, 0, 320, 0, 1, 0, 0 };
        assert(LeafiaAudioConverterNew(&in, &out, &conv) == 0);
        assert(conv != 0);
        Conv *c = (Conv *)conv;
        assert(c->in.bitsPerChannel == 16);
        assert(c->in.bytesPerFrame == 2);
        assert(LeafiaAudioConverterDispose(conv) == 0);
    }
    return 0;
}

int main(void) {
    pthread_t th[8];
    int ids[8];
    for (int i = 0; i < 8; i++) {
        ids[i] = i;
        assert(pthread_create(&th[i], 0, worker, &ids[i]) == 0);
    }
    for (int i = 0; i < 8; i++) {
        assert(pthread_join(th[i], 0) == 0);
    }

    /* Standard 16-bit ASBD still works */
    void *conv = 0;
    ASBD in16 = { 16000, FMT_OPUS, 0, 0, 320, 0, 1, 0, 0 };
    ASBD out16 = { 16000, FMT_LPCM, 0, 2, 1, 2, 1, 16, 0 };
    assert(LeafiaAudioConverterNew(&in16, &out16, &conv) == 0);
    assert(LeafiaAudioConverterDispose(conv) == 0);

    /* Non-16-bit LPCM (e.g., 32-bit or 4 bytesPerFrame mono) is still rejected */
    ASBD out32 = { 16000, FMT_LPCM, 0, 4, 1, 4, 1, 32, 0 };
    assert(LeafiaAudioConverterNew(&in16, &out32, &conv) == kUnsupportedErr);
    ASBD outBadBpf = { 16000, FMT_LPCM, 0, 4, 1, 4, 1, 0, 0 };
    assert(LeafiaAudioConverterNew(&in16, &outBadBpf, &conv) == kUnsupportedErr);

    puts("ok    leafiaaac_test (concurrent load + LPCM bitsPerChannel==0)");
    return 0;
}
