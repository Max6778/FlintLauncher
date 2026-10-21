package net.kdt.pojavlaunch.fragments;

import static com.movtery.zalithlauncher.event.single.RefreshVersionsEvent.MODE.END;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.bumptech.glide.Glide;

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
import com.movtery.zalithlauncher.renderer.RendererInterface;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.task.TaskExecutors;
import com.movtery.zalithlauncher.ui.fragment.AboutFragment;
import com.movtery.zalithlauncher.ui.fragment.FragmentWithAnim;
import com.movtery.zalithlauncher.ui.fragment.VersionManagerFragment;
import com.movtery.zalithlauncher.ui.fragment.VersionsListFragment;
import com.movtery.zalithlauncher.ui.subassembly.account.AccountViewWrapper;
import com.movtery.zalithlauncher.utils.ZHTools;
import net.kdt.pojavlaunch.utils.FlintAccentUtils;
import com.movtery.zalithlauncher.utils.anim.ViewAnimUtils;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class MainMenuFragment extends FragmentWithAnim {
    public static final String TAG = "MainMenuFragment";
    private static final String HOME_IMAGE_PREFERENCES = "flint_launcher_ui";
    private static final String HOME_IMAGE_URI = "cat_image_uri";
    private static final String HOME_IMAGE_HIDDEN = "cat_image_hidden";
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

        // ── Install JAR ──
        binding.installJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        binding.installJarButton.setOnLongClickListener(v -> {
            runInstallerWithConfirmation(true);
            return true;
        });

        // Version card
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

        // Play button
        binding.playButton.setOnClickListener(v ->
                EventBus.getDefault().post(new LaunchGameEvent()));

        binding.versionName.setSelected(true);
        binding.versionInfo.setSelected(true);

        refreshCurrentVersion();
        refreshStatusPanel();
        refreshCatImage();
    }

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



        } else {
            binding.versionName.setText(R.string.version_no_versions);
            binding.versionInfo.setVisibility(View.GONE);
            binding.managerProfileButton.setVisibility(View.GONE);


        }
    }

    private void refreshCatImage() {
        if (binding == null || !isAdded()) return;

        android.content.SharedPreferences preferences = requireContext().getSharedPreferences(
                HOME_IMAGE_PREFERENCES,
                android.content.Context.MODE_PRIVATE
        );
        if (preferences.getBoolean(HOME_IMAGE_HIDDEN, false)) {
            binding.catImage.setVisibility(View.GONE);
            Glide.with(binding.catImage).clear(binding.catImage);
            return;
        }

        binding.catImage.setVisibility(View.VISIBLE);
        String customImage = preferences.getString(HOME_IMAGE_URI, null);
        if (customImage == null) {
            Glide.with(binding.catImage).load(R.raw.cat).centerInside().into(binding.catImage);
        } else {
            Glide.with(binding.catImage).load(Uri.parse(customImage)).centerInside().into(binding.catImage);
        }
    }

    private void refreshStatusPanel() {
        try {

            // RAM
            // getRamAllocation() returns the Kotlin Lazy; first .getValue() forces it to IntSettingUnit,
            // second .getValue() reads the int.
            if (binding.statRamValue != null) {
                try {
                    int ram = AllSettings.getRamAllocation().getValue().getValue();
                    binding.statRamValue.setText(ram + " MB alloc");
                } catch (Exception e) {
                    binding.statRamValue.setText("RAM");
                }
            }

            // Renderer
            // AllSettings.renderer is a @JvmStatic val StringSettingUnit.
            if (binding.statRendererValue != null) {
                try {
                    String renderer = AllSettings.getRenderer().getValue();
                    String rendererName = renderer;
                    if (renderer != null) {
                        for (RendererInterface availableRenderer :
                                Renderers.INSTANCE.getCompatibleRenderers(requireContext()).getSecond()) {
                            if (renderer.equals(availableRenderer.getUniqueIdentifier())) {
                                rendererName = availableRenderer.getRendererName();
                                break;
                            }
                        }
                    }
                    if (rendererName == null || rendererName.equals(renderer)) {
                        rendererName = "Krypton Wrapper";
                    }
                    binding.statRendererValue.setText(rendererName);

                } catch (Exception e) {
                    binding.statRendererValue.setText("GL4ES");
                }
            }

        } catch (Exception ignored) {
        }
    }

    private void showTaskRunningToast() {
        TaskExecutors.runInUIThread(() ->
                Toast.makeText(
                        requireContext(),
                        R.string.version_manager_task_in_progress,
                        Toast.LENGTH_SHORT
                ).show());
    }

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
        if (accountViewWrapper != null) {
            accountViewWrapper.refreshAccountInfo();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        refreshCatImage();
        FlintAccentUtils.applyAccentColor(
                binding.getRoot(),
                FlintAccentUtils.getAccentColor(requireContext())
        );
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0) {
            Tools.installMod(requireActivity(), isCustomArgs);
        } else {
            Toast.makeText(
                    requireContext(),
                    R.string.tasks_ongoing,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    public void slideIn(AnimPlayer animPlayer) {
        animPlayer.apply(
                new AnimPlayer.Entry(binding.launcherMenu, Animations.BounceInDown)
        ).apply(
                new AnimPlayer.Entry(binding.playLayout, Animations.BounceInLeft)
        ).apply(
                new AnimPlayer.Entry(binding.playButtonsLayout, Animations.BounceEnlarge)
        );
    }

    @Override
    public void slideOut(AnimPlayer animPlayer) {
        animPlayer.apply(
                new AnimPlayer.Entry(binding.launcherMenu, Animations.FadeOutUp)
        ).apply(
                new AnimPlayer.Entry(binding.playLayout, Animations.FadeOutRight)
        ).apply(
                new AnimPlayer.Entry(binding.playButtonsLayout, Animations.BounceShrink)
        );
    }
}

