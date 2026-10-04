package com.AlerCello86767.jython_language_runtime.client.datagen;

import net.minecraft.resources.Identifier;

/**
 * datagen 回调里的 id 解析工具。
 *
 * <p>与运行期门面不同，datagen 回调发生在 {@code onInitializeDataGenerator} 之外，没有
 * 「当前命名空间」上下文；但每个 provider 都由某个模组的数据包生成器创建，namespace 是已知的
 * （{@link net.fabricmc.fabric.api.datagen.v1.FabricPackOutput#getModId()}）。因此这里直接用它
 * 做省略命名空间时的回退：{@code "ruby" -> "py_test:ruby"}，写全限定则原样解析。
 */
final class DatagenIds {
    private DatagenIds() {
    }

    /** 解析 id；省略命名空间时用 {@code fallbackNamespace}。 */
    static Identifier of(String raw, String fallbackNamespace) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Empty id");
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return Identifier.fromNamespaceAndPath(fallbackNamespace, raw);
        }
        return Identifier.fromNamespaceAndPath(raw.substring(0, colon), raw.substring(colon + 1));
    }
}
