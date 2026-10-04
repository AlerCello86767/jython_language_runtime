package com.AlerCello86767.jython_language_runtime.client;

import java.util.Locale;

import org.python.core.PyObject;
import org.python.core.PyType;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/**
 * 声音门面（{@code fabric-sound-api-v1}）：让 Python 播一条自己控制的音效。
 *
 * <pre>
 * class RubyHum(object):
 *     def getVolume(self): return 0.6
 *     def isLooping(self): return True
 *     def getPitch(self): return 1.0
 *     def getAttenuation(self): return "linear"
 *
 * handle = PySounds.play("minecraft:block.beacon.ambient", "master", RubyHum)
 * PySounds.isPlaying(handle)
 * PySounds.stop(handle)
 * </pre>
 *
 * <p>{@code behavior} 传**类**（宿主会实例化一份并长期持有），也可直接传实例，或传 {@code None}
 * 只按默认参数播一次。行为类实现 {@code getVolume / getPitch / isLooping / isRelative / getDelay /
 * getX / getY / getZ / getAttenuation / canStartSilent / canPlaySound}，未实现的走原版默认值——
 * 详见 {@link PythonSoundInstance}。
 *
 * <p>音效 id 是**音效事件**（{@code sounds.json} 里的键），不是音频文件路径。
 * 只能在客户端调用（渲染线程）。
 */
public final class PySounds {
    private PySounds() {
    }

    /** 播一条音效，返回句柄（就是那个 SoundInstance）；句柄交给 {@link #stop} / {@link #isPlaying} 用。 */
    public static Object play(String soundId, String source, PyObject behavior) {
        PythonSoundInstance instance = new PythonSoundInstance(
                ModIds.parse(soundId), source(source), instantiate(behavior));
        Minecraft.getInstance().getSoundManager().play(instance);
        return instance;
    }

    /** 停掉某条正在播的音效。 */
    public static void stop(PyObject handleArg) {
        SoundInstance instance = toInstance(handleArg);
        if (instance != null) {
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }

    /** 该音效是否还在播（已经播完的短音效会变 False）。 */
    public static boolean isPlaying(PyObject handleArg) {
        SoundInstance instance = toInstance(handleArg);
        return instance != null && Minecraft.getInstance().getSoundManager().isActive(instance);
    }

    private static SoundInstance toInstance(PyObject handleArg) {
        return (SoundInstance) handleArg.__tojava__(SoundInstance.class);
    }

    /** Python 类交给宿主时是类对象，得先实例化——项目里菜单 / 屏幕宿主同款处理。 */
    private static PyObject instantiate(PyObject behavior) {
        if (behavior == null) {
            return null;
        }
        return behavior instanceof PyType ? behavior.__call__() : behavior;
    }

    private static SoundSource source(String name) {
        try {
            return SoundSource.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知音源分类: " + name
                    + "（可用 master/music/records/weather/blocks/hostile/neutral/players/ambient/voice/ui）");
        }
    }
}
