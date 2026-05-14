package net.kdt.pojavlaunch.fragments;

import static com.movtery.zalithlauncher.event.single.RefreshVersionsEvent.MODE.END;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.movtery.anim.AnimPlayer;
import com.movtery.anim.animations.Animations;
import com.movtery.zalithlauncher.InfoCenter;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.databinding.FragmentLauncherBinding;
import com.movtery.zalithlauncher.event.single.AccountUpdateEvent;
import com.movtery.zalithlauncher.event.single.LaunchGameEvent;
import com.movtery.zalithlauncher.event.single.RefreshVersionsEvent;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.feature.version.utils.VersionIconUtils;
import com.movtery.zalithlauncher.feature.version.VersionInfo;
import com.movtery.zalithlauncher.feature.version.VersionsManager;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.task.TaskExecutors;
import com.movtery.zalithlauncher.ui.fragment.AboutFragment;
import com.movtery.zalithlauncher.ui.fragment.ControlButtonFragment;
import com.movtery.zalithlauncher.ui.fragment.FilesFragment;
import com.movtery.zalithlauncher.ui.fragment.FragmentWithAnim;
import com.movtery.zalithlauncher.ui.fragment.VersionManagerFragment;
import com.movtery.zalithlauncher.ui.fragment.VersionsListFragment;
import com.movtery.zalithlauncher.ui.subassembly.account.AccountViewWrapper;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.anim.ViewAnimUtils;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class MainMenuFragment extends FragmentWithAnim {
    public static final String TAG = "MainMenuFragment";
    private FragmentLauncherBinding binding;
    private AccountViewWrapper accountViewWrapper;

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherBinding.inflate(getLayoutInflater());
        accountViewWrapper = new AccountViewWrapper(this, binding.viewAccount);
        accountViewWrapper.refreshAccountInfo();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // ── About ──
        binding.aboutText.setText(InfoCenter.replaceName(requireActivity(), R.string.about_tab));
        binding.aboutButton.setOnClickListener(v ->
                ZHTools.swapFragmentWithAnim(this, AboutFragment.class, AboutFragment.TAG, null));

        // ── Controls ──
        binding.customControlButton.setOnClickListener(v ->
                ZHTools.swapFragmentWithAnim(this, ControlButtonFragment.class, ControlButtonFragment.TAG, null));

        // ── Files ──
        binding.openMainDirButton.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(FilesFragment.BUNDLE_LIST_PATH, PathManager.DIR_GAME_HOME);
            ZHTools.swapFragmentWithAnim(this, FilesFragment.class, FilesFragment.TAG, bundle);
        });

        // ── Install JAR ──
        binding.installJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        binding.installJarButton.setOnLongClickListener(v -> {
            runInstallerWithConfirmation(true);
            return true;
        });

        // ── Share Logs ──
        binding.shareLogsButton.setOnClickListener(v -> ZHTools.shareLogs(requireActivity()));

        // BUG 4 FIX: Left sidebar — Versions button
        if (binding.navItemVersions != null) {
            binding.navItemVersions.setOnClickListener(v -> {
                if (!checkTaskRunning()) {
                    ZHTools.swapFragmentWithAnim(this, VersionsListFragment.class, VersionsListFragment.TAG, null);
                } else {
                    ViewAnimUtils.setViewAnim(binding.navItemVersions, Animations.Shake);
                    showTaskRunningToast();
                }
            });
        }

        // BUG 4 FIX: Left sidebar — Download button
        if (binding.navItemDownload != null) {
            binding.navItemDownload.setOnClickListener(v ->
                    ZHTools.swapFragmentWithAnim(this, VersionsListFragment.class, VersionsListFragment.TAG, null));
        }

        // ── Version card (center) ──
        binding.version.setOnClickListener(v -> {
            if (!checkTaskRunning()) {
                ZHTools.swapFragmentWithAnim(this, VersionsListFragment.class, VersionsListFragment.TAG, null);
            } else {
                ViewAnimUtils.setViewAnim(binding.version, Animations.Shake);
                showTaskRunningToast();
            }
        });

        binding.managerProfileButton.setOnClickListener(v -> {
            if (!checkTaskRunning()) {
                ViewAnimUtils.setViewAnim(binding.managerProfileButton, Animations.Pulse);
                ZHTools.swapFragmentWithAnim(this, VersionManagerFragment.class, VersionManagerFragment.TAG, null);
            } else {
                ViewAnimUtils.setViewAnim(binding.managerProfileButton, Animations.Shake);
                showTaskRunningToast();
            }
        });

        // BUG 3 FIX: Play button — background set to flint_btn_play (blue) in XML
        // EventBus fires the launch
        binding.playButton.setOnClickListener(v -> EventBus.getDefault().post(new LaunchGameEvent()));

        // BUG 6 FIX: Quick action buttons
        if (binding.quickVersionsButton != null) {
            binding.quickVersionsButton.setOnClickListener(v -> {
                if (!checkTaskRunning()) {
                    ZHTools.swapFragmentWithAnim(this, VersionsListFragment.class, VersionsListFragment.TAG, null);
                } else {
                    showTaskRunningToast();
                }
            });
        }

        if (binding.quickControlsButton != null) {
            binding.quickControlsButton.setOnClickListener(v ->
                    ZHTools.swapFragmentWithAnim(this, ControlButtonFragment.class, ControlButtonFragment.TAG, null));
        }

        if (binding.quickFilesButton != null) {
            binding.quickFilesButton.setOnClickListener(v -> {
                Bundle bundle = new Bundle();
                bundle.putString(FilesFragment.BUNDLE_LIST_PATH, PathManager.DIR_GAME_HOME);
                ZHTools.swapFragmentWithAnim(this, FilesFragment.class, FilesFragment.TAG, bundle);
            });
        }

        if (binding.quickLogsButton != null) {
            binding.quickLogsButton.setOnClickListener(v -> ZHTools.shareLogs(requireActivity()));
        }

        binding.versionName.setSelected(true);
        binding.versionInfo.setSelected(true);

        refreshCurrentVersion();

        // BUG 5 FIX: Populate RAM and Renderer stats in right panel
        refreshStatusPanel();
    }

    // BUG 7 + BUG 5 FIX: Refresh version info and ready tag properly
    private void refreshCurrentVersion() {
        Version version = VersionsManager.INSTANCE.getCurrentVersion();

        if (version != null) {
            binding.versionName.setText(version.getVersionName());

            VersionInfo versionInfo = version.getVersionInfo();
            if (versionInfo != null) {
                binding.versionInfo.setText(versionInfo.getInfoString());
                binding.versionInfo.setVisibility(View.VISIBLE);
            } else {
                binding.versionInfo.setVisibility(View.GONE);
            }

            new VersionIconUtils(version).start(binding.versionIcon);
            binding.managerProfileButton.setVisibility(View.VISIBLE);

            // BUG 7 FIX: Only show "Ready" tag when a version is actually selected
            if (binding.versionTagReady != null) {
                binding.versionTagReady.setVisibility(View.VISIBLE);
            }

        } else {
            // No version installed — show placeholder, hide ready tag
            binding.versionName.setText(R.string.version_no_versions);
            binding.versionInfo.setVisibility(View.GONE);
            binding.managerProfileButton.setVisibility(View.GONE);

            // BUG 7 FIX: Hide "Ready" tag when no version is installed
            if (binding.versionTagReady != null) {
                binding.versionTagReady.setVisibility(View.GONE);
            }
        }
    }

    // BUG 5 FIX: Populate RAM / JRE / Renderer stats from actual settings
    private void refreshStatusPanel() {
        try {
            // RAM — read directly from SharedPreferences since ramAllocation is Kotlin lazy
            if (binding.statRamValue != null) {
                try {
                    int ram = com.movtery.zalithlauncher.setting.Settings.Manager
                            .getInt("allocation",
                                    LauncherPreferences.findBestRAMAllocation(requireContext()));
                    binding.statRamValue.setText(ram + " MB alloc");
                } catch (Exception e) {
                    binding.statRamValue.setText("RAM");
                }
            }

            // Active JRE — read from settings, then forceReread(name)
            if (binding.statJreValue != null) {
                try {
                    String defaultRuntime = AllSettings.getDefaultRuntime().getValue();
                    if (defaultRuntime != null && !defaultRuntime.isEmpty()) {
                        net.kdt.pojavlaunch.multirt.Runtime rt = MultiRTUtils.forceReread(defaultRuntime);
                        binding.statJreValue.setText(rt != null ? rt.name : defaultRuntime);
                    } else {
                        binding.statJreValue.setText("Java");
                    }
                } catch (Exception e) {
                    binding.statJreValue.setText("Java");
                }
            }

            // Renderer — AllSettings.renderer is @JvmStatic StringSettingUnit
            if (binding.statRendererValue != null) {
                try {
                    String renderer = AllSettings.renderer.getValue();
                    binding.statRendererValue.setText(renderer != null ? renderer : "GL4ES");
                } catch (Exception e) {
                    binding.statRendererValue.setText("GL4ES");
                }
            }

        } catch (Exception e) {
            // Silently fail — stats are non-critical UI
        }
    }

    private void showTaskRunningToast() {
        TaskExecutors.runInUIThread(() ->
                Toast.makeText(requireContext(), R.string.version_manager_task_in_progress, Toast.LENGTH_SHORT).show());
    }

    // BUG FIX: isTaskRunning() is final in BaseFragment — removed override, use directly
    private boolean checkTaskRunning() {
        return ProgressKeeper.getTaskCount() != 0;
    }

    @Subscribe()
    public void event(RefreshVersionsEvent event) {
        if (event.getMode() == END) {
            TaskExecutors.runInUIThread(this::refreshCurrentVersion);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void event(AccountUpdateEvent event) {
        if (accountViewWrapper != null) accountViewWrapper.refreshAccountInfo();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }

    @Override
    public void slideIn(AnimPlayer animPlayer) {
        animPlayer.apply(new AnimPlayer.Entry(binding.launcherMenu, Animations.BounceInDown))
                .apply(new AnimPlayer.Entry(binding.playLayout, Animations.BounceInLeft))
                .apply(new AnimPlayer.Entry(binding.playButtonsLayout, Animations.BounceEnlarge));
    }

    @Override
    public void slideOut(AnimPlayer animPlayer) {
        animPlayer.apply(new AnimPlayer.Entry(binding.launcherMenu, Animations.FadeOutUp))
                .apply(new AnimPlayer.Entry(binding.playLayout, Animations.FadeOutRight))
                .apply(new AnimPlayer.Entry(binding.playButtonsLayout, Animations.BounceShrink));
    }
}

