package com.frizzle.plugin_package;

import android.app.Activity;
import android.content.res.AssetManager;
import android.content.res.Resources;

/**
 * author: LWJ
 * date: 2020/9/10$
 * description
 */
public class BaseActivity extends Activity {
    @Override
    public Resources getResources() {
        if (getApplication() != null && getApplication().getResources()!=null){
            return getApplication().getResources();
        }
        return super.getResources();
    }

    @Override
    public AssetManager getAssets() {
        if (getApplication() != null && getApplication().getAssets()!=null){
            return getApplication().getAssets();
        }
        return super.getAssets();
    }
}
