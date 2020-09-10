package com.frizzle.frizzlehook;


import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class MainActivity extends AppCompatActivity {

    private Button btn;
    private Button btnJump;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestPerms();
    }

    private void requestPerms() {
        //权限,简单处理下
        if (Build.VERSION.SDK_INT> Build.VERSION_CODES.N) {
            String[] perms= {Manifest.permission.WRITE_EXTERNAL_STORAGE};
            if (checkSelfPermission(perms[0]) == PackageManager.PERMISSION_DENIED) {
                requestPermissions(perms,200);
            }else {
                initView();
            }
        } else {
            initView();
        }
    }


    private void initView() {
        btn = findViewById(R.id.btn);
        btnJump = findViewById(R.id.btn_jump);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Toast.makeText(MainActivity.this, ((Button)view).getText(),Toast.LENGTH_SHORT).show();
            }
        });
        //通过hook方式修改Toast内容
        try {
            hook(btn);
        } catch (Exception e) {
            Log.e("Frizzle","hook失败:  "+e.toString());
            e.printStackTrace();
        }

        btnJump.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpTestActiviy();
            }
        });
    }

    /**
     * 跳转TestActiviy,TestActiviy未在AndroidManifest中注册
     * 使用hook技术跳过AndroidManifest注册校验
     * 原理是使用hook将TestActiviy替换成注册过的Activity
     *
     * 过程大致为 : startActivity(TestActivity) ---> Activity --> Instrumentation.execStartActivity ---> ActivityManagerNative.getDefault()
     *  IActivityManager.startActivity --->  (Hook)   AMS.startActivity（检测，当前要启动的Activity是否注册了）
     *
     *  实现思路:
     *
     *  思想切入点：既然会得到IActivityManager，会设置IActivityManager，（寻找替换点(动态代理)）
     *
     *  动态代理：由于执行startActivity之前，我们需要先执行我们的代码(把TestActivity 替换成 已经注册的 Activity)
     *
     *
     * ASM检查过后，要把这个ProxyActivity 换回来 --> TestActivity
     *
     * startActivity --->  TestActivity -- （Hook ProxyActivity）（AMS）检测，当前要启动的Activity是否注册了）ok ---》
     *   ActivityThread（即将加载启动Activity）----(要把这个ProxyActivity 换回来 --> TestActivity)
     *
     * Hook LAUNCH_ACTIVITY
     */
    private void jumpTestActiviy() {
//        startActivity(new Intent(this,TestActivity.class));
        //宿主中启动宿主Activity
//        Intent intent = new Intent();
//        intent.setComponent(new ComponentName("com.frizzle.frizzlehook","com.frizzle.frizzlehook.TestActivity"));
//        startActivity(intent);
        //宿主中启动插件的Activity
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.frizzle.plugin_package","com.frizzle.plugin_package.PluginActivity"));
        startActivity(intent);

    }

    private void hook(View view) throws Exception{
        Class viewClass = Class.forName("android.view.View");
        Method getListenerInfoMethod = viewClass.getDeclaredMethod("getListenerInfo");
        getListenerInfoMethod.setAccessible(true);
        Object mListenerInfo = getListenerInfoMethod.invoke(view);
        Class listenerInfoClass = Class.forName("android.view.View$ListenerInfo");
        //为了拿到mOnClickListener对象需要
        Field mOnClickListenerField = listenerInfoClass.getField("mOnClickListener");
        final Object mOnClickListenerObj = mOnClickListenerField.get(mListenerInfo);
        //第一个参数是ClassLoader 第二个是监听对象 会监听该类下的所有方法 ,第三个参数是监听回调
        Object proxyClickListener = Proxy.newProxyInstance(MainActivity.class.getClassLoader(), new Class[]{View.OnClickListener.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] objects) throws Throwable {
                //加入自己的逻辑(替换)
                //继续执行系统代码逻辑
                Log.e("Frizzle","hook到系统的onClick方法");
                Button button = new Button(MainActivity.this);
                button.setText("Hook成功");
                return method.invoke(mOnClickListenerObj,button);
            }
        });

        //把系统的OnClickListener换成我们自己写的动态代理的内容
        mOnClickListenerField.set(mListenerInfo,proxyClickListener);
    }
}