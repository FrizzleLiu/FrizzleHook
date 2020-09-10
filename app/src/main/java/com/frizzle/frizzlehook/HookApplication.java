package com.frizzle.frizzlehook;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

/**
 * author: LWJ
 * date: 2020/9/9$
 * description
 */
public class HookApplication extends Application {

    public static final int LAUNCH_ACTIVITY = 100;
    // 增加权限的管理
    private static List<String> activityList = new ArrayList<>();

    static {
        activityList.add(TestActivity.class.getName()); // 有权限
        activityList.add("com.frizzle.plugin_package.PluginActivity");
    }

    private AssetManager assetManager;
    private Resources resources;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            hookAmsAction();
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("Frizzle", "hookAmsAction hook失败:  " + e.toString());
        }

        try {
            hookLaunchActivity();
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("Frizzle", "hookLaunchActivity hook失败:  " + e.toString());
        }


//        //融合dexElement
//        try {
//            pluginToAppAction();
//        } catch (Exception e) {
//            e.printStackTrace();
//            Log.e("Frizzle", "插件和宿主Element融合失败:  " + e.toString());
//        }


        //loadedApk式插件化
        try {
            customLoadedApkAction();
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("Frizzle", "hookLaunchActivity hook失败:  " + e.toString());
        }

    }

    /**
     * 1.把TestActivity 替换我们真实有效的Activity
     * <p>
     * startActivity(TestActivity) ---> Activity --> Instrumentation.execStartActivity ---> ActivityManagerNative.getDefault()
     * IActivityManager.startActivity --->  (Hook)   AMS.startActivity（检测，当前要启动的Activity是否注册了）
     * <p>
     * 思想切入点：既然会得到IActivityManager，会设置IActivityManager，（寻找替换点(动态代理)）
     * <p>
     * 动态代理：由于执行startActivity之前，我们需要先执行我们的代码(把TestActivity 替换成 已经注册的 Activity)
     */
    private void hookAmsAction() throws Exception {
        Class mIActivityManagerClass = Class.forName("android.app.IActivityManager");
        Class mActivityManagerNativeClass2 = Class.forName("android.app.ActivityManagerNative");
        //IActivityManager 为了让动态代理的invoke方法继续执行,需要拿到IActivityManager
        final Object mIActivityManager = mActivityManagerNativeClass2.getMethod("getDefault").invoke(null);//静态方法
        //本质是IActivityManager
        Object mIActivityManagerProxy = Proxy.newProxyInstance(HookApplication.class.getClassLoader(), new Class[]{mIActivityManagerClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                //动态代理需要处理的是startActivity方法
                if ("startActivity".equalsIgnoreCase(method.getName())) {
                    //用AndroidManifest注册过的ProxyActivity替换未注册的TestActivity
                    //并将原Intent携带到代理Activity
                    Intent intent = new Intent(HookApplication.this, ProxyActivity.class);
                    intent.putExtra("intentAction", (Intent) args[2]);
                    args[2] = intent;
                }
                Log.e("Frizzle", "hook到mIActivityManager的方法");
                return method.invoke(mIActivityManager, args);
            }
        });
        // 我们要拿到IActivityManager对象，才能让动态代理里面的 invoke 正常执行下
        // 执行此方法 static public IActivityManager getDefault()，就能拿到 IActivityManager
        Class mActivityManagerNativeClass = Class.forName("android.app.ActivityManagerNative");
        Field gDefaultField = mActivityManagerNativeClass.getDeclaredField("gDefault");
        gDefaultField.setAccessible(true);
        Object gDefaultObj = gDefaultField.get(null);//静态变量,不用传所属对象

        Class mSingletonClass = Class.forName("android.util.Singleton");
        Field mInstanceField = mSingletonClass.getDeclaredField("mInstance");
        mInstanceField.setAccessible(true);
        //替换
        mInstanceField.set(gDefaultObj, mIActivityManagerProxy);//替换是需要gDefault ,所以上面代码需要拿到gDefault
    }

    /**
     * 2.ASM检查过后，要把这个ProxyActivity 换回来 --> TestActivity
     * <p>
     * startActivity --->  TestActivity -- （Hook ProxyActivity）（AMS）检测，当前要启动的Activity是否注册了）ok ---》
     * ActivityThread（即将加载启动Activity）----(要把这个ProxyActivity 换回来 --> TestActivity)
     * <p>
     * Hook LAUNCH_ACTIVITY
     * <p>
     * 我们要在Handler.handleMessage 之前执行，就是为了(要把这个ProxyActivity 换回来 --> TestActivity)，所有需要Hook
     * <p>
     * <p>
     * 1.hook ams检查  把TestActivity 换成 ProxyActivity
     * 2.hook 即将要加载Activity又把ProxyActivity 换回来了 TestActivity
     */
    private void hookLaunchActivity() throws Exception {
        Field mCallbackField = Handler.class.getDeclaredField("mCallback");
        mCallbackField.setAccessible(true);
        //需要拿到ActivityThread 的 H extends Handler
        //执行此方法 public static ActivityThread currentActivityThread()
        //通过ActivityThread 找到 H

        Class mActivityThreadClass = Class.forName("android.app.ActivityThread");

        Object mActivityThread = mActivityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null);
        Field mHField = mActivityThreadClass.getDeclaredField("mH");
        mHField.setAccessible(true);
        Handler mH = (Handler) mHField.get(mActivityThread);
        mCallbackField.set(mH, new MyCallback(mH));
    }

    class MyCallback implements Handler.Callback {
        private Handler mH;

        public MyCallback(Handler mH) {
            this.mH = mH;
        }

        @Override
        public boolean handleMessage(@NonNull Message msg) {
            //obj的实质是ActivityClientRecord
            if (msg.what == LAUNCH_ACTIVITY) {
                Object obj = msg.obj;
                try {
                    // 我们要获取之前Hook携带过来的 TestActivity
                    Field intentField = obj.getClass().getDeclaredField("intent");
                    intentField.setAccessible(true);
                    // 获取 intent 对象，拿到第一次动态代理携带过来的 actionIntent
                    Intent intent = (Intent) intentField.get(obj);
                    Intent intentAction = intent.getParcelableExtra("intentAction");
                    if (intentAction != null) {
//                        if (activityList.contains(intentAction.getComponent().getClassName())) {
//                            intentField.set(obj, intentAction); // 把ProxyActivity 换成  TestActivity
//                        } else { // 没有权限
//                            intentField.set(obj, new Intent(HookApplication.this, PermissionActivity.class));
//                        }

                        intentField.set(obj, intentAction);

                        //对插件和宿主进行区分
                        Field activityInfoField = obj.getClass().getDeclaredField("activityInfo");
                        activityInfoField.setAccessible(true);
                        ActivityInfo activityInfo = (ActivityInfo) activityInfoField.get(obj);
                        if (intentAction.getPackage() == null) {//插件
                            activityInfo.applicationInfo.packageName = intentAction.getComponent().getPackageName();
                            hookGetPackageInfo();
                        } else {//宿主
                            activityInfo.applicationInfo.packageName = intentAction.getPackage();
                        }
                    }
                    //获取的intent其实就是obj(ActivityClientRecord)的成员变量intent

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            mH.handleMessage(msg);
            return true;//返回false 系统的代码会继续执行 返回true需要使用系统的  mH.handleMessage(msg);
        }
    }

    /**
     * 将插件中的dexElement与宿主的dexElement融为一体
     */
    private void pluginToAppAction() throws Exception {
        //第一步 获取宿主的dexElements对象
        PathClassLoader pathClassLoader = (PathClassLoader) this.getClassLoader();//源码得知: 本质就是PathClassLoader
        Class mBaseDexClassLoaderClass = Class.forName("dalvik.system.BaseDexClassLoader");
        Field pathListField = mBaseDexClassLoaderClass.getDeclaredField("pathList");
        pathListField.setAccessible(true);
        Object mDexPathList = pathListField.get(pathClassLoader);
        Field dexElementsField = mDexPathList.getClass().getDeclaredField("dexElements");
        dexElementsField.setAccessible(true);
        //拿到宿主dexElements 本质是Element数组
        Object dexElements = dexElementsField.get(mDexPathList);
        //第二步 获取插件的dexElements对象
        //获取插件的ClassLoader应该是 DexClassLoader
        File file = new File(Environment.getExternalStorageDirectory() + File.separator + "hook.apk");
        if (!file.exists()) {
            throw new FileNotFoundException("插件包不存在");
        }
        String pluginPath = file.getAbsolutePath();
        File fileDir = this.getDir("pluginDir", Context.MODE_PRIVATE); // data/data/包名/pluginDir/
        DexClassLoader dexClassLoader = new DexClassLoader(pluginPath, fileDir.getAbsolutePath(), null, getClassLoader());
        Class pathClassLoaderClassPlugin = Class.forName("dalvik.system.BaseDexClassLoader");
        Field pathListFieldPlugin = pathClassLoaderClassPlugin.getDeclaredField("pathList");
        pathListFieldPlugin.setAccessible(true);
        Object mDexPathListPlugin = pathListFieldPlugin.get(dexClassLoader);
        Field dexElementsFieldPlugin = mDexPathListPlugin.getClass().getDeclaredField("dexElements");
        dexElementsFieldPlugin.setAccessible(true);
        //拿到插件dexElements 本质是Element数组
        Object pluginDexElements = dexElementsFieldPlugin.get(mDexPathListPlugin);
        int mainDexLength = Array.getLength(dexElements);
        int pluginDexLength = Array.getLength(pluginDexElements);
        int sumDexLength = mainDexLength + pluginDexLength;
        //第三步 创建新的dexElements[]
        Object newDexElements = Array.newInstance(dexElements.getClass().getComponentType(), sumDexLength);
        //第四步 宿主dexElements + 插件dexElements =----> 融合  新的 newDexElements
        for (int i = 0; i < sumDexLength; i++) {
            // 先融合宿主
            if (i < mainDexLength) {
                // 参数一：新要融合的容器 -- newDexElements
                Array.set(newDexElements, i, Array.get(dexElements, i));
            } else { // 再融合插件的
                Array.set(newDexElements, i, Array.get(pluginDexElements, i - mainDexLength));
            }
        }

        //第五步 把新的DexElements设置到宿主中去
        dexElementsField.set(mDexPathList, newDexElements);
        //加载插件中的布局

        loadPluginLayout();
    }

    private void loadPluginLayout() throws Exception {
        assetManager = AssetManager.class.newInstance();
        File file = new File(Environment.getExternalStorageDirectory() + File.separator + "hook.apk");
        if (!file.exists()) {
            throw new FileNotFoundException("插件包不存在");
        }
        Method method = assetManager.getClass().getDeclaredMethod("addAssetPath", String.class);
        method.setAccessible(true);
        method.invoke(assetManager, file.getAbsolutePath());
        Resources appResources = getResources();

        //通过反射调用final StringBlock[] ensureStringBlocks() 实例化StringBlock
        //实例化StringBlock的时候会解析加载string.xml anim.xml color.xml等
        Method ensureStringBlocksMethod = assetManager.getClass().getDeclaredMethod("ensureStringBlocks");
        ensureStringBlocksMethod.setAccessible(true);
        ensureStringBlocksMethod.invoke(assetManager);
        //加载插件资源的Resources
        resources = new Resources(assetManager, appResources.getDisplayMetrics(), appResources.getConfiguration());
    }

    @Override
    public Resources getResources() {
        return resources == null ? super.getResources() : resources;
    }

    @Override
    public AssetManager getAssets() {
        return assetManager == null ? super.getAssets() : assetManager;
    }


    /**
     * 自己创造一个LoadedApk.ClassLoader(LoadedApk以及LoadedApk以及.ClassLoader) 添加到 mPackages，此LoadedApk 专门用来加载插件里面的 class
     */
    private void customLoadedApkAction() throws Exception {
        File file = new File(Environment.getExternalStorageDirectory() + File.separator + "hook.apk");
        if (!file.exists()) {
            throw new FileNotFoundException("插件包不存在");
        }
        String pluginPath = file.getAbsolutePath();
        Class mActivityThreadClass = Class.forName("android.app.ActivityThread");
        // 执行此方法 public static ActivityThread currentActivityThread() 拿到 ActivityThread对象
        Object mActivityThread = mActivityThreadClass.getMethod("currentActivityThread").invoke(null);
        Field mPackagesField = mActivityThreadClass.getDeclaredField("mPackages");
        mPackagesField.setAccessible(true);
        Object mPackagesObj = mPackagesField.get(mActivityThread);
        Map mPackages = (Map) mPackagesObj;

        // 如何自定义一个 LoadedApk，系统通过getPackageInfoNoCheck创建的LoadedApk
        // 执行此 public final LoadedApk getPackageInfoNoCheck(ApplicationInfo ai, CompatibilityInfo compatInfo)
        Class mCompatibilityInfoClass = Class.forName("android.content.res.CompatibilityInfo");
        Field defaultField = mCompatibilityInfoClass.getDeclaredField("DEFAULT_COMPATIBILITY_INFO");
        defaultField.setAccessible(true);
        //本质是CompatibilityInfo
        Object defaultObj = defaultField.get(null);
        Method mLoadedApkMethod = mActivityThreadClass.getMethod("getPackageInfoNoCheck", ApplicationInfo.class, mCompatibilityInfoClass);
        ApplicationInfo applicationInfo = getApplicationInfoAction();
        //本质是LoadedApk
        Object mLoadedApk = mLoadedApkMethod.invoke(mActivityThread, applicationInfo, defaultObj);

        //参数String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent
        File fileDir = getDir("pulginPathDir", Context.MODE_PRIVATE);
        PluginClassLoader pluginClassLoader = new PluginClassLoader(pluginPath, fileDir.getAbsolutePath(), null, getClassLoader());
        Field mClassLoaderField = mLoadedApk.getClass().getDeclaredField("mClassLoader");
        mClassLoaderField.setAccessible(true);
        //将自定义的PluginClassLoader赋值给自定义的LoadedApk的ClassLoader
        mClassLoaderField.set(mLoadedApk, pluginClassLoader);
        WeakReference<Object> weakReference = new WeakReference<>(mLoadedApk);
        mPackages.put(null, weakReference);
    }

    /**
     * @return
     * @throws Exception 获取 ApplicationInfo 为插件服务的
     */
    private ApplicationInfo getApplicationInfoAction() throws Exception {
        // 执行此public static ApplicationInfo generateApplicationInfo方法，拿到ApplicationInfo
        Class mPackageParserClass = Class.forName("android.content.pm.PackageParser");
        Object mPackageParser = mPackageParserClass.newInstance();
        Class mPackageClass = Class.forName("android.content.pm.PackageParser$Package");
        Class mPackageUserStateClass = Class.forName("android.content.pm.PackageUserState");
        Method mApplicationInfoMethod = mPackageParserClass.getMethod("generateApplicationInfo", mPackageClass, int.class, mPackageUserStateClass);
        File file = new File(Environment.getExternalStorageDirectory() + File.separator + "hook.apk");
        String pulginPath = file.getAbsolutePath();

        // 执行此public Package parsePackage(File packageFile, int flags)方法，拿到 Package
        // 获得执行方法的对象
        Method mPackageMethod = mPackageParserClass.getMethod("parsePackage", File.class, int.class);
        Object mPackage = mPackageMethod.invoke(mPackageParser, file, PackageManager.GET_ACTIVITIES);

        // 参数 Package p, int flags, PackageUserState state
        ApplicationInfo applicationInfo = (ApplicationInfo)
                mApplicationInfoMethod.invoke(mPackageParser, mPackage, 0, mPackageUserStateClass.newInstance());

        // 获得的 ApplicationInfo 就是插件的 ApplicationInfo
        // 我们这里获取的 ApplicationInfo
        // applicationInfo.publicSourceDir = 插件的路径；
        // applicationInfo.sourceDir = 插件的路径；
        applicationInfo.publicSourceDir = pulginPath;
        applicationInfo.sourceDir = pulginPath;

        return applicationInfo;
    }

    /**
     *hook绕过PMS包名安装校验
     */
    private void hookGetPackageInfo() {
        try {
            Class mActivityThreadClass = Class.forName("android.app.ActivityThread");
            Field sCurrentActivityThreadField = mActivityThreadClass.getDeclaredField("sCurrentActivityThread");
            sCurrentActivityThreadField.setAccessible(true);
            Class mIPackageManagerClass = Class.forName("android.content.pm.IPackageManager");
            Field sPackageManagerField = mActivityThreadClass.getDeclaredField("sPackageManager");
            sPackageManagerField.setAccessible(true);
            final Object packageManager = sPackageManagerField.get(null);
            Object mIPackageManagerProxy = Proxy.newProxyInstance(getClassLoader(), new Class[]{mIPackageManagerClass}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    if ("getPackageInfo".equals(method.getName())) {
                        // 如何才能绕过 PMS, 欺骗系统
                        // pi != null
                        return new PackageInfo(); // 成功绕过 PMS检测
                    }
                    // 让系统正常继续执行下去
                    return method.invoke(packageManager, args);
                }
            });

            sPackageManagerField.set(null,mIPackageManagerProxy);
        }catch (Exception e){
            Log.e("Frizzle","hook绕过PMS校验失败...");
            e.printStackTrace();
        }
    }
}
