package com.movtery.zalithlauncher.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.movtery.zalithlauncher.context.ContextExecutor;
import com.movtery.zalithlauncher.context.LocaleHelper;
import com.movtery.zalithlauncher.event.single.LauncherIgnoreNotchEvent;
import com.movtery.zalithlauncher.feature.accounts.AccountsManager;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager;
import com.movtery.zalithlauncher.plugins.PluginLoader;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils;

import net.kdt.pojavlaunch.utils.FlintAccentUtils;

import net.kdt.pojavlaunch.MissingStorageActivity;
import net.kdt.pojavlaunch.Tools;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.Companion.setLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(FragmentManager fragmentManager, Fragment fragment,
                                                      View view, Bundle savedInstanceState) {
                        FlintAccentUtils.applyAccentColor(view, FlintAccentUtils.getAccentColor(view.getContext()));
                    }
                }, true);
        LocaleHelper.Companion.setLocale(this);
        Tools.setFullscreen(this);
        Tools.updateWindowSize(this);

        checkStoragePermissions();
        //加载渲染器
        Renderers.INSTANCE.init(false);
        //加载插件
        PluginLoader.loadAllPlugins(this, false);
        //刷新游戏路径
        ProfilePathManager.INSTANCE.refreshPath();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        if (!Tools.checkStorageRoot()) {
            startActivity(new Intent(this, MissingStorageActivity.class));
            finish();
        }

        checkStoragePermissions();

        AccountsManager.INSTANCE.reload();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        Tools.setFullscreen(this);
        Tools.ignoreNotch(shouldIgnoreNotch(),this);
        FlintAccentUtils.applyAccentColor(
                getWindow().getDecorView(), FlintAccentUtils.getAccentColor(this));
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Tools.getDisplayMetrics(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe
    public void event(LauncherIgnoreNotchEvent event) {
        Tools.ignoreNotch(shouldIgnoreNotch(),this);
    }

    /** @return Whether or not the notch should be ignored */
    public boolean shouldIgnoreNotch() {
        return AllSettings.getIgnoreNotchLauncher().getValue();
    }

    private void checkStoragePermissions() {
        //检查所有文件管理权限
        StoragePermissionsUtils.checkPermissions(this);
    }
}
