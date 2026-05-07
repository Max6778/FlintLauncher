package com.movtery.zalithlauncher.ui.fragment.about

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.movtery.zalithlauncher.InfoCenter
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.FragmentAboutInfoPageBinding
import com.movtery.zalithlauncher.ui.subassembly.about.AboutItemBean
import com.movtery.zalithlauncher.ui.subassembly.about.AboutItemBean.AboutItemButtonBean
import com.movtery.zalithlauncher.ui.subassembly.about.AboutRecyclerAdapter
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.path.UrlManager

class AboutInfoPageFragment() : Fragment(R.layout.fragment_about_info_page) {
    private lateinit var binding: FragmentAboutInfoPageBinding
    private val mAboutData: MutableList<AboutItemBean> = ArrayList()
    private var parentPager2: ViewPager2? = null

    constructor(parentPager: ViewPager2): this() {
        this.parentPager2 = parentPager
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutInfoPageBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        loadAboutData(requireContext().resources)

        val context = requireActivity()

        binding.apply {
            dec1.text = InfoCenter.replaceName(context, R.string.about_dec1)
            dec2.text = InfoCenter.replaceName(context, R.string.about_dec2)
            dec3.text = InfoCenter.replaceName(context, R.string.about_dec3)

            githubButton.setOnClickListener { ZHTools.openLink(requireActivity(), UrlManager.URL_HOME) }
            licenseButton.setOnClickListener { ZHTools.openLink(requireActivity(), "https://www.gnu.org/licenses/gpl-3.0.html") }

            val aboutAdapter = AboutRecyclerAdapter(this@AboutInfoPageFragment.mAboutData)
            aboutRecycler.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = aboutAdapter
            }

            sponsor.setOnClickListener { _ ->
                parentPager2?.currentItem = 1
            }

            // QQ group removed — FlintLauncher is a global project
            qqGroupButton.visibility = View.GONE

            // GitHub replaces Discord for now
            discordButton.setOnClickListener {
                ZHTools.openLink(requireActivity(), UrlManager.URL_HOME)
            }
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun loadAboutData(resources: Resources) {
        mAboutData.clear()

        // PojavLauncher — core engine, required GPL credit
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.ic_pojav_full, requireContext().theme),
                "PojavLauncherTeam",
                getString(R.string.about_PojavLauncher_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    "GitHub",
                    "https://github.com/PojavLauncherTeam/PojavLauncher"
                )
            )
        )

        // ZalithLauncher — base we forked from, required GPL credit
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_movtery, requireContext().theme),
                "MovTery",
                getString(R.string.about_MovTery_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    "GitHub",
                    "https://github.com/ZalithLauncher/ZalithLauncher"
                )
            )
        )

        // GL4ES
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.ic_pojav_full, requireContext().theme),
                "ptitSeb",
                "GL4ES — OpenGL to OpenGL ES translation layer used by FlintLauncher for rendering.",
                AboutItemButtonBean(
                    requireActivity(),
                    "GitHub",
                    "https://github.com/ptitSeb/gl4es"
                )
            )
        )

        // Vera-Firefly
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_verafirefly, requireContext().theme),
                "Vera-Firefly",
                getString(R.string.about_VeraFirefly_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    getString(R.string.about_access_link),
                    "https://github.com/Vera-Firefly"
                )
            )
        )

        // bangbang93 — BMCLAPI mirror
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_bangbang93, requireContext().theme),
                "bangbang93",
                getString(R.string.about_bangbang93_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    "GitHub",
                    "https://github.com/bangbang93"
                )
            )
        )
    }
}
