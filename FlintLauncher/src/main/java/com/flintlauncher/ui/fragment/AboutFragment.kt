package com.flintlauncher.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.flintlauncher.R
import com.flintlauncher.databinding.FragmentAboutBinding
import com.flintlauncher.ui.dialog.TipDialog
import com.flintlauncher.ui.fragment.about.AboutInfoPageFragment
import com.flintlauncher.ui.fragment.about.AboutSponsorPageFragment
import com.flintlauncher.utils.ZHTools
import com.flintlauncher.utils.path.UrlManager
import com.flintlauncher.utils.stringutils.StringUtils

class AboutFragment : FragmentWithAnim(R.layout.fragment_about) {
    companion object {
        const val TAG: String = "AboutFragment"
    }

    private lateinit var binding: FragmentAboutBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViewPager()

        binding.apply {
            appInfo.text = StringUtils.insertNewline(StringUtils.insertSpace(getString(R.string.about_version_name), ZHTools.getVersionName()),
                StringUtils.insertSpace(getString(R.string.about_version_code), ZHTools.getVersionCode()),
                StringUtils.insertSpace(getString(R.string.about_last_update_time), ZHTools.getLastUpdateTime(requireContext())),
                StringUtils.insertSpace(getString(R.string.about_version_status), ZHTools.getVersionStatus(requireContext())))
            appInfo.setOnClickListener{ StringUtils.copyText("text", appInfo.text.toString(), requireContext()) }

            returnButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
            supportDevelopment.setOnClickListener {
                TipDialog.Builder(requireActivity())
                    .setTitle(R.string.request_sponsorship_title)
                    .setMessage(R.string.request_sponsorship_message)
                    .setConfirm(R.string.about_button_support_development)
                    .setConfirmClickListener { ZHTools.openLink(requireActivity(), UrlManager.URL_SUPPORT) }
                    .showDialog()
            }
        }
    }

    private fun initViewPager() {
        binding.infoViewPager.apply {
            adapter = ViewPagerAdapter(requireActivity(), this)
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.infoViewPager, Animations.BounceInDown))
            .apply(AnimPlayer.Entry(binding.operateLayout, Animations.BounceInLeft))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.infoViewPager, Animations.FadeOutUp))
        animPlayer.apply(AnimPlayer.Entry(binding.operateLayout, Animations.FadeOutRight))
    }

    private class ViewPagerAdapter(
        fragmentActivity: FragmentActivity,
        private val viewPager: ViewPager2
    ): FragmentStateAdapter(fragmentActivity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment {
            return when(position) {
                0 -> AboutInfoPageFragment(viewPager)
                else -> AboutSponsorPageFragment()
            }
        }
    }
}

