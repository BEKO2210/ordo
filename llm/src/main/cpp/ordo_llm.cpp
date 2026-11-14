/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Thin JNI bridge to llama.cpp: load a GGUF model, keep the state after a fixed prompt prefix,
 * and generate one grammar-constrained answer per call. All calls for one handle come from one thread,
 * except cancel(), which only flips an atomic flag.
 */

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <climits>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "OrdoLlm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<uint8_t> snapshot;
    std::atomic<bool> cancelled{false};
};

// Text crosses JNI as real UTF-8 bytes: JNI's own string functions use modified UTF-8, which breaks
// on emoji and NUL characters.
std::string toString(JNIEnv *env, jbyteArray value) {
    jsize length = env->GetArrayLength(value);
    std::string result(length, '\0');
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte *>(result.data()));
    return result;
}

jbyteArray toBytes(JNIEnv *env, const std::string &text) {
    jbyteArray array = env->NewByteArray((jsize) text.size());
    if (array != nullptr) {
        env->SetByteArrayRegion(array, 0, (jsize) text.size(), reinterpret_cast<const jbyte *>(text.data()));
    }
    return array;
}

std::vector<llama_token> tokenize(const Engine &engine, const std::string &text) {
    int probe = llama_tokenize(engine.vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, true);
    if (probe >= 0 || probe == INT32_MIN) {
        return {};
    }
    int n = -probe;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(engine.vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, false,
                       true) < 0) {
        tokens.clear();
    }
    return tokens;
}

// Feeds tokens in batch-sized chunks; positions continue from what the context already holds.
bool decodeAll(Engine &engine, std::vector<llama_token> &tokens) {
    const size_t batch = llama_n_batch(engine.ctx);
    for (size_t i = 0; i < tokens.size(); i += batch) {
        if (engine.cancelled) {
            return false;
        }
        int32_t n = (int32_t) std::min(batch, tokens.size() - i);
        if (llama_decode(engine.ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            LOGE("llama_decode failed at token %zu", i);
            return false;
        }
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_me_zhanghai_android_files_llm_LlamaEngine_nativeLoad(JNIEnv *env, jclass, jbyteArray path,
                                                         jint threads, jint contextSize) {
    llama_backend_init();
    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(toString(env, path).c_str(), modelParams);
    if (model == nullptr) {
        LOGE("model load failed");
        return 0;
    }
    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = contextSize;
    contextParams.n_batch = 512;
    contextParams.n_ubatch = 512;
    contextParams.n_threads = threads;
    contextParams.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, contextParams);
    if (ctx == nullptr) {
        LOGE("context init failed");
        llama_model_free(model);
        return 0;
    }
    auto *engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_me_zhanghai_android_files_llm_LlamaEngine_nativePrepare(JNIEnv *env, jclass, jlong handle,
                                                            jbyteArray prefix) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    engine->cancelled = false;
    llama_memory_clear(llama_get_memory(engine->ctx), true);
    std::vector<llama_token> tokens = tokenize(*engine, toString(env, prefix));
    if (tokens.empty() || !decodeAll(*engine, tokens)) {
        return JNI_FALSE;
    }
    engine->snapshot.resize(llama_state_seq_get_size(engine->ctx, 0));
    size_t written = llama_state_seq_get_data(engine->ctx, engine->snapshot.data(),
                                              engine->snapshot.size(), 0);
    engine->snapshot.resize(written);
    return written > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_me_zhanghai_android_files_llm_LlamaEngine_nativeGenerate(JNIEnv *env, jclass, jlong handle,
                                                             jbyteArray suffix, jbyteArray grammar,
                                                             jint maxTokens) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    engine->cancelled = false;
    if (engine->snapshot.empty()) {
        return nullptr;
    }
    llama_memory_clear(llama_get_memory(engine->ctx), true);
    if (llama_state_seq_set_data(engine->ctx, engine->snapshot.data(), engine->snapshot.size(), 0)
        == 0) {
        LOGE("state restore failed");
        return nullptr;
    }
    std::vector<llama_token> tokens = tokenize(*engine, toString(env, suffix));
    if (tokens.empty() || !decodeAll(*engine, tokens)) {
        return nullptr;
    }
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler *grammarSampler = llama_sampler_init_grammar(
            engine->vocab, toString(env, grammar).c_str(), "root");
    if (grammarSampler == nullptr) {
        LOGE("grammar parse failed");
        llama_sampler_free(sampler);
        return nullptr;
    }
    llama_sampler_chain_add(sampler, grammarSampler);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    std::string output;
    bool ok = true;
    for (int i = 0; i < maxTokens; ++i) {
        if (engine->cancelled) {
            ok = false;
            break;
        }
        llama_token token = llama_sampler_sample(sampler, engine->ctx, -1);
        if (llama_vocab_is_eog(engine->vocab, token)) {
            break;
        }
        std::vector<char> piece(64);
        int n = llama_token_to_piece(engine->vocab, token, piece.data(), (int32_t) piece.size(), 0, false);
        if (n < 0) {
            // Too small: the negative value is the size it needs.
            piece.resize(-n);
            n = llama_token_to_piece(engine->vocab, token, piece.data(), (int32_t) piece.size(), 0, false);
        }
        if (n > 0) {
            output.append(piece.data(), n);
        }
        if (llama_decode(engine->ctx, llama_batch_get_one(&token, 1)) != 0) {
            ok = false;
            break;
        }
    }
    llama_sampler_free(sampler);
    return ok ? toBytes(env, output) : nullptr;
}

JNIEXPORT void JNICALL
Java_me_zhanghai_android_files_llm_LlamaEngine_nativeCancel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Engine *>(handle)->cancelled = true;
}

JNIEXPORT void JNICALL
Java_me_zhanghai_android_files_llm_LlamaEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    llama_free(engine->ctx);
    llama_model_free(engine->model);
    delete engine;
}

}  // extern "C"
