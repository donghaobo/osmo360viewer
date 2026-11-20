package com.osmo.viewer;

import android.app.Application;

/** 全局入口：进程一起来就装好闪退捕获，连 Activity 启动前的崩溃也能记下来。 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashCatcher.install(this);
    }
}
