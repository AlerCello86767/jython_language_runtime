package com.AlerCello86767.jython_language_runtime.client;

import java.util.Locale;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyForwarder;

import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * 声音实例宿主（{@code fabric-sound-api-v1}）：把 {@link SoundInstance} 的取值转发给 Python 对象。
 *
 * <p>继承 {@link AbstractSoundInstance} 而不是直接实现接口——音效解析（{@code resolve} / {@code getSound}）
 * 由它按音效 id 从 {@link net.minecraft.client.sounds.SoundManager} 里查，Python 不必也没法自己造。
 * 于是 Python 能控制的正好是「播放参数」这一层：音量、音调、循环、相对坐标、延迟、位置、衰减。
 *
 * <p><b>{@code FabricSoundInstance#getAudioStream} 这里没有转发</b>：它要返回
 * {@code CompletableFuture<AudioStream>}，Python 造不出那种对象，所以保持默认实现
 * （即原版按音效文件流式加载）。也就是说本模组用的是这个接口的 {@link SoundInstance} 那一面，
 * 不是「自定义音频流」那一面。Python 没实现的方法一律回退 {@code super}。
 */
public class PythonSoundInstance extends AbstractSoundInstance implements FabricSoundInstance {
    private final PyObject behavior;

    public PythonSoundInstance(Identifier soundId, SoundSource source, PyObject behavior) {
        super(soundId, source, SoundInstance.createUnseededRandom());
        this.behavior = behavior;
    }

    @Override
    public float getVolume() {
        Float value = forward("getVolume", Float.class);
        return value == null ? super.getVolume() : value;
    }

    @Override
    public float getPitch() {
        Float value = forward("getPitch", Float.class);
        return value == null ? super.getPitch() : value;
    }

    @Override
    public boolean isLooping() {
        Boolean value = forward("isLooping", Boolean.class);
        return value == null ? super.isLooping() : value;
    }

    @Override
    public boolean isRelative() {
        Boolean value = forward("isRelative", Boolean.class);
        return value == null ? super.isRelative() : value;
    }

    @Override
    public int getDelay() {
        Integer value = forward("getDelay", Integer.class);
        return value == null ? super.getDelay() : value;
    }

    @Override
    public double getX() {
        Double value = forward("getX", Double.class);
        return value == null ? super.getX() : value;
    }

    @Override
    public double getY() {
        Double value = forward("getY", Double.class);
        return value == null ? super.getY() : value;
    }

    @Override
    public double getZ() {
        Double value = forward("getZ", Double.class);
        return value == null ? super.getZ() : value;
    }

    @Override
    public boolean canStartSilent() {
        Boolean value = forward("canStartSilent", Boolean.class);
        return value != null && value;
    }

    @Override
    public boolean canPlaySound() {
        Boolean value = forward("canPlaySound", Boolean.class);
        return value == null || value;
    }

    /** 衰减方式，Python 返回 {@code "none"} 或 {@code "linear"}；未实现走原版默认。 */
    @Override
    public Attenuation getAttenuation() {
        String value = forward("getAttenuation", String.class);
        if (value == null) {
            return super.getAttenuation();
        }
        try {
            return Attenuation.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知衰减方式: " + value + "（可用 none / linear）");
        }
    }

    /** behavior 可为 null（纯用默认值播一条音效）；Python 未实现该方法时返回 null 由调用方回退 super。 */
    private <T> T forward(String name, Class<T> type) {
        return behavior == null ? null : PyForwarder.forward(behavior, name, type);
    }
}
