package com.AlerCello86767.jython_language_runtime.client.datagen;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;

/**
 * 语言文件 provider 桥接：把 Python 传入的 {@code {翻译键: 译文}} 写成
 * {@code assets/<namespace>/lang/<locale>.json}。
 *
 * <p>译文为纯字符串，Python 侧可以直接写中文（不经过 Component，不涉及 Jython 字节串问题）；
 * 但按项目约定仍建议只放 ASCII，中文留给人工维护的 zh_cn。
 */
public final class PyLangProvider extends FabricLanguageProvider {
    private final Map<String, Object> translations;

    public PyLangProvider(FabricPackOutput output,
                          String locale,
                          CompletableFuture<HolderLookup.Provider> registries,
                          Map<String, Object> translations) {
        super(output, locale, registries);
        this.translations = translations;
    }

    @Override
    public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder builder) {
        for (Map.Entry<String, Object> entry : translations.entrySet()) {
            builder.add(entry.getKey(), String.valueOf(entry.getValue()));
        }
    }
}
