package com.frizzle.frizzlehook;

import dalvik.system.DexClassLoader;

/**
 * author: LWJ
 * date: 2020/9/10$
 * description
 * 用来加载插件的ClassLoader
 */
public class PluginClassLoader extends DexClassLoader {

    public PluginClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(dexPath, optimizedDirectory, librarySearchPath, parent);
    }
}
