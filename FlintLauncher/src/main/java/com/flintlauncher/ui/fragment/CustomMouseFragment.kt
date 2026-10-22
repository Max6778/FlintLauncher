package com.flintlauncher.ui.fragment

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.flintlauncher.R
import com.flintlauncher.databinding.FragmentCustomMouseBinding
import com.flintlauncher.setting.AllSettings
import com.flintlauncher.task.Task
import com.flintlauncher.task.TaskExecutors
import com.flintlauncher.ui.dialog.FilesDialog
import com.flintlauncher.ui.dialog.FilesDialog.FilesButton
import com.flintlauncher.ui.subassembly.filelist.FileIcon
import com.flintlauncher.ui.subassembly.filelist.FileItemBean
import com.flintlauncher.ui.subassembly.filelist.FileRecyclerViewCreator
import com.flintlauncher.utils.NewbieGuideUtils
import com.flintlauncher.utils.path.PathManager
import com.flintlauncher.utils.ZHTools
import com.flintlauncher.utils.file.FileTools
import com.flintlauncher.utils.file.FileTools.Companion.mkdirs
import com.flintlauncher.utils.image.ImageUtils.Companion.isImage
import com.flintlauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Tools
import java.io.File

class CustomMouseFragment : FragmentWithAnim(R.layout.fragment_custom_mouse) {
    companion object {
        const val TAG: String = "CustomMouseFragment"
    }

    private lateinit var binding: FragmentCustomMouseBinding
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private var fileRecyclerViewCreator: FileRecyclerViewCreator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentLauncher = registerForActivityResult<Array<String>, Uri>(ActivityResultContracts.OpenDocument()) { result: Uri? ->
            result?.let { uri ->
                val dialog = ZHTools.showTaskRunningDialog(requireContext())
                Task.runTask {
                    FileTools.copyFileInBackground(requireActivity(), uri, mousePath().absolutePath)
                }.ended(TaskExecutors.getAndroidUI()) {
                    Toast.makeText(requireActivity(), getString(R.string.file_added), Toast.LENGTH_SHORT).show()
                    loadData()
                }.onThrowable { e ->
                    Tools.showErrorRemote(e)
                }.finallyTask(TaskExecutors.getAndroidUI()) {
                    dialog.dismiss()
                }.execute()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCustomMouseBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViews()

        binding.actionBar.apply {
            returnButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
            addFileButton.setOnClickListener { openDocumentLauncher.launch(arrayOf("image/*")) }
            refreshButton.setOnClickListener { loadData() }
        }

        loadData()

        startNewbieGuide()
    }

    private fun startNewbieGuide() {
        if (NewbieGuideUtils.showOnlyOne(TAG)) return
        val fragmentActivity = requireActivity()
        binding.actionBar.apply {
            TapTargetSequence(fragmentActivity)
                .targets(
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, refreshButton, getString(R.string.generic_refresh), getString(R.string.newbie_guide_general_refresh)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, addFileButton, getString(R.string.custom_mouse_add), getString(R.string.newbie_guide_mouse_import)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, returnButton, getString(R.string.generic_close), getString(R.string.newbie_guide_general_close)))
                .start()
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun loadData() {
        val fileItemBeans = FileRecyclerViewCreator.loadItemBeansFromPath(
            requireActivity(),
            mousePath(),
            FileIcon.FILE,
            showFile = true,
            showFolder = false
        )
        fileItemBeans.add(0, FileItemBean(
            getString(R.string.custom_mouse_default),
            ContextCompat.getDrawable(requireActivity(), R.drawable.ic_mouse_pointer)
        ))
        TaskExecutors.runInUIThread {
            fileRecyclerViewCreator?.loadData(fileItemBeans)
            //默认显示当前选中的鼠标
            refreshIcon()
        }
    }

    private fun mousePath(): File {
        val path = File(PathManager.DIR_CUSTOM_MOUSE)
        if (!path.exists()) mkdirs(path)
        return path
    }

    private fun refreshIcon() {
        binding.mouseIcon.apply {
            ZHTools.getCustomMouse()?.let { file ->
                Glide.with(requireActivity())
                    .load(file)
                    .override(width, height)
                    .fitCenter()
                    .into(DrawableImageViewTarget(this))
                return@apply
            }
            setImageDrawable(ZHTools.customMouse(context))
        }
    }

    private fun initViews() {
        binding.actionBar.apply {
            addFileButton.setContentDescription(getString(R.string.custom_mouse_add))
            searchButton.visibility = View.GONE
            pasteButton.visibility = View.GONE
            createFolderButton.visibility = View.GONE

            ZHTools.setTooltipText(
                returnButton,
                addFileButton,
                refreshButton
            )
        }

        fileRecyclerViewCreator = FileRecyclerViewCreator(requireActivity(), binding.recyclerView, { position: Int, fileItemBean: FileItemBean ->
                val file = fileItemBean.file
                val fileName = file?.name
                val isDefaultMouse = position == 0

                val filesButton = FilesButton()
                filesButton.setButtonVisibility(false, false,
                    !isDefaultMouse, !isDefaultMouse, !isDefaultMouse, (isDefaultMouse || isImage(file))) //默认虚拟鼠标不支持分享、重命名、删除操作

                //如果选中的虚拟鼠标是默认的虚拟鼠标，那么将加上额外的提醒
                var message = getString(R.string.file_message)
                if (isDefaultMouse) message += """
     
     ${getString(R.string.custom_mouse_message_default)}
     """.trimIndent()
                filesButton.setMessageText(message)
                filesButton.setMoreButtonText(getString(R.string.generic_select))

                val filesDialog = FilesDialog(requireActivity(), filesButton, Task.runTask { loadData() }, mousePath(), file)
                filesDialog.setMoreButtonClick {
                    AllSettings.customMouse.put(fileName ?: "").save()
                    refreshIcon()
                    Toast.makeText(requireActivity(),
                        StringUtils.insertSpace(getString(R.string.custom_mouse_added), (fileName ?: getString(R.string.custom_mouse_default))),
                        Toast.LENGTH_SHORT).show()
                    filesDialog.dismiss()
                }
                filesDialog.show()
            },
            null
        )
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.mouseLayout, Animations.BounceInDown))
            .apply(AnimPlayer.Entry(binding.operateLayout, Animations.BounceInLeft))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.mouseLayout, Animations.FadeOutUp))
            .apply(AnimPlayer.Entry(binding.operateLayout, Animations.FadeOutRight))
    }
}
